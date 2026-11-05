#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT
javac -d "$tmp_dir" app/src/main/java/za/co/epservice/jamguard/Protocol.java tests/ProtocolTest.java
java -cp "$tmp_dir" ProtocolTest
node --check app/src/main/assets/app.js
bash -n UPLOAD_WITH_TERMUX.sh
