#!/usr/bin/env python3
"""Offline release preparation and mocked OSS publication regression tests."""

from __future__ import annotations

import copy
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
import zipfile

from prepare_release import ReleaseValidationError, prepare_release, validate_cloud_base, validate_manifest
from mirror_release import load_assets, mirror_release


BASE = "https://downloads.example.com/zhicolor"


class MissingObject(Exception):
    status = 404
    code = "NoSuchKey"


class FakeBucket:
    def __init__(self, remote=None):
        self.remote = remote
        self.objects = {}
        self.calls = []
        self.fail_apk = False
        self.fail_read = False
        self.mutate_apk = False
        self.newer_on_second_read = None
        self.read_count = 0

    def get_object(self, key):
        self.calls.append(("get", key))
        self.read_count += 1
        if self.fail_read:
            raise RuntimeError("offline")
        if self.newer_on_second_read is not None and self.read_count == 2:
            self.remote = self.newer_on_second_read
        if self.remote is None:
            raise MissingObject()
        return io.BytesIO(json.dumps(self.remote).encode("utf-8"))

    def head_object(self, key):
        self.calls.append(("head", key))
        if key not in self.objects:
            raise MissingObject()
        return SimpleNamespace(headers=self.objects[key])

    def put_object_from_file(self, key, filename, headers):
        self.calls.append(("apk", key, headers))
        if self.fail_apk:
            raise RuntimeError("simulated upload failure")
        if key in self.objects:
            raise AssertionError("immutable object was overwritten")
        self.objects[key] = {"x-oss-meta-sha256": hashlib.sha256(Path(filename).read_bytes()).hexdigest(),
                             "Content-Length": str(Path(filename).stat().st_size)}
        if self.mutate_apk:
            Path(filename).write_bytes(b"changed during upload")
        return SimpleNamespace(status=200)

    def put_object(self, key, payload, headers):
        self.calls.append(("manifest", key, headers))
        self.remote = json.loads(payload)
        return SimpleNamespace(status=200)


class ReleaseToolsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.build = self.root / "release"
        self.build.mkdir()
        self.apk = self.build / "app-release.apk"
        with zipfile.ZipFile(self.apk, "w") as archive:
            archive.writestr("AndroidManifest.xml", "fixture manifest")
            archive.writestr("classes.dex", b"fixture dex")
        self.version = self.root / "version.properties"
        self.version.write_text("versionCode=21\nversionName=0.2.17\n", encoding="utf-8")
        self.changelog = self.root / "changelog.md"
        self.changelog.write_text("更新检查与界面调整。\n", encoding="utf-8")
        self.metadata = {"artifactType": {"type": "APK"}, "applicationId": "com.naivety.zhicolor",
                         "variantName": "release", "elements": [{"type": "SINGLE", "filters": [],
                         "versionCode": 21, "versionName": "0.2.17", "outputFile": self.apk.name}]}
        self.write_metadata()
        self.output = self.root / "assets"

    def write_metadata(self):
        (self.build / "output-metadata.json").write_text(json.dumps(self.metadata), encoding="utf-8")

    def prepare(self):
        return prepare_release(self.apk, self.output, self.changelog, BASE, self.version)

    def write_manifest(self, manifest):
        payload = (json.dumps(manifest) + "\n").encode()
        (self.output / "update.json").write_bytes(payload)
        (self.output / "SHA256SUMS").write_text(
            f"{manifest['sha256']}  ZHIColor-0.2.17.apk\n{hashlib.sha256(payload).hexdigest()}  update.json\n",
            encoding="ascii")

    def other_release(self, manifest, code, name):
        result = copy.deepcopy(manifest)
        result.update(versionCode=code, versionName=name,
                      githubUrl=f"https://github.com/XZOWO/ZHIColor/releases/tag/v{name}",
                      cloudUrl=f"{BASE}/v{name}/ZHIColor-{name}.apk")
        return result

    def workflow_script(self, filename, step):
        path = Path(__file__).resolve().parent.parent / ".github" / "workflows" / filename
        lines = path.read_text(encoding="utf-8").splitlines()
        start = lines.index(f"      - name: {step}") + 1
        while lines[start] != "        run: |":
            self.assertFalse(lines[start].startswith("      - "), "step has no shell block")
            start += 1
        commands = []
        for line in lines[start + 1:]:
            if line.startswith("          "):
                commands.append(line[10:])
            elif not line.strip():
                commands.append("")
            else:
                break
        return "\n".join(commands) + "\n"

    def run_workflow_script(self, script, **variables):
        shell = shutil.which("bash")
        if shell is None and os.name == "nt":
            git = shutil.which("git")
            if git is not None:
                candidate = Path(git).parent.parent / "bin" / "bash.exe"
                if candidate.is_file():
                    shell = str(candidate)
        if shell is None:
            self.skipTest("Bash is required to execute the GitHub Actions shell branches")
        environment = {"PATH": os.defpath, "CLOUD_BASE_URL": "", "ZHICOLOR_UPDATE_MANIFEST_URL": ""}
        environment.update({key: str(value).replace("\\", "/") for key, value in variables.items()})
        return subprocess.run([shell, "--noprofile", "--norc", "-e", "-c", script],
                              env=environment, text=True, capture_output=True, check=False)

    def test_prepare_github_only_keeps_real_hash_and_verified_assets(self):
        manifest = prepare_release(self.apk, self.output, self.changelog, version_file=self.version)
        digest = hashlib.sha256(self.apk.read_bytes()).hexdigest()
        self.assertEqual(set(manifest), {"versionCode", "versionName", "changelog", "githubUrl", "sha256"})
        self.assertEqual(manifest["sha256"], digest)
        self.assertEqual(validate_manifest(manifest), (21, "0.2.17"))
        self.assertEqual((self.output / "ZHIColor-0.2.17.apk").read_bytes(), self.apk.read_bytes())
        payload = (self.output / "update.json").read_bytes()
        self.assertEqual(json.loads(payload), manifest)
        self.assertEqual((self.output / "SHA256SUMS").read_text(encoding="ascii").splitlines(),
                         [f"{digest}  ZHIColor-0.2.17.apk",
                          f"{hashlib.sha256(payload).hexdigest()}  update.json"])

    def test_prepare_empty_cloud_setting_is_github_only(self):
        manifest = prepare_release(self.apk, self.output, self.changelog, "", self.version)
        self.assertNotIn("cloudUrl", manifest)
        self.assertEqual(validate_manifest(manifest, ""), (21, "0.2.17"))

    def test_prepare_cli_accepts_omitted_cloud_option(self):
        script = self.root / "scripts" / "prepare_release.py"
        script.parent.mkdir()
        script.write_bytes(Path(__file__).with_name("prepare_release.py").read_bytes())
        result = subprocess.run([sys.executable, "-B", str(script), "--apk", str(self.apk),
                                 "--output", str(self.output), "--changelog-file", str(self.changelog)],
                                text=True, capture_output=True, check=False)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("PREPARED version=0.2.17 versionCode=21 tag=v0.2.17", result.stdout)
        self.assertNotIn("cloudUrl", json.loads((self.output / "update.json").read_bytes()))

    def test_github_only_preparation_still_rejects_build_mismatch(self):
        self.metadata["elements"][0]["versionCode"] = 20
        self.write_metadata()
        with self.assertRaises(ReleaseValidationError):
            prepare_release(self.apk, self.output, self.changelog, version_file=self.version)
        self.assertFalse(self.output.exists())

    def test_github_only_manifest_rejects_fake_cloud_fields_or_invalid_hash(self):
        manifest = prepare_release(self.apk, self.output, self.changelog, version_file=self.version)
        for field, value in (("cloudUrl", BASE + "/not-deployed.apk"), ("sha256", "not-a-digest"),
                             ("githubUrl", "https://github.com/other/project/releases/tag/v0.2.17")):
            with self.subTest(field=field), self.assertRaises(ReleaseValidationError):
                validate_manifest({**manifest, field: value})

    def test_cloud_sync_rejects_github_only_assets_before_remote_calls(self):
        prepare_release(self.apk, self.output, self.changelog, version_file=self.version)
        bucket = FakeBucket()
        with self.assertRaises(ReleaseValidationError):
            mirror_release(self.output, "v0.2.17", BASE, bucket)
        self.assertEqual(bucket.calls, [])

    def test_prepare_workflow_accepts_no_cloud_or_matching_cloud_configuration(self):
        script = self.workflow_script("prepare-release.yml", "Validate update configuration")
        for base, manifest, succeeds in (("", "", True), (BASE, BASE + "/update.json", True),
                                         (BASE, "", False), ("", BASE + "/update.json", False),
                                         (BASE, "https://other.example.com/update.json", False)):
            with self.subTest(base=base, manifest=manifest):
                result = self.run_workflow_script(script, CLOUD_BASE_URL=base,
                                                  ZHICOLOR_UPDATE_MANIFEST_URL=manifest)
                self.assertEqual(result.returncode == 0, succeeds, result.stderr)

    def test_prepare_workflow_omits_cloud_argument_until_configured(self):
        script = self.workflow_script("prepare-release.yml", "Prepare release attachments")
        capture = self.root / "arguments.txt"
        prefix = 'python() { printf "%s\n" "$@" > "$ARGUMENTS_FILE"; }\n'
        for base in ("", BASE):
            with self.subTest(base=base):
                result = self.run_workflow_script(prefix + script, CLOUD_BASE_URL=base, ARGUMENTS_FILE=capture)
                self.assertEqual(result.returncode, 0, result.stderr)
                arguments = capture.read_text(encoding="utf-8").splitlines()
                self.assertEqual("--cloud-base-url" in arguments, bool(base))
                if base:
                    self.assertEqual(arguments[-2:], ["--cloud-base-url", BASE])

    def test_mirror_workflow_without_cloud_succeeds_and_gates_all_remote_steps(self):
        script = self.workflow_script("mirror-release.yml", "Check cloud sync configuration")
        output = self.root / "github-output.txt"
        result = self.run_workflow_script(script, GITHUB_OUTPUT=output)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("SYNC_SKIPPED: cloud update is not configured", result.stdout)
        self.assertEqual(output.read_text(encoding="utf-8").strip(), "enabled=false")
        workflow = Path(__file__).resolve().parent.parent / ".github" / "workflows" / "mirror-release.yml"
        steps = workflow.read_text(encoding="utf-8").split("    steps:\n", 1)[1].split("      - ")[1:]
        self.assertGreater(len(steps), 1)
        for step in steps[1:]:
            self.assertIn("if: steps.cloud.outputs.enabled == 'true'", step)

    def test_mirror_workflow_with_cloud_enables_existing_sync_path(self):
        script = self.workflow_script("mirror-release.yml", "Check cloud sync configuration")
        output = self.root / "github-output.txt"
        result = self.run_workflow_script(script, CLOUD_BASE_URL=BASE, GITHUB_OUTPUT=output)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn("SYNC_SKIPPED", result.stdout)
        self.assertEqual(output.read_text(encoding="utf-8").strip(), "enabled=true")

    def test_prepare_produces_exact_contract_and_verified_assets(self):
        manifest = self.prepare()
        self.assertEqual(set(manifest), {"versionCode", "versionName", "changelog", "githubUrl", "cloudUrl", "sha256"})
        self.assertEqual(manifest["cloudUrl"], BASE + "/v0.2.17/ZHIColor-0.2.17.apk")
        self.assertEqual((self.output / "ZHIColor-0.2.17.apk").read_bytes(), self.apk.read_bytes())
        checked, _, _ = load_assets(self.output, "v0.2.17", BASE, "zhicolor")
        self.assertEqual(checked, manifest)

    def test_prepare_rejects_build_version_mismatch(self):
        self.metadata["elements"][0]["versionCode"] = 20
        self.write_metadata()
        with self.assertRaises(ReleaseValidationError):
            self.prepare()
        self.assertFalse(self.output.exists())

    def test_prepare_rejects_debug_foreign_split_or_wrong_file(self):
        for update in ({"variantName": "debug"}, {"applicationId": "other.app"}, {"artifactType": []},
                       {"elements": [{**self.metadata["elements"][0], "filters": [{"type": "ABI"}]}]},
                       {"elements": [{**self.metadata["elements"][0], "outputFile": "other.apk"}]}):
            with self.subTest(update=update):
                original = copy.deepcopy(self.metadata)
                self.metadata.update(update)
                self.write_metadata()
                with self.assertRaises(ReleaseValidationError):
                    self.prepare()
                self.metadata = original

    def test_prepare_rejects_non_apk_and_prerelease(self):
        self.apk.write_bytes(b"not an APK")
        with self.assertRaises(ReleaseValidationError):
            self.prepare()
        self.version.write_text("versionCode=21\nversionName=0.2.17-beta\n")
        with self.assertRaises(ReleaseValidationError):
            self.prepare()

    def test_unsafe_cloud_urls_are_rejected(self):
        for url in ("http://downloads.example.com/zhicolor", "https://github.com/zhicolor",
                    "https://raw.githubusercontent.com/zhicolor", "https://site.github.io/zhicolor",
                    "https://user:secret@downloads.example.com/zhicolor", "https://127.0.0.1/zhicolor",
                    "https://host.local/zhicolor", BASE + "?token=abc", BASE + "#fragment",
                    BASE + "/", "https://downloads.example.com/other", "https://downloads.example.com/a/../zhicolor",
                    "https://downloads.example.com/%7Ahicolor"):
            with self.subTest(url=url), self.assertRaises(ReleaseValidationError):
                validate_cloud_base(url)

    def test_mirror_checks_hash_tag_and_official_urls_before_remote_calls(self):
        manifest = self.prepare()
        bucket = FakeBucket()
        with self.assertRaises(ReleaseValidationError):
            mirror_release(self.output, "v0.2.16", BASE, bucket)
        (self.output / "ZHIColor-0.2.17.apk").write_bytes(b"changed APK")
        with self.assertRaises(ReleaseValidationError):
            mirror_release(self.output, "v0.2.17", BASE, bucket)
        self.assertEqual(bucket.calls, [])
        (self.output / "ZHIColor-0.2.17.apk").write_bytes(self.apk.read_bytes())
        for field, value in (("cloudUrl", "https://other.example.com/zhicolor/v0.2.17/ZHIColor-0.2.17.apk"),
                             ("githubUrl", "https://github.com/other/project/releases/tag/v0.2.17"),
                             ("versionCode", True), ("versionName", "0.2.17-rc1")):
            modified = {**manifest, field: value}
            self.write_manifest(modified)
            with self.subTest(field=field), self.assertRaises(ReleaseValidationError):
                mirror_release(self.output, "v0.2.17", BASE, bucket)
        self.assertEqual(bucket.calls, [])

    def test_manifest_checksum_tampering_is_rejected(self):
        self.prepare()
        (self.output / "update.json").write_bytes((self.output / "update.json").read_bytes() + b" ")
        with self.assertRaises(ReleaseValidationError):
            load_assets(self.output, "v0.2.17", BASE, "zhicolor")

    def test_success_uploads_apk_before_manifest_and_forbids_overwrite(self):
        self.prepare()
        bucket = FakeBucket()
        result = mirror_release(self.output, "v0.2.17", BASE, bucket)
        self.assertEqual([call[0] for call in bucket.calls], ["get", "head", "apk", "head", "get", "manifest"])
        self.assertEqual(bucket.calls[2][1], "zhicolor/v0.2.17/ZHIColor-0.2.17.apk")
        self.assertEqual(bucket.calls[2][2]["x-oss-forbid-overwrite"], "true")
        self.assertEqual(bucket.calls[-1][1], "zhicolor/update.json")
        self.assertEqual(result["apk_action"], "uploaded")

    def test_failed_apk_upload_never_updates_manifest(self):
        self.prepare()
        bucket = FakeBucket()
        bucket.fail_apk = True
        with self.assertRaises(ReleaseValidationError):
            mirror_release(self.output, "v0.2.17", BASE, bucket)
        self.assertNotIn("manifest", [call[0] for call in bucket.calls])
        self.assertIsNone(bucket.remote)

    def test_apk_mutation_during_upload_never_updates_manifest(self):
        self.prepare()
        bucket = FakeBucket()
        bucket.mutate_apk = True
        with self.assertRaises(ReleaseValidationError):
            mirror_release(self.output, "v0.2.17", BASE, bucket)
        self.assertNotIn("manifest", [call[0] for call in bucket.calls])

    def test_newer_remote_release_blocks_all_writes(self):
        manifest = self.prepare()
        bucket = FakeBucket(self.other_release(manifest, 22, "0.2.18"))
        with self.assertRaises(ReleaseValidationError):
            mirror_release(self.output, "v0.2.17", BASE, bucket)
        self.assertEqual([call[0] for call in bucket.calls], ["get"])

    def test_same_version_conflicting_digest_blocks_all_writes(self):
        manifest = self.prepare()
        bucket = FakeBucket({**manifest, "sha256": "a" * 64})
        with self.assertRaises(ReleaseValidationError):
            mirror_release(self.output, "v0.2.17", BASE, bucket)
        self.assertEqual([call[0] for call in bucket.calls], ["get"])

    def test_identical_existing_version_skips_apk_and_can_retry_manifest(self):
        manifest = self.prepare()
        bucket = FakeBucket(manifest)
        bucket.objects["zhicolor/v0.2.17/ZHIColor-0.2.17.apk"] = {
            "x-oss-meta-sha256": manifest["sha256"], "Content-Length": str(self.apk.stat().st_size)}
        result = mirror_release(self.output, "v0.2.17", BASE, bucket)
        self.assertEqual(result["apk_action"], "already-present")
        self.assertEqual([call[0] for call in bucket.calls], ["get", "head", "get", "manifest"])

    def test_remote_read_error_aborts_all_writes(self):
        self.prepare()
        bucket = FakeBucket()
        bucket.fail_read = True
        with self.assertRaises(ReleaseValidationError):
            mirror_release(self.output, "v0.2.17", BASE, bucket)
        self.assertEqual([call[0] for call in bucket.calls], ["get"])

    def test_existing_immutable_apk_conflict_aborts(self):
        self.prepare()
        bucket = FakeBucket()
        bucket.objects["zhicolor/v0.2.17/ZHIColor-0.2.17.apk"] = {
            "x-oss-meta-sha256": "a" * 64, "Content-Length": str(self.apk.stat().st_size)}
        with self.assertRaises(ReleaseValidationError):
            mirror_release(self.output, "v0.2.17", BASE, bucket)
        self.assertNotIn("apk", [call[0] for call in bucket.calls])
        self.assertNotIn("manifest", [call[0] for call in bucket.calls])

    def test_newer_release_arriving_during_upload_preserves_latest(self):
        manifest = self.prepare()
        bucket = FakeBucket()
        bucket.newer_on_second_read = self.other_release(manifest, 22, "0.2.18")
        with self.assertRaises(ReleaseValidationError):
            mirror_release(self.output, "v0.2.17", BASE, bucket)
        self.assertIn("apk", [call[0] for call in bucket.calls])
        self.assertNotIn("manifest", [call[0] for call in bucket.calls])
        self.assertEqual(bucket.remote["versionCode"], 22)

    def test_older_remote_release_can_advance(self):
        manifest = self.prepare()
        bucket = FakeBucket(self.other_release(manifest, 20, "0.2.16"))
        mirror_release(self.output, "v0.2.17", BASE, bucket)
        self.assertEqual(bucket.remote["versionCode"], 21)


class AndroidSdkWorkflowTests(unittest.TestCase):
    def test_gradle_wrapper_has_unix_line_endings(self):
        wrapper = (Path(__file__).resolve().parents[1] / "gradlew").read_bytes()
        self.assertTrue(wrapper.startswith(b"#!/usr/bin/env bash\n"))
        self.assertNotIn(b"\r", wrapper)

    def test_workflows_install_explicit_supported_sdk_packages(self):
        root = Path(__file__).resolve().parents[1]
        for name in ("check.yml", "prepare-release.yml"):
            with self.subTest(workflow=name):
                source = (root / ".github/workflows" / name).read_text(encoding="utf-8")
                action = source.split("      - uses: android-actions/setup-android@v3\n", 1)[1].split("      - ", 1)[0]
                self.assertIn("cmdline-tools-version: '15859902'", action)
                self.assertIn("packages: 'platform-tools platforms;android-37.0 build-tools;37.0.0'", action)


if __name__ == "__main__":
    unittest.main()
