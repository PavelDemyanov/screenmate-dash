#!/bin/bash
# Launch the smdash Android emulator and bring up our overlay dashboard.
# Used both from the Terminal and by the "Screenmate Emulator.app" Launchpad shortcut.
# It does NOT rebuild the app — it shows whatever app-debug.apk is currently built
# (installs it if the AVD doesn't have the app yet). Rebuild with redeploy/gradle separately.

set -u

ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
ADB=/opt/homebrew/bin/adb
EMU="$ANDROID_HOME/emulator/emulator"
AVD=smdash
SERIAL=emulator-5554
APK="$HOME/projects/screenmate-dash/app/build/outputs/apk/debug/app-debug.apk"
LOG=/tmp/smdash-emulator.log
export ANDROID_HOME JAVA_HOME

notify() { osascript -e "display notification \"$2\" with title \"Screenmate Emulator\" subtitle \"$1\"" >/dev/null 2>&1; }

# 1. Launch the emulator detached (survives this script exiting) unless already running.
#    The `emulator` binary execs into qemu-system-*, so match the stable "-avd <name>" arg.
if ! pgrep -f -- "-avd $AVD" >/dev/null 2>&1; then
  notify "Запуск" "Поднимаю эмулятор…"
  nohup "$EMU" -avd "$AVD" -no-snapshot -gpu auto -no-boot-anim >"$LOG" 2>&1 &
  disown
else
  notify "Запуск" "Эмулятор уже запущен, поднимаю дашборд…"
fi

# 2. Wait for the device to finish booting (up to ~120s).
"$ADB" wait-for-device
for i in $(seq 1 60); do
  [ "$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
  sleep 2
done

# 3. Root + overlay permissions (the service is exported=false → needs root to start).
"$ADB" -s "$SERIAL" root >/dev/null 2>&1
"$ADB" -s "$SERIAL" wait-for-device

# 4. Install the app if this AVD doesn't have it yet.
if ! "$ADB" -s "$SERIAL" shell pm list packages 2>/dev/null | grep -q "package:app.smdash"; then
  [ -f "$APK" ] && "$ADB" -s "$SERIAL" install -r "$APK" >/dev/null 2>&1
fi

"$ADB" -s "$SERIAL" shell appops set app.smdash SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1
"$ADB" -s "$SERIAL" shell pm grant app.smdash android.permission.WRITE_SECURE_SETTINGS >/dev/null 2>&1

# 5. Start the overlay service and force-show our dashboard.
"$ADB" -s "$SERIAL" shell am start-foreground-service -n app.smdash/.OverlayService >/dev/null 2>&1
sleep 2
"$ADB" -s "$SERIAL" shell am broadcast -a app.smdash.SHOWOURS -p app.smdash >/dev/null 2>&1

# 6. Open the transparency settings panel beside the dashboard. No stock app exists on the
#    emulator, so this panel is how you drive our dashboard's background transparency
#    (the slider sets the same value the car's stock "Dashboard Transparency" setting would).
"$ADB" -s "$SERIAL" shell am start -n app.smdash/.SettingsActivity >/dev/null 2>&1

if "$ADB" -s "$SERIAL" shell ps -A 2>/dev/null | grep -q app.smdash; then
  notify "Готово" "Дашборд + панель прозрачности запущены ✅"
else
  notify "Внимание" "Оверлей не поднялся, см. $LOG"
fi
