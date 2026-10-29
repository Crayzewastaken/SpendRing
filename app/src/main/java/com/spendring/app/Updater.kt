package com.spendring.app

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject

/**
 * Updates: looks at the latest GitHub release, downloads the APK and opens Android's installer.
 * Installs over the top (purchases and budget kept) because every build is signed with the
 * same key. This is the only thing the app ever goes online for; no money data is sent.
 */
object Updater {
    private const val REPO = "Crayzewastaken/SpendRing"

    data class Release(val build: Int, val url: String)

    @Volatile private var busy = false

    fun currentBuild(ctx: Context): Int {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else @Suppress("DEPRECATION") info.versionCode
        return code - 100
    }

    fun latest(): Release {
        val j = JSONObject(
            httpGet("https://api.github.com/repos/$REPO/releases/latest")
        )
        val build = j.optString("tag_name").removePrefix("build-").toIntOrNull() ?: 0
        val assets = j.optJSONArray("assets")
        var url = ""
        if (assets != null) for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            if (a.optString("name").endsWith(".apk")) url = a.optString("browser_download_url")
        }
        if (url.isEmpty()) throw Exception("No APK in the latest release")
        return Release(build, url)
    }

    /**
     * Runs when the app opens: at most every 3 hours, look for a newer build. If there is one,
     * download it straight away and open the installer (one tap on Android's Install button).
     */
    fun autoCheck(act: Activity) {
        val sp = act.getSharedPreferences("spendring", Context.MODE_PRIVATE)
        if (!sp.getBoolean("autoUpdate", true)) return
        if (System.currentTimeMillis() - sp.getLong("latestCheckedAt", 0L) < 3 * 3_600_000L) return
        if (busy) return
        busy = true
        Thread {
            val r = try { latest() } catch (_: Exception) { null }
            busy = false
            if (r == null) return@Thread
            sp.edit().putInt("latestBuild", r.build).apply()
            // Only wait 3 hours once you're up to date; a pending update is offered every time you open the app.
            if (r.build <= currentBuild(act)) sp.edit().putLong("latestCheckedAt", System.currentTimeMillis()).apply()
            act.runOnUiThread {
                if (act.isFinishing || r.build <= currentBuild(act)) return@runOnUiThread
                Toast.makeText(act, "New Spend Ring update (build ${r.build}), downloading…", Toast.LENGTH_LONG).show()
                download(act, r)
            }
        }.start()
    }

    fun latestKnown(ctx: Context): Int =
        ctx.getSharedPreferences("spendring", Context.MODE_PRIVATE).getInt("latestBuild", 0)

    fun check(act: Activity) {
        Toast.makeText(act, "Checking for updates…", Toast.LENGTH_SHORT).show()
        Thread {
            val result = try { latest() } catch (e: Exception) { null }
            act.runOnUiThread {
                if (act.isFinishing) return@runOnUiThread
                val mine = currentBuild(act)
                when {
                    result == null -> Toast.makeText(act, "Couldn't reach GitHub. Try again on Wi-Fi.", Toast.LENGTH_LONG).show()
                    result.build <= mine -> AlertDialog.Builder(act)
                        .setTitle("You're up to date")
                        .setMessage("You have build $mine, the latest.")
                        .setPositiveButton("OK", null).show()
                    else -> AlertDialog.Builder(act)
                        .setTitle("Update available")
                        .setMessage("Build ${result.build} is out (you have build $mine). Download and install it now? Your purchases and budget are kept.")
                        .setPositiveButton("Update") { _, _ -> download(act, result) }
                        .setNegativeButton("Later", null).show()
                }
            }
        }.start()
    }

    fun download(act: Activity, r: Release) {
        if (Build.VERSION.SDK_INT >= 26 && !act.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(act)
                .setTitle("One-time permission")
                .setMessage("Allow Spend Ring to install its own updates: switch on \"Allow from this source\" on the next screen, then come back. After this, updates just need one tap on Install.")
                .setPositiveButton("Open setting") { _, _ ->
                    act.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${act.packageName}")))
                }
                .setNegativeButton("Cancel", null).show()
            return
        }
        val dm = act.getSystemService(DownloadManager::class.java) ?: return
        val name = "SpendRing-build-${r.build}.apk"
        act.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.listFiles()?.forEach { it.delete() }
        val req = DownloadManager.Request(Uri.parse(r.url))
            .setTitle("Spend Ring update")
            .setMimeType("application/vnd.android.package-archive")
            .setDestinationInExternalFilesDir(act, Environment.DIRECTORY_DOWNLOADS, name)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
        val id = dm.enqueue(req)
        Toast.makeText(act, "Downloading update…", Toast.LENGTH_SHORT).show()

        val app = act.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (i?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return
                try { app.unregisterReceiver(this) } catch (_: Exception) {}
                val uri = dm.getUriForDownloadedFile(id)
                if (uri == null) {
                    Toast.makeText(app, "Update download failed", Toast.LENGTH_LONG).show()
                    return
                }
                val install = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                try { app.startActivity(install) } catch (e: Exception) {
                    Toast.makeText(app, "Couldn't open the installer: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else app.registerReceiver(receiver, filter)
    }

    private fun httpGet(url: String): String {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", "SpendRing-app")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            if (conn.responseCode !in 200..299) throw java.io.IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
