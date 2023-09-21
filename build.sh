#!/bin/bash
mode="--native-image --jvm 21 --graalvm-java-version 21 --graalvm-version 21.0.0"
case "$(uname -sm)" in
  Darwin\ arm64)   postfix=_darwin_arm64     ;;
  Darwin\ x86_64)  postfix=_darwin_amd64     ;;
  Linux\ armv5*)   postfix=_linux_armv5      ;;
  Linux\ armv6*)   postfix=_linux_armv6      ;;
  Linux\ armv7*)   postfix=_linux_armv7      ;;
  Linux\ armv8*)   postfix=_linux_arm64      ;;
  Linux\ aarch64*) postfix=_linux_arm64      ;;
  Linux\ *64)      postfix=_linux_amd64      ;;
  FreeBSD\ *64)    postfix=_freebsd_amd64    ;;
  OpenBSD\ *64)    postfix=_openbsd_amd64    ;;
  CYGWIN*\ *64)    postfix=_windows_amd64    ;;
  MINGW*\ *64)     postfix=_windows_amd64    ;;
  MSYS*\ *64)      postfix=_windows_amd64    ;;
  Windows*\ *64)   postfix=_windows_amd64    ;;
esac
if [[ $1 == "noarch" ]]; then
  mode="--assembly --jvm 8"
  postfix=_noarch
fi

if git diff-index --quiet HEAD --; then
  commit=$(git rev-parse --short HEAD)
  tag=v$(git show-ref --tags| grep $commit | awk -F"[/]" '{print $3}')
  if [[ $tag = "v" ]]; then
    tag=$commit
  fi
else
  tag=$(git rev-parse --short HEAD)-dirty
fi

if [[ $1 = "noarch" ]]; then
  platform=JVM
else
  platform=${postfix#_}
fi

mkdir -p builds
out=builds/ydiff$postfix
rm -f $out $out.tar.gz
scala-cli --power package -o $out $mode ydiff.sc -O -DydiffVersion=$tag -O -DydiffPlatform=$platform --jvm-index https://github.com/zhranklin/jvm-index/raw/patch-1/index.json
tar czf $out.tar.gz $out
