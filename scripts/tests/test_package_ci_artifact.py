import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile


spec = importlib.util.spec_from_file_location("package_ci", Path(__file__).resolve().parents[1] / "package-ci-artifact.py")
package_ci = importlib.util.module_from_spec(spec)
spec.loader.exec_module(package_ci)


class PackageCiArtifactTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="mongle-ci-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.git("init", "-q")
        self.git("config", "user.name", "CI fixture")
        self.git("config", "user.email", "fixture@example.invalid")
        self.git("config", "commit.gpgsign", "false")
        (self.root / "tracked.txt").write_text("source\n")
        self.git("add", "tracked.txt")
        self.git("commit", "-qm", "fixture")
        self.commit = self.git("rev-parse", "HEAD")
        self.libs = self.root / "build/libs"
        self.libs.mkdir(parents=True)
        self.jar = self.libs / "mongle.jar"
        self.boot_jar(self.jar)
        self.environment = dict(GITHUB_SHA=self.commit, CI_HEAD_SHA=self.commit,
                                GITHUB_EVENT_NAME="push", GITHUB_REF="refs/heads/develop",
                                GITHUB_REPOSITORY="mongle-web/mongle-server", GITHUB_RUN_ID="123", GITHUB_RUN_ATTEMPT="1")

    def git(self, *args):
        return subprocess.check_output(["git", "-C", str(self.root), *args], text=True, stderr=subprocess.DEVNULL).strip()

    def boot_jar(self, path):
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\nMain-Class: org.springframework.boot.loader.launch.JarLauncher\r\nStart-Class: example.Application\r\n\r\n")
            archive.writestr("BOOT-INF/classes/example/Application.class", b"fixture")

    def package(self):
        return package_ci.package(self.root, self.environment)

    def test_stages_only_executable_jar_and_matching_checksum_and_run_identity(self):
        (self.libs / "mongle-plain.jar").write_text("not the deployable JAR")
        (self.root / ".env").write_text("SECRET=must-not-be-uploaded")
        metadata = self.package()
        destination = self.root / "build/ci-artifact"
        filename = f"mongle-{self.commit}.jar"
        self.assertEqual({filename, "SHA256SUMS", "build-metadata.json"}, {p.name for p in destination.iterdir()})
        self.assertEqual(self.jar.read_bytes(), (destination / filename).read_bytes())
        checksum = hashlib.sha256(self.jar.read_bytes()).hexdigest()
        self.assertEqual(f"{checksum}  {filename}\n", (destination / "SHA256SUMS").read_text())
        self.assertEqual(checksum, metadata["sha256"])
        self.assertEqual(metadata, json.loads((destination / "build-metadata.json").read_text()))
        self.assertEqual("123", metadata["runId"])
        self.assertTrue(metadata["deploymentEligible"])

    def test_pull_request_records_tested_merge_and_head_separately_and_is_not_deployable(self):
        self.environment.update(GITHUB_EVENT_NAME="pull_request", GITHUB_REF="refs/pull/3/merge", CI_HEAD_SHA="a" * 40)
        result = self.package()
        self.assertEqual(self.commit, result["commitSha"])
        self.assertEqual("a" * 40, result["headSha"])
        self.assertFalse(result["deploymentEligible"])
        self.environment.update(GITHUB_EVENT_NAME="workflow_dispatch", GITHUB_REF="refs/heads/develop")
        self.assertFalse(self.package()["deploymentEligible"])

    def test_ambiguous_or_missing_jars_fail_and_remove_a_stale_bundle(self):
        self.package()
        self.boot_jar(self.libs / "second.jar")
        with self.assertRaisesRegex(ValueError, "exactly one"):
            self.package()
        self.assertFalse((self.root / "build/ci-artifact").exists())
        (self.libs / "second.jar").unlink()
        self.jar.unlink()
        with self.assertRaisesRegex(ValueError, "exactly one"):
            self.package()

    def test_mismatched_commit_dirty_source_and_missing_run_identity_are_rejected(self):
        self.environment["GITHUB_SHA"] = "b" * 40
        with self.assertRaisesRegex(ValueError, "does not match"):
            self.package()
        self.environment["GITHUB_SHA"] = self.commit
        (self.root / "tracked.txt").write_text("uncommitted change")
        with self.assertRaisesRegex(ValueError, "committed"):
            self.package()
        self.git("checkout", "--", "tracked.txt")
        self.environment.pop("GITHUB_RUN_ID")
        with self.assertRaisesRegex(ValueError, "identity"):
            self.package()

    def test_non_boot_archives_and_symlinked_jars_are_rejected(self):
        with zipfile.ZipFile(self.jar, "w") as archive:
            archive.writestr("META-INF/MANIFEST.MF", "Main-Class: example.PlainJar\n")
        with self.assertRaisesRegex(ValueError, "Spring Boot"):
            self.package()
        self.jar.unlink()
        real = self.root / "outside.jar"
        self.boot_jar(real)
        self.jar.symlink_to(real)
        with self.assertRaisesRegex(ValueError, "non-symlink"):
            self.package()

    def test_symlinked_output_is_not_followed_or_deleted(self):
        protected = self.root / "protected"
        protected.mkdir()
        marker = protected / "keep"
        marker.write_text("keep")
        (self.root / "build/ci-artifact").symlink_to(protected)
        with self.assertRaisesRegex(ValueError, "symlink"):
            self.package()
        self.assertEqual("keep", marker.read_text())


if __name__ == "__main__":
    unittest.main()
