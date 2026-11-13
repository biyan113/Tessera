#!/bin/zsh
# Builds tesserad and installs it on the tablet as a boot service, then (re)starts it.
# Usage: daemon/install.sh <adb-serial>
set -e
cd "${0:A:h}"
S=${1:?adb serial}
./build.sh
adb -s $S push build/tesserad.dex /data/local/tmp/tesserad.dex >/dev/null
adb -s $S push device/tessera-daemon.sh /data/local/tmp/tessera-daemon.sh >/dev/null
cat > build/setup.sh <<'EOS'
for p in $(pgrep -f 'tessera-daemo[n]') $(pgrep -f 'Tessera[d]'); do kill $p; done
mkdir -p /data/adb/tessera
cp /data/local/tmp/tesserad.dex /data/adb/tessera/tesserad.dex
chmod 644 /data/adb/tessera/tesserad.dex
cp /data/local/tmp/tessera-daemon.sh /data/adb/service.d/tessera-daemon.sh
chmod 755 /data/adb/service.d/tessera-daemon.sh
nohup sh /data/adb/service.d/tessera-daemon.sh >/dev/null 2>&1 &
EOS
adb -s $S push build/setup.sh /data/local/tmp/tessera-setup.sh >/dev/null
# `su -c 'a; b'` would run only `a` as root, so the steps go in as one script.
adb -s $S shell /debug_ramdisk/su -c 'sh /data/local/tmp/tessera-setup.sh'
echo "installed; helper starts in a few seconds"
