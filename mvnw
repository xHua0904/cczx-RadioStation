#!/bin/sh
set -eu
cd "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
if command -v mvn >/dev/null 2>&1; then
  exec mvn -Dmaven.repo.local=.tools/repository "$@"
fi
version=3.9.11
maven=".tools/apache-maven-$version/bin/mvn"
if [ ! -f "$maven" ]; then
  mkdir -p .tools
  url="https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$version/apache-maven-$version-bin.zip"
  curl --fail --location "$url" -o .tools/maven.zip
  curl --fail --location "$url.sha512" -o .tools/maven.zip.sha512
  expected=$(awk '{print $1}' .tools/maven.zip.sha512)
  if command -v sha512sum >/dev/null 2>&1; then
    actual=$(sha512sum .tools/maven.zip | awk '{print $1}')
  else
    actual=$(shasum -a 512 .tools/maven.zip | awk '{print $1}')
  fi
  [ "$actual" = "$expected" ] || { echo 'Maven 文件校验失败' >&2; exit 1; }
  unzip -q -o .tools/maven.zip -d .tools
fi
exec sh "$maven" -Dmaven.repo.local=.tools/repository "$@"
