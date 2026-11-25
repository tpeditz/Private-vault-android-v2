#!/usr/bin/env python3
"""
Generates Schema Version 1 `update.json` for Private Vault OTA updates.
Validates APK existence, size, SHA-256 digest, version metadata, and signing certificate fingerprint.
"""

import argparse
import hashlib
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

OFFICIAL_REPO = "tpeditz/Private-vault-android-v2"
OFFICIAL_APP_ID = "com.tpeditz.privatevault.v2"
OFFICIAL_SIGNING_CERT_SHA256 = (
    "3B:EE:FF:13:8B:71:61:68:B2:F1:66:98:3D:42:49:6B:"
    "9D:24:F6:29:B7:5E:E2:5B:DA:FD:16:67:6F:06:A7:70"
)


def compute_sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        while True:
            chunk = f.read(64 * 1024)
            if not chunk:
                break
            h.update(chunk)
    return h.hexdigest().lower()


def normalize_fingerprint(fp: str) -> str:
    cleaned = fp.replace(":", "").strip().lower()
    if not re.fullmatch(r"[0-9a-f]{64}", cleaned):
        raise ValueError(f"Invalid SHA-256 certificate fingerprint: {fp}")
    return ":".join(cleaned[i : i + 2].upper() for i in range(0, 64, 2))


def build_manifest(
    apk_path: Path,
    version_code: int,
    version_name: str,
    tag_name: str,
    release_notes: str = "",
    signing_cert_sha256: str = OFFICIAL_SIGNING_CERT_SHA256,
    published_at: str | None = None,
) -> dict:
    if not apk_path.is_file():
        raise FileNotFoundError(f"APK file not found: {apk_path}")
    size_bytes = apk_path.stat().st_size
    if size_bytes <= 0:
        raise ValueError(f"APK file is empty: {apk_path}")
    if version_code <= 0:
        raise ValueError(f"versionCode must be > 0, got {version_code}")
    if not version_name.strip():
        raise ValueError("versionName cannot be blank")
    if not tag_name.strip():
        raise ValueError("tagName cannot be blank")

    formatted_cert = normalize_fingerprint(signing_cert_sha256)
    expected_norm = normalize_fingerprint(OFFICIAL_SIGNING_CERT_SHA256)
    if formatted_cert != expected_norm:
        raise ValueError(
            f"Signing certificate mismatch: expected {expected_norm}, got {formatted_cert}"
        )

    sha256_hex = compute_sha256(apk_path)
    apk_filename = apk_path.name
    apk_url = (
        f"https://github.com/{OFFICIAL_REPO}/releases/download/"
        f"{tag_name.strip()}/{apk_filename}"
    )
    timestamp = published_at or datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

    return {
        "schemaVersion": 1,
        "applicationId": OFFICIAL_APP_ID,
        "versionCode": version_code,
        "versionName": version_name.strip(),
        "apkUrl": apk_url,
        "sha256": sha256_hex,
        "sizeBytes": size_bytes,
        "publishedAt": timestamp,
        "releaseNotes": release_notes.strip(),
        "signingCertSha256": formatted_cert,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Generate Private Vault OTA update.json")
    parser.add_argument("--apk", required=True, help="Path to signed release APK (private-vault.apk)")
    parser.add_argument("--output", required=True, help="Output path for update.json")
    parser.add_argument("--version-code", type=int, required=True, help="Integer Android versionCode")
    parser.add_argument("--version-name", required=True, help="Android versionName (e.g. 2.0.1)")
    parser.add_argument("--tag", required=True, help="GitHub Release tag (e.g. v2.0.1)")
    parser.add_argument("--release-notes", default="", help="Optional plain-text release notes")
    parser.add_argument(
        "--signing-cert-sha256",
        default=OFFICIAL_SIGNING_CERT_SHA256,
        help="SHA-256 fingerprint of release signing certificate",
    )
    args = parser.parse_args(argv)

    manifest = build_manifest(
        apk_path=Path(args.apk),
        version_code=args.version_code,
        version_name=args.version_name,
        tag_name=args.tag,
        release_notes=args.release_notes,
        signing_cert_sha256=args.signing_cert_sha256,
    )

    out_path = Path(args.output)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote OTA manifest {out_path} for versionCode={manifest['versionCode']} ({manifest['sha256']})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
