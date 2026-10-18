#!/usr/bin/env python3
"""Validate release assets, then publish the APK and finally the OSS update manifest."""

from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import re
import sys
from urllib.parse import urlsplit

from prepare_release import (
    MAX_APK_BYTES, MAX_JSON_BYTES, ReleaseValidationError, apk_filename,
    parse_json, read_limited, sha256_file, validate_cloud_base, validate_https_url,
    validate_manifest,
)


def load_assets(assets: Path, tag: str, expected_base: str, prefix: str) -> tuple[dict, bytes, Path]:
    validate_cloud_base(expected_base, prefix)
    payload = read_limited(assets / "update.json", MAX_JSON_BYTES)
    manifest = parse_json(payload, "update.json")
    _, name = validate_manifest(manifest, expected_base, prefix)
    if tag != "v" + name:
        raise ReleaseValidationError("release tag does not match update.json")
    apk = assets / apk_filename(name)
    if not apk.is_file() or not 0 < apk.stat().st_size <= MAX_APK_BYTES:
        raise ReleaseValidationError("release APK must be a nonempty file of at most 256 MiB")
    if sha256_file(apk) != manifest["sha256"]:
        raise ReleaseValidationError("APK SHA-256 does not match update.json")
    sums: dict[str, str] = {}
    for line in read_limited(assets / "SHA256SUMS", 1024).decode("ascii").splitlines():
        match = re.fullmatch(r"([0-9a-f]{64})  ([A-Za-z0-9.-]+)", line)
        if not match or match[2] in sums:
            raise ReleaseValidationError("SHA256SUMS contains invalid or duplicate entries")
        sums[match[2]] = match[1]
    expected_sums = {apk.name: manifest["sha256"], "update.json": hashlib.sha256(payload).hexdigest()}
    if sums != expected_sums:
        raise ReleaseValidationError("SHA256SUMS does not match the APK and update.json")
    return manifest, payload, apk


def is_missing(error: Exception) -> bool:
    return getattr(error, "status", None) == 404 and getattr(error, "code", None) == "NoSuchKey"


def read_remote_manifest(bucket: object, key: str) -> dict | None:
    try:
        response = bucket.get_object(key)
    except Exception as error:
        if is_missing(error):
            return None
        raise ReleaseValidationError("remote latest manifest could not be read; upload aborted") from error
    try:
        payload = response.read(MAX_JSON_BYTES + 1)
    except Exception as error:
        raise ReleaseValidationError("remote latest manifest could not be read; upload aborted") from error
    finally:
        close = getattr(response, "close", None)
        if close is not None:
            close()
    return parse_json(payload, "remote update.json")


def check_remote_latest(bucket: object, key: str, incoming: dict, expected_base: str, prefix: str) -> None:
    current = read_remote_manifest(bucket, key)
    if current is None:
        return
    validate_manifest(current, expected_base, prefix)
    if current["versionCode"] > incoming["versionCode"]:
        raise ReleaseValidationError("remote latest version is newer; rollback publication rejected")
    if current["versionCode"] == incoming["versionCode"]:
        if any(current[field] != incoming[field] for field in ("versionName", "sha256", "cloudUrl", "githubUrl")):
            raise ReleaseValidationError("same version has conflicting APK identity; publication rejected")


def check_existing_apk(bucket: object, key: str, digest: str, size: int) -> bool:
    try:
        response = bucket.head_object(key)
    except Exception as error:
        if is_missing(error):
            return False
        raise ReleaseValidationError("existing APK object could not be checked; upload aborted") from error
    headers = {str(key).lower(): str(value) for key, value in response.headers.items()}
    if headers.get("x-oss-meta-sha256") != digest or headers.get("content-length") != str(size):
        raise ReleaseValidationError("immutable APK object already exists with conflicting identity")
    return True


def mirror_release(assets: Path, tag: str, expected_base: str, bucket: object, prefix: str = "zhicolor") -> dict:
    manifest, payload, apk = load_assets(assets, tag, expected_base, prefix)
    latest_key = prefix + "/update.json"
    apk_key = f"{prefix}/{tag}/{apk.name}"
    check_remote_latest(bucket, latest_key, manifest, expected_base, prefix)
    already_present = check_existing_apk(bucket, apk_key, manifest["sha256"], apk.stat().st_size)
    if not already_present:
        try:
            result = bucket.put_object_from_file(apk_key, str(apk), headers={
                "Content-Type": "application/vnd.android.package-archive",
                "Cache-Control": "public, max-age=31536000, immutable",
                "x-oss-meta-sha256": manifest["sha256"],
                "x-oss-forbid-overwrite": "true",
            })
        except Exception as error:
            raise ReleaseValidationError("APK upload failed; latest manifest was not published") from error
        if not 200 <= getattr(result, "status", 0) < 300:
            raise ReleaseValidationError("APK upload returned a failure; latest manifest was not published")
        if not check_existing_apk(bucket, apk_key, manifest["sha256"], apk.stat().st_size):
            raise ReleaseValidationError("uploaded APK was not found; latest manifest was not published")
    if sha256_file(apk) != manifest["sha256"]:
        raise ReleaseValidationError("local APK changed during publication; latest manifest was not published")
    # Recheck after the APK upload so another completed release cannot be rolled back.
    check_remote_latest(bucket, latest_key, manifest, expected_base, prefix)
    try:
        result = bucket.put_object(latest_key, payload, headers={
            "Content-Type": "application/json; charset=utf-8",
            "Cache-Control": "no-cache, max-age=0, must-revalidate",
        })
    except Exception as error:
        raise ReleaseValidationError("latest manifest upload failed") from error
    if not 200 <= getattr(result, "status", 0) < 300:
        raise ReleaseValidationError("latest manifest upload returned a failure")
    return {"manifest": manifest, "apk_key": apk_key, "latest_key": latest_key,
            "apk_action": "already-present" if already_present else "uploaded"}


def create_bucket(environ: dict[str, str]) -> tuple[object, str]:
    required = ("OSS_ENDPOINT", "OSS_BUCKET", "OSS_ACCESS_KEY_ID", "OSS_ACCESS_KEY_SECRET")
    if any(not environ.get(name) for name in required):
        raise ReleaseValidationError("OSS_ENDPOINT, OSS_BUCKET, OSS_ACCESS_KEY_ID, and OSS_ACCESS_KEY_SECRET are required")
    endpoint = validate_https_url(environ["OSS_ENDPOINT"], "OSS endpoint")
    parsed = urlsplit(endpoint)
    if not parsed.hostname.endswith(".aliyuncs.com") or parsed.path not in ("", "/"):
        raise ReleaseValidationError("OSS endpoint must be an official aliyuncs.com HTTPS endpoint")
    if not re.fullmatch(r"[a-z0-9][a-z0-9-]{1,61}[a-z0-9]", environ["OSS_BUCKET"]):
        raise ReleaseValidationError("OSS_BUCKET is invalid")
    prefix = environ.get("OSS_PREFIX", "zhicolor")
    try:
        import oss2
    except ImportError as error:
        raise ReleaseValidationError("install oss2==2.19.1 before uploading") from error
    auth = oss2.Auth(environ["OSS_ACCESS_KEY_ID"], environ["OSS_ACCESS_KEY_SECRET"])
    return oss2.Bucket(auth, endpoint, environ["OSS_BUCKET"], connect_timeout=30), prefix


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assets", type=Path, required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--expected-base-url", required=True, help="Public HTTPS base ending in /zhicolor")
    args = parser.parse_args(argv)
    try:
        prefix = os.environ.get("OSS_PREFIX", "zhicolor")
        # Complete all local checks before creating an authenticated SDK client.
        load_assets(args.assets, args.tag, args.expected_base_url, prefix)
        bucket, prefix = create_bucket(os.environ)
        result = mirror_release(args.assets, args.tag, args.expected_base_url, bucket, prefix)
    except (OSError, ValueError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 2
    manifest = result["manifest"]
    print(f"MIRRORED version={manifest['versionName']} versionCode={manifest['versionCode']} tag={args.tag}")
    print(f"APK_KEY={result['apk_key']}")
    print(f"UPDATE_KEY={result['latest_key']}")
    print(f"APK_ACTION={result['apk_action']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
