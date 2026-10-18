#!/usr/bin/env python3
"""Prepare reviewed release assets locally; this command never uploads files."""

from __future__ import annotations

import argparse
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import shutil
import sys
import tempfile
from urllib.parse import urlsplit
import zipfile


ROOT = Path(__file__).resolve().parent.parent
APPLICATION_ID = "com.naivety.zhicolor"
GITHUB_RELEASES = "https://github.com/XZOWO/ZHIColor/releases/tag/"
MAX_APK_BYTES = 256 * 1024 * 1024
MAX_JSON_BYTES = 256 * 1024
MAX_CHANGELOG_BYTES = 64 * 1024
STABLE_VERSION = re.compile(r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\Z")
SHA256 = re.compile(r"[0-9a-f]{64}\Z")


class ReleaseValidationError(ValueError):
    """A release input does not meet the public release contract."""


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_limited(path: Path, maximum: int) -> bytes:
    with path.open("rb") as stream:
        payload = stream.read(maximum + 1)
    if len(payload) > maximum:
        raise ReleaseValidationError(f"{path.name} exceeds the size limit")
    return payload


def parse_json(payload: bytes, label: str) -> dict:
    if len(payload) > MAX_JSON_BYTES:
        raise ReleaseValidationError(f"{label} exceeds the size limit")

    def unique_object(pairs: list[tuple[str, object]]) -> dict:
        result: dict = {}
        for key, value in pairs:
            if key in result:
                raise ReleaseValidationError(f"{label} contains duplicate fields")
            result[key] = value
        return result

    try:
        value = json.loads(payload.decode("utf-8"), object_pairs_hook=unique_object)
    except (UnicodeError, json.JSONDecodeError) as error:
        raise ReleaseValidationError(f"{label} is not valid UTF-8 JSON") from error
    if not isinstance(value, dict):
        raise ReleaseValidationError(f"{label} must be a JSON object")
    return value


def validate_version(code: object, name: object) -> tuple[int, str]:
    if type(code) is not int or code <= 0:
        raise ReleaseValidationError("versionCode must be a positive integer")
    if not isinstance(name, str) or not STABLE_VERSION.fullmatch(name):
        raise ReleaseValidationError("versionName must be a stable major.minor.patch version")
    return code, name


def read_version(path: Path) -> tuple[int, str]:
    values: dict[str, str] = {}
    for line in read_limited(path, 4096).decode("utf-8-sig").splitlines():
        line = line.strip()
        if not line or line.startswith(("#", "!")):
            continue
        if "=" not in line:
            raise ReleaseValidationError("version.properties contains an invalid line")
        key, value = (item.strip() for item in line.split("=", 1))
        if key in values:
            raise ReleaseValidationError("version.properties contains duplicate fields")
        values[key] = value
    try:
        raw_code, name = values["versionCode"], values["versionName"]
        if not re.fullmatch(r"[1-9][0-9]*", raw_code):
            raise ValueError()
        return validate_version(int(raw_code), name)
    except (KeyError, ValueError) as error:
        raise ReleaseValidationError("version.properties must define a positive versionCode and stable versionName") from error


def validate_https_url(value: str, label: str) -> str:
    if not isinstance(value, str) or any(character.isspace() for character in value):
        raise ReleaseValidationError(f"{label} must be an HTTPS URL")
    if any(character in value for character in ("\\", "%")):
        raise ReleaseValidationError(f"{label} contains an unsupported URL escape")
    try:
        parsed = urlsplit(value)
        port = parsed.port
    except ValueError as error:
        raise ReleaseValidationError(f"{label} contains an invalid host or port") from error
    host = (parsed.hostname or "").lower()
    if (parsed.scheme != "https" or not host or parsed.username is not None
            or parsed.password is not None or parsed.query or parsed.fragment
            or port not in (None, 443)):
        raise ReleaseValidationError(f"{label} must use public HTTPS without credentials, query, or fragment")
    if (not re.fullmatch(r"[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?", host)
            or "." not in host or ".." in host or host.endswith((".local", ".localhost", ".invalid", ".test"))):
        raise ReleaseValidationError(f"{label} must use a public DNS hostname")
    try:
        ipaddress.ip_address(host)
    except ValueError:
        pass
    else:
        raise ReleaseValidationError(f"{label} must use a public DNS hostname")
    segments = parsed.path.split("/")
    if any(segment in (".", "..") for segment in segments) or "//" in parsed.path:
        raise ReleaseValidationError(f"{label} contains an invalid path")
    return value


def validate_cloud_base(value: str, prefix: str = "zhicolor") -> str:
    validate_https_url(value, "cloud base URL")
    parsed = urlsplit(value)
    host = parsed.hostname.lower()
    if any(host == domain or host.endswith("." + domain)
           for domain in ("github.com", "githubusercontent.com", "github.io")):
        raise ReleaseValidationError("cloud base URL must be independent of GitHub")
    if (not re.fullmatch(r"[a-z0-9][a-z0-9/_-]*", prefix)
            or any(part in ("", ".", "..") for part in prefix.split("/"))
            or prefix.split("/")[-1] != "zhicolor"):
        raise ReleaseValidationError("OSS_PREFIX must be a relative object prefix ending in zhicolor")
    if parsed.path != "/" + prefix:
        raise ReleaseValidationError(f"cloud base URL must end in /{prefix} without a trailing slash")
    return value


def apk_filename(version_name: str) -> str:
    return f"ZHIColor-{version_name}.apk"


def validate_manifest(value: dict, cloud_base: str | None = None, prefix: str = "zhicolor") -> tuple[int, str]:
    cloud_enabled = cloud_base not in (None, "")
    expected_fields = {"versionCode", "versionName", "changelog", "githubUrl", "sha256"}
    if cloud_enabled:
        validate_cloud_base(cloud_base, prefix)
        expected_fields.add("cloudUrl")
    if set(value) != expected_fields:
        raise ReleaseValidationError("update.json fields do not match the release contract")
    code, name = validate_version(value["versionCode"], value["versionName"])
    changelog = value["changelog"]
    if not isinstance(changelog, str) or not changelog.strip() or len(changelog.encode("utf-8")) > MAX_CHANGELOG_BYTES:
        raise ReleaseValidationError("changelog must be nonempty and at most 64 KiB")
    if value["githubUrl"] != GITHUB_RELEASES + "v" + name:
        raise ReleaseValidationError("githubUrl does not match the official repository and release tag")
    if cloud_enabled and value["cloudUrl"] != f"{cloud_base}/v{name}/{apk_filename(name)}":
        raise ReleaseValidationError("cloudUrl does not match the expected immutable version path")
    if not isinstance(value["sha256"], str) or not SHA256.fullmatch(value["sha256"]):
        raise ReleaseValidationError("sha256 must be a lowercase SHA-256 digest")
    return code, name


def prepare_release(apk: Path, output: Path, changelog_file: Path,
                    cloud_base: str | None = None, version_file: Path = ROOT / "version.properties") -> dict:
    if cloud_base not in (None, ""):
        validate_cloud_base(cloud_base)
    code, name = read_version(version_file)
    apk = apk.resolve(strict=True)
    if not apk.is_file() or apk.suffix.lower() != ".apk" or not 0 < apk.stat().st_size <= MAX_APK_BYTES:
        raise ReleaseValidationError("APK must be a nonempty regular .apk file of at most 256 MiB")
    try:
        with zipfile.ZipFile(apk) as archive:
            if "AndroidManifest.xml" not in archive.namelist():
                raise ReleaseValidationError("APK does not contain AndroidManifest.xml")
    except zipfile.BadZipFile as error:
        raise ReleaseValidationError("APK is not a valid ZIP archive") from error

    metadata = parse_json(read_limited(apk.parent / "output-metadata.json", MAX_JSON_BYTES), "output-metadata.json")
    elements = metadata.get("elements")
    artifact_type = metadata.get("artifactType")
    if (metadata.get("applicationId") != APPLICATION_ID or metadata.get("variantName") != "release"
            or not isinstance(artifact_type, dict) or artifact_type.get("type") != "APK"
            or not isinstance(elements, list) or len(elements) != 1 or not isinstance(elements[0], dict)):
        raise ReleaseValidationError("APK metadata must describe one official release APK")
    element = elements[0]
    if (element.get("outputFile") != apk.name or element.get("filters") != []
            or validate_version(element.get("versionCode"), element.get("versionName")) != (code, name)):
        raise ReleaseValidationError("APK output metadata does not match the file and version.properties")

    try:
        changelog = read_limited(changelog_file, MAX_CHANGELOG_BYTES).decode("utf-8-sig").strip()
    except UnicodeError as error:
        raise ReleaseValidationError("changelog must be UTF-8 text") from error
    digest = sha256_file(apk)
    manifest = {"versionCode": code, "versionName": name, "changelog": changelog,
                "githubUrl": GITHUB_RELEASES + "v" + name}
    if cloud_base not in (None, ""):
        manifest["cloudUrl"] = f"{cloud_base}/v{name}/{apk_filename(name)}"
    manifest["sha256"] = digest
    validate_manifest(manifest, cloud_base)
    manifest_bytes = (json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    output = output.resolve()
    if output == apk.parent:
        raise ReleaseValidationError("output directory must differ from the APK build directory")
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".release-", dir=output) as temporary:
        staging = Path(temporary)
        shutil.copyfile(apk, staging / apk_filename(name))
        if sha256_file(staging / apk_filename(name)) != digest:
            raise ReleaseValidationError("APK changed while release assets were being prepared")
        (staging / "update.json").write_bytes(manifest_bytes)
        sums = f"{digest}  {apk_filename(name)}\n{hashlib.sha256(manifest_bytes).hexdigest()}  update.json\n"
        (staging / "SHA256SUMS").write_text(sums, encoding="utf-8", newline="\n")
        for filename in (apk_filename(name), "SHA256SUMS", "update.json"):
            os.replace(staging / filename, output / filename)
    return manifest


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--changelog-file", type=Path, required=True)
    parser.add_argument("--cloud-base-url", help="Optional public HTTPS base ending in /zhicolor; omit for GitHub-only assets")
    args = parser.parse_args(argv)
    try:
        manifest = prepare_release(args.apk, args.output, args.changelog_file, args.cloud_base_url)
    except (OSError, ValueError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 2
    output = args.output.resolve()
    print(f"PREPARED version={manifest['versionName']} versionCode={manifest['versionCode']} tag=v{manifest['versionName']}")
    print(f"APK={output / apk_filename(manifest['versionName'])}")
    print(f"UPDATE={output / 'update.json'}")
    print(f"SHA256SUMS={output / 'SHA256SUMS'}")
    print(f"SHA256={manifest['sha256']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
