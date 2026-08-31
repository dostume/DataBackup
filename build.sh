#!/bin/bash
# 构建脚本

cd repo/source
./gradlew clean
./gradlew assembleFossRelease --stacktrace