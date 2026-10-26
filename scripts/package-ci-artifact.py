#!/usr/bin/env python3
"""Stage one tested executable JAR and provenance metadata for a later CD job."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import zipfile


def git(root, *args):
    return subprocess.check_output(["git", "-C", str(root), *args], text=True).strip()


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def package(root, environment):
    root = Path(root).resolve()
    build = root / "build"
    destination = build / "ci-artifact"
    if destination.is_symlink():
        raise ValueError("Artifact destination must not be a symlink")
    if destination.exists():
        shutil.rmtree(destination)

    commit = git(root, "rev-parse", "HEAD")
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("Expected a full Git commit SHA")
    if environment.get("GITHUB_SHA", commit) != commit:
        raise ValueError("Checked-out commit does not match the tested GitHub SHA")
    if git(root, "status", "--porcelain", "--untracked-files=no"):
        raise ValueError("Tracked source changes must be committed before packaging")

    candidates = sorted(path for path in (build / "libs").glob("*.jar")
                        if not path.name.endswith("-plain.jar"))
    if len(candidates) != 1 or candidates[0].is_symlink():
        raise ValueError("Expected exactly one non-symlink executable JAR, excluding plain.jar")
    jar = candidates[0]
    with zipfile.ZipFile(jar) as archive:
        manifest = archive.read("META-INF/MANIFEST.MF").decode("utf-8")
        manifest = manifest.replace("\r\n", "\n").replace("\n ", "")
        attributes = dict(line.split(": ", 1) for line in manifest.splitlines() if ": " in line)
        if (attributes.get("Main-Class") != "org.springframework.boot.loader.launch.JarLauncher"
                or not attributes.get("Start-Class")
                or not any(name.startswith("BOOT-INF/classes/") for name in archive.namelist())):
            raise ValueError("The artifact must be a Spring Boot executable JAR")

    event = environment.get("GITHUB_EVENT_NAME", "local")
    ref = environment.get("GITHUB_REF", "")
    repository = environment.get("GITHUB_REPOSITORY", "")
    run_id = environment.get("GITHUB_RUN_ID", "")
    attempt = environment.get("GITHUB_RUN_ATTEMPT", "")
    head = environment.get("CI_HEAD_SHA", commit)
    if not re.fullmatch(r"[0-9a-f]{40}", head):
        raise ValueError("Expected a full pull-request head SHA")
    if event != "local" and (
            not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository)
            or not run_id.isdecimal() or not attempt.isdecimal()):
        raise ValueError("GitHub run identity is missing")

    filename = f"mongle-{commit}.jar"
    with tempfile.TemporaryDirectory(prefix=".ci-artifact-", dir=build) as directory:
        staging = Path(directory)
        output = staging / filename
        shutil.copyfile(jar, output)
        checksum = sha256(output)
        (staging / "SHA256SUMS").write_text(f"{checksum}  {filename}\n")
        metadata = {
            "schemaVersion": 1,
            "repository": repository,
            "commitSha": commit,
            "headSha": head,
            "eventName": event,
            "ref": ref,
            "runId": run_id,
            "runAttempt": attempt,
            "jar": filename,
            "sha256": checksum,
            "deploymentEligible": event == "push" and ref == "refs/heads/develop"
                and repository == "mongle-web/mongle-server",
        }
        (staging / "build-metadata.json").write_text(json.dumps(metadata, indent=2) + "\n")
        staging.rename(destination)
    return metadata


if __name__ == "__main__":
    result = package(Path(__file__).resolve().parents[1], os.environ)
    print(f"Packaged {result['jar']} ({result['sha256']})")
