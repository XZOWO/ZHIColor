#!/usr/bin/env python3
"""Offline GitHub-only release asset and CI contract regression tests."""
from __future__ import annotations
import copy
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile
from prepare_release import ReleaseValidationError, prepare_release, validate_manifest


class ReleaseToolsTest(unittest.TestCase):
    def prepare(self):
        return prepare_release(self.apk, self.output, self.changelog, version_file=self.version)

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
        environment = {"PATH": os.defpath}
        environment.update({key: str(value).replace("\\", "/") for key, value in variables.items()})
        return subprocess.run([shell, "--noprofile", "--norc", "-e", "-c", script],
                              env=environment, text=True, capture_output=True, check=False)


    def test_prepare_github_only_keeps_real_hash_and_verified_assets(self):
        manifest = prepare_release(self.apk, self.output, self.changelog, version_file=self.version)
        digest = hashlib.sha256(self.apk.read_bytes()).hexdigest()
        self.assertEqual(set(manifest), {"versionCode", "versionName", "changelog", "githubUrl", "downloadUrl", "sha256"})
        self.assertEqual(manifest["sha256"], digest)
        self.assertEqual(validate_manifest(manifest), (21, "0.2.17"))
        self.assertEqual((self.output / "ZHIColor-0.2.17.apk").read_bytes(), self.apk.read_bytes())
        payload = (self.output / "update.json").read_bytes()
        self.assertEqual(json.loads(payload), manifest)
        self.assertEqual((self.output / "SHA256SUMS").read_text(encoding="ascii").splitlines(),
                         [f"{digest}  ZHIColor-0.2.17.apk",
                          f"{hashlib.sha256(payload).hexdigest()}  update.json"])


    def test_prepare_cli_emits_github_download(self):
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


    def test_manifest_rejects_unsupported_fields_or_invalid_hash(self):
        manifest = prepare_release(self.apk, self.output, self.changelog, version_file=self.version)
        for field, value in (("cloudUrl", "https://downloads.example.com/not-deployed.apk"), ("sha256", "not-a-digest"),
                             ("githubUrl", "https://github.com/other/project/releases/tag/v0.2.17")):
            with self.subTest(field=field), self.assertRaises(ReleaseValidationError):
                validate_manifest({**manifest, field: value})


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


    def test_official_download_matches_version_and_apk_filename(self):
        manifest = self.prepare()
        self.assertEqual(manifest["downloadUrl"], "https://github.com/XZOWO/ZHIColor/releases/download/v0.2.17/ZHIColor-0.2.17.apk")
        for url in (manifest["downloadUrl"].replace("XZOWO", "other"), manifest["downloadUrl"].replace("v0.2.17", "v0.2.16"),
                    manifest["downloadUrl"].replace(".apk", ".zip"), manifest["downloadUrl"] + "?token=x",
                    manifest["downloadUrl"].replace("github.com", "github.com.evil.example"),
                    manifest["downloadUrl"].replace("https://", "http://"), "https://downloads.example.com/app.apk"):
            with self.subTest(url=url), self.assertRaises(ReleaseValidationError):
                validate_manifest({**manifest, "downloadUrl": url})

    def test_required_fields_cannot_be_missing(self):
        manifest = self.prepare()
        for field in manifest:
            value = dict(manifest)
            del value[field]
            with self.subTest(field=field), self.assertRaises(ReleaseValidationError):
                validate_manifest(value)

    def test_checksum_types_and_lengths_are_validated(self):
        manifest = self.prepare()
        for digest in (None, True, "", "a" * 63, "A" * 64, "a" * 65):
            with self.subTest(digest=digest), self.assertRaises(ReleaseValidationError):
                validate_manifest({**manifest, "sha256": digest})

    def test_changelog_limits_match_the_app_parser(self):
        for text in ("", "a" * 30001, "中" * 24000):
            self.changelog.write_text(text, encoding="utf-8")
            with self.subTest(length=len(text)), self.assertRaises(ReleaseValidationError):
                self.prepare()

    def test_corrupt_apk_crc_is_rejected(self):
        self.apk.write_bytes(self.apk.read_bytes().replace(b"fixture dex", b"fixture bad"))
        with self.assertRaises(ReleaseValidationError):
            self.prepare()

    def test_output_cannot_overwrite_the_build_directory(self):
        with self.assertRaises(ReleaseValidationError):
            prepare_release(self.apk, self.build, self.changelog, version_file=self.version)

    def test_cloud_cli_option_is_removed(self):
        result = subprocess.run([sys.executable, str(Path(__file__).with_name("prepare_release.py")),
                                 "--apk", str(self.apk), "--output", str(self.output),
                                 "--changelog-file", str(self.changelog), "--cloud-base-url", "https://unused.example/zhicolor"],
                                 text=True, capture_output=True, check=False)
        self.assertEqual(result.returncode, 2)
        self.assertIn("unrecognized arguments", result.stderr)

    def test_release_workflow_has_no_external_update_configuration(self):
        root = Path(__file__).resolve().parents[1]
        workflow = (root / ".github/workflows/prepare-release.yml").read_text(encoding="utf-8")
        for term in ("CLOUD_BASE_URL", "ZHICOLOR_UPDATE_MANIFEST_URL", "--cloud-base-url", "OSS_", "mirror_release"):
            self.assertNotIn(term, workflow)
        self.assertFalse((root / ".github/workflows/mirror-release.yml").exists())
        self.assertFalse((root / "scripts/mirror_release.py").exists())

    def test_release_workflow_uses_the_github_only_packager(self):
        script = self.workflow_script("prepare-release.yml", "Prepare release attachments")
        capture = self.root / "arguments.txt"
        prefix = 'python() { printf "%s\n" "$@" > "$ARGUMENTS_FILE"; }' + chr(10)
        result = self.run_workflow_script(prefix + script, ARGUMENTS_FILE=capture)
        self.assertEqual(result.returncode, 0, result.stderr)
        arguments = capture.read_text(encoding="utf-8").splitlines()
        self.assertIn("scripts/prepare_release.py", arguments)
        self.assertIn("--apk", arguments)
        self.assertIn("--output", arguments)
        self.assertNotIn("--cloud-base-url", arguments)

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
