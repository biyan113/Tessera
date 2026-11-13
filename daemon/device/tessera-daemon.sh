#!/system/bin/sh
# Magisk service.d script: keeps tesserad, the Tessera root helper, running.
until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 2; done
sleep 6
while true; do
  CLASSPATH=/data/adb/tessera/tesserad.dex app_process / Tesserad >/data/adb/tessera/log 2>&1
  # If the helper dies, hand the system shade back so the tablet is never left without one.
  cmd statusbar send-disable-flag none
  sleep 3
done
