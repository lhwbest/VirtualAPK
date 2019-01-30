#!/bin/bash

echo "START!!!!"

cd /Users/lihongwei/Documents/GitHub/VirtualAPK
./gradlew clean assembleDebug

adb install -r /Users/lihongwei/Documents/GitHub/VirtualAPK/app/build/outputs/apk/debug/app-debug.apk

cd /Users/lihongwei/Documents/GitHub/VirtualAPK/PluginDemo

./gradlew assemblePlugin

adb push /Users/lihongwei/Documents/GitHub/VirtualAPK/PluginDemo/app/build/outputs/apk/shanghai/release/app-shanghai-release.apk /sdcard/Test.apk

echo "SUCCESS!!!!"