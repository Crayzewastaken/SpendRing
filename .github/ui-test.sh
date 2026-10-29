#!/usr/bin/env bash
# Runs on the Android emulator in CI: sets a budget, feeds in sample Google Wallet
# notifications (both through the real notification listener and the test hook),
# then screenshots the dashboard, an edit dialog and settings.
set -x
OUT=screens
mkdir -p $OUT
PKG=com.spendring.app

adb root || true
sleep 3
adb wait-for-device
adb install -r -g SpendRing.apk
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0

# ---- 1. First open: no budget yet, so the Budget tab with the import box
adb shell am start -n $PKG/.MainActivity
sleep 4
adb exec-out screencap -p > $OUT/1-first-open.png
adb shell am force-stop $PKG

# ---- 2. Keep the imported budget; also read notifications from the shell (for the listener test)
NEXT=$(date -d "+9 days" +%F)
cat > spendring.xml <<EOF
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="askedNotif" value="true" />
    <string name="watchApps">com.google.android.apps.walletnfcrel
com.google.android.gms
com.android.shell</string>
</map>
EOF
adb push spendring.xml /data/local/tmp/spendring.xml
APPUID=$(adb shell stat -c %u /data/data/$PKG | tr -d '\r')
adb shell mkdir -p /data/data/$PKG/shared_prefs
adb shell cp /data/local/tmp/spendring.xml /data/data/$PKG/shared_prefs/spendring.xml
adb shell chown -R $APPUID:$APPUID /data/data/$PKG/shared_prefs
adb shell chmod 771 /data/data/$PKG/shared_prefs
adb shell chmod 660 /data/data/$PKG/shared_prefs/spendring.xml
adb shell restorecon -R /data/data/$PKG/shared_prefs || true

adb logcat -c
adb shell am start -n $PKG/.MainActivity
sleep 3
adb exec-out screencap -p > $OUT/2-budget-set-no-access.png

# Import a sample budget code (made-up numbers, same shape as a real one)
adb shell am start -n $PKG/.MainActivity --ei sr_tab 1
sleep 2
adb exec-out screencap -p > $OUT/2b-import-box.png
cat > plan.json <<'JSON'
{"pay":1200,"rent":180,"saving":700,"spending":150,"fuelMonthly":160,"bills":[["Rent",390],["Phone",45],["Fuel",160]],"savingsBalance":3000,"savingsDate":"2026-10-20","payAnchor":"2026-09-15","cashbackMin":500,"cashback":40,"extra":{"2026-11-10":200},"lumps":[["2027-07-25",1500,"Tax refund"]]}
JSON
adb push plan.json /data/local/tmp/plan.json
adb shell "am start -n $PKG/.MainActivity --es sr_plan \"\$(cat /data/local/tmp/plan.json)\""
sleep 2

# ---- 3. Turn on notification access, as the user would in Settings
adb shell cmd notification allow_listener $PKG/$PKG.WalletListener
sleep 3

# ---- 4. Sample notifications through the test hook (different times in the cycle)
feed() { # title text hoursAgo
  adb shell "am start -n $PKG/.MainActivity --es sr_title '$1' --es sr_text '$2' --ei sr_hours_ago $3" >/dev/null
  sleep 1.2
}
feed "Aldi" "\$41.30 with Visa ••4821" 100
feed "Woolworths" "\$34.20 with Visa ••4821" 40
feed "Google Wallet" "You paid A\$12.50 at Guzman y Gomez" 30
feed "BP Express" "\$65.00 with Visa ••4821" 20
feed "Kmart" "\$39.00 · Mastercard ••1234" 10
feed "Transaction declined" "\$250.00 at JB Hi-Fi was declined" 9
feed "Card added" "Your Visa is ready to use in Google Wallet" 8
feed "Uber Eats" "\$31.45 with Visa ••4821" 6
feed "Kmart" "Refund of \$15.00 with Visa ••4821" 3
# the same Uber Eats notification again (should be skipped as a duplicate)
feed "Uber Eats" "\$31.45 with Visa ••4821" 6

# ---- 5. A real posted notification, read by the listener service itself
for U in "su 2000" ""; do
  adb shell "$U cmd notification post -S bigtext -t 'Coles' coles1 '\$23.10 with Visa ••4821'" >> $OUT/post-output.txt 2>&1
  sleep 3
  if adb shell cat /data/data/$PKG/shared_prefs/spendring.xml | grep -q "Coles"; then echo "recorded via: $U" >> $OUT/post-output.txt; break; fi
done
adb shell "su 2000 cmd notification post -S bigtext -t 'Weekly summary' sum1 'You have 3 new offers'" >> $OUT/post-output.txt 2>&1
sleep 2
adb shell dumpsys notification --noredact | grep -E "pkg=|coles1|sum1" | head -20 >> $OUT/post-output.txt 2>&1

adb shell am start -n $PKG/.MainActivity --ei sr_tab 0
sleep 2
adb exec-out screencap -p > $OUT/3-main.png

# Budget tab
adb shell am start -n $PKG/.MainActivity --ei sr_tab 1
sleep 2
adb exec-out screencap -p > $OUT/4-budget.png
adb shell input swipe 540 1700 540 600 400
sleep 1
adb exec-out screencap -p > $OUT/5-budget-scrolled.png
adb shell input swipe 540 1700 540 600 400
sleep 1
adb exec-out screencap -p > $OUT/5b-budget-plan.png
adb shell input swipe 540 1700 540 600 400
sleep 1
adb exec-out screencap -p > $OUT/5c-budget-plan2.png
adb shell input swipe 540 1700 540 600 400
sleep 1
adb exec-out screencap -p > $OUT/5d-budget-purchases.png
adb shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1
adb pull /data/local/tmp/ui.xml $OUT/ui-budget.xml >/dev/null 2>&1
tap_text() {
  B=$(grep -oE "text=\"$1\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" $2 | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | head -1)
  if [ -n "$B" ]; then
    X1=$(echo $B | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\1/'); Y1=$(echo $B | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\2/')
    X2=$(echo $B | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\3/'); Y2=$(echo $B | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\4/')
    adb shell input tap $(((X1+X2)/2)) $(((Y1+Y2)/2))
  fi
}
tap_text "Woolworths" $OUT/ui-budget.xml
sleep 1.5
adb exec-out screencap -p > $OUT/6-edit-dialog.png
adb shell input keyevent KEYCODE_BACK
sleep 1
adb shell input swipe 540 600 540 2000 200; adb shell input swipe 540 600 540 2000 200; adb shell input swipe 540 600 540 2000 200
sleep 1
adb shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1
adb pull /data/local/tmp/ui.xml $OUT/ui-budget2.xml >/dev/null 2>&1
tap_text "+  Add" $OUT/ui-budget2.xml
sleep 1.5
adb exec-out screencap -p > $OUT/6b-add-dialog.png
adb shell input keyevent KEYCODE_BACK
sleep 1

# Settings tab
adb shell am start -n $PKG/.MainActivity --ei sr_tab 2
sleep 2
adb exec-out screencap -p > $OUT/7-settings.png
adb shell input swipe 540 1700 540 600 400; sleep 1
adb exec-out screencap -p > $OUT/8-settings-log.png
adb shell input swipe 540 1700 540 600 400; sleep 1
adb exec-out screencap -p > $OUT/9-settings-log2.png
adb shell input swipe 540 1700 540 600 400; sleep 1
adb exec-out screencap -p > $OUT/10-settings-bottom.png

# ---- 7. The "left" notification posted after a purchase
adb shell cmd statusbar expand-notifications
sleep 2
adb exec-out screencap -p > $OUT/11-notification-shade.png
adb shell cmd statusbar collapse

# Saved data, for checking the numbers
adb shell cat /data/data/$PKG/shared_prefs/spendring.xml > $OUT/prefs.xml
adb logcat -d > $OUT/logcat-full.txt
grep -E "FATAL|AndroidRuntime|$PKG" $OUT/logcat-full.txt | head -200 > $OUT/logcat-app.txt
exit 0
