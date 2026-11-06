#!/bin/zsh
# Compiles shade/src into build/shade.dex.
set -e
cd "${0:A:h}"
SDK=$(sed -n 's/sdk.dir=//p' ../local.properties)
BT=$(ls -d $SDK/build-tools/* | tail -1)
rm -rf build; mkdir -p build/classes
javac -source 8 -target 8 -nowarn -cp $SDK/platforms/android-36/android.jar -d build/classes src/*.java 2>&1 | grep -v "warning" || true
$BT/d8 --min-api 36 --lib $SDK/platforms/android-36/android.jar --output build build/classes/*.class
mv build/classes.dex build/shade.dex
