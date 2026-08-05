#!/usr/bin/env python3
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

SENSITIVE_SUFFIXES = {".jks", ".keystore", ".p12", ".pem", ".key"}
SENSITIVE_NAMES = {"local.properties", ".env", "google-services.json"}
REQUIRED_ARCHIVE_PATHS = {
    "AGENTS.md",
    "CHATGPT.md",
    ".github/workflows/quality.yml",
    ".github/workflows/repo-pack.yml",
    ".codex/config.toml",
    "gradle/libs.versions.toml",
}

def run(*args: str) -> str:
    return subprocess.check_output(args, text=True).strip()

def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()

def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--sha", required=True)
    args = parser.parse_args()

    tracked = run("git", "ls-files").splitlines()
    bad = []
    for item in tracked:
        path = Path(item)
        if path.name in SENSITIVE_NAMES or path.suffix.lower() in SENSITIVE_SUFFIXES:
            bad.append(item)
    if bad:
        raise SystemExit("Sensitive files are tracked and cannot enter repo pack: " + ", ".join(bad))

    out = args.output.resolve()
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)

    short = args.sha[:12]
    base = args.repository.split("/")[-1]
    source_zip = out / f"{base}-{short}-source.zip"
    bundle = out / f"{base}-{short}.bundle"

    subprocess.check_call([
        "git", "archive", "--format=zip", f"--prefix={base}/", "-o", str(source_zip), args.sha,
    ])
    subprocess.check_call(["git", "bundle", "create", str(bundle), "--all"])
    subprocess.check_call(["git", "bundle", "verify", str(bundle)])

    with zipfile.ZipFile(source_zip) as archive:
        names = set(archive.namelist())
        missing = [p for p in REQUIRED_ARCHIVE_PATHS if f"{base}/{p}" not in names]
        if missing:
            raise SystemExit("Source ZIP missing required paths: " + ", ".join(sorted(missing)))

    manifest = {
        "schemaVersion": 1,
        "repository": args.repository,
        "headSha": args.sha,
        "sourceZip": source_zip.name,
        "gitBundle": bundle.name,
        "gradleModules": [":app", ":mapping-core"],
        "notes": "Generated from tracked files after successful Quality workflow.",
    }
    manifest_path = out / "MANIFEST.json"
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

    checksum_paths = [source_zip, bundle, manifest_path]
    (out / "SHA256SUMS.txt").write_text(
        "".join(f"{sha256(path)}  {path.name}\n" for path in checksum_paths),
        encoding="utf-8",
    )
    (out / "README.txt").write_text(
        "Extract the source ZIP for a clean snapshot, or clone the Git bundle with:\n"
        f"  git clone {bundle.name} {base}\n",
        encoding="utf-8",
    )
    print(f"Created verified repo pack in {out}")

if __name__ == "__main__":
    main()
