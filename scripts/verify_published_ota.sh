#!/usr/bin/env bash
set -euo pipefail

# Verify the public downloads exactly as a fresh client would. No signing
# credentials, release mutation, or authenticated download is needed.
BASE=https://github.com/tpeditz/Private-vault-android-v2/releases
EXPECTED_CERT=3beeff138b716168b2f166983d42496b9d24f629b75ee25bdafd16676f06a770
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

download() {
  local url=$1 output=$2 limit=$3
  printf 'Checking %s\n' "$url"
  curl --fail --silent --show-error --location --max-redirs 5 \
    --proto '=https' --proto-redir '=https' --connect-timeout 15 \
    --max-time 120 --max-filesize "$limit" \
    --retry 5 --retry-all-errors --retry-delay 10 --retry-max-time 120 \
    --output "$output" "$url"
}

download "$BASE/latest/download/update.json" "$WORK/latest.json" 1048576
VERSION=$(python3 - "$WORK/latest.json" "$BASE" "$EXPECTED_CERT" <<'PY'
import json, re, sys

path, base, expected_cert = sys.argv[1:]
with open(path, encoding='utf-8') as f:
    m = json.load(f)
assert m['schemaVersion'] == 1, 'Unexpected manifest schema'
assert m['applicationId'] == 'com.tpeditz.privatevault.v2', 'Wrong package'
assert type(m['versionCode']) is int and m['versionCode'] > 0, 'Invalid versionCode'
assert re.fullmatch(r'\d+\.\d+\.\d+', m['versionName']), 'Invalid versionName'
assert m['apkUrl'] == f"{base}/download/v{m['versionName']}/private-vault.apk", 'Unexpected APK URL'
assert re.fullmatch(r'[0-9a-f]{64}', m['sha256']), 'Invalid APK digest'
assert type(m['sizeBytes']) is int and 0 < m['sizeBytes'] <= 250 * 1024 * 1024, 'Invalid APK size'
assert m['signingCertSha256'].replace(':', '').lower() == expected_cert, 'Wrong signing certificate'
print(m['versionName'])
PY
)

download "$BASE/download/v$VERSION/update.json" "$WORK/versioned.json" 1048576
cmp "$WORK/latest.json" "$WORK/versioned.json"
if [ "$#" -gt 0 ]; then
  cmp "$1" "$WORK/latest.json"
fi

download "$BASE/download/v$VERSION/private-vault.apk" "$WORK/versioned.apk" 262144000
download "$BASE/latest/download/private-vault.apk" "$WORK/latest.apk" 262144000
cmp "$WORK/versioned.apk" "$WORK/latest.apk"

python3 - "$WORK/latest.json" "$WORK/versioned.apk" <<'PY'
import hashlib, json, sys
from pathlib import Path

m = json.loads(Path(sys.argv[1]).read_text(encoding='utf-8'))
apk = Path(sys.argv[2])
assert apk.stat().st_size == m['sizeBytes'], 'APK size mismatch'
with apk.open('rb') as f:
    actual = hashlib.file_digest(f, 'sha256').hexdigest()
assert actual == m['sha256'], 'APK SHA-256 mismatch'
print(f"Verified full APK bytes: version={m['versionName']}, versionCode={m['versionCode']}, size={m['sizeBytes']}, sha256={actual}")
PY

BUILD_TOOLS_DIR=$(find "${ANDROID_HOME}/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)
APKSIGNER=${BUILD_TOOLS_DIR}/apksigner
AAPT2=${BUILD_TOOLS_DIR}/aapt2
SIGNATURE=$("$APKSIGNER" verify --verbose --print-certs "$WORK/versioned.apk")
printf '%s\n' "$SIGNATURE"
printf '%s\n' "$SIGNATURE" | awk -v expected="$EXPECTED_CERT" '
  /^Number of signers: / { signers=$NF }
  /(^|[[:space:]])Signer( #[0-9]+)?:? certificate SHA-256 digest:/ {
    count++
    if (tolower($NF) != expected) wrong++
  }
  END { exit !(signers == 1 && count >= 1 && wrong == 0) }
'

"$AAPT2" dump badging "$WORK/versioned.apk" > "$WORK/badging.txt"
python3 - "$WORK/latest.json" "$WORK/badging.txt" <<'PY'
import json, re, sys
from pathlib import Path

m = json.loads(Path(sys.argv[1]).read_text(encoding='utf-8'))
badging = Path(sys.argv[2]).read_text(encoding='utf-8')
p = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.M)
assert p and p.groups() == (m['applicationId'], str(m['versionCode']), m['versionName']), 'APK metadata mismatch'
assert 'application-debuggable' not in badging, 'Debuggable APK must not be published'
PY
echo "Verified live OTA endpoints, non-debug APK metadata, SHA-256 and signing certificate."
