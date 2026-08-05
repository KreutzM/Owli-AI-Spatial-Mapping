#!/usr/bin/env python3
import json
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
CORE = ROOT / "mapping-core" / "src"
FORBIDDEN = ("import android.", "import androidx.", "import com.google.ar.")
REQUIRED = [
    ROOT / "AGENTS.md",
    ROOT / "CHATGPT.md",
    ROOT / "docs" / "COORDINATE_SYSTEMS.md",
    ROOT / "docs" / "DATASET_POLICY.md",
    ROOT / ".github" / "workflows" / "quality.yml",
    ROOT / ".github" / "workflows" / "repo-pack.yml",
    ROOT / ".github" / "workflows" / "post-merge-handoff.yml",
    ROOT / "tools" / "post_merge_handoff.py",
]

errors = []


def load_yaml_mapping(path: Path):
    ruby_parser = r"""
require "json"
require "yaml"

workflow = YAML.safe_load(
  File.read(ARGV.fetch(0)),
  permitted_classes: [],
  permitted_symbols: [],
  aliases: false
)
abort "workflow YAML root must be a mapping" unless workflow.is_a?(Hash)
STDOUT.write(JSON.generate(workflow))
"""
    try:
        completed = subprocess.run(
            ["ruby", "-e", ruby_parser, str(path)],
            check=True,
            capture_output=True,
            text=True,
        )
        return json.loads(completed.stdout)
    except (FileNotFoundError, subprocess.CalledProcessError, json.JSONDecodeError) as error:
        errors.append(f"Unable to parse {path.relative_to(ROOT)} structurally: {error}")
        return None


def walk_yaml_scalars(value):
    if isinstance(value, dict):
        for key, nested in value.items():
            yield str(key)
            yield from walk_yaml_scalars(nested)
    elif isinstance(value, list):
        for nested in value:
            yield from walk_yaml_scalars(nested)
    elif isinstance(value, str):
        yield value


def check_post_merge_handoff_permissions():
    workflow_path = ROOT / ".github" / "workflows" / "post-merge-handoff.yml"
    workflow = load_yaml_mapping(workflow_path)
    if workflow is None:
        return

    expected_permissions = {
        "actions": "read",
        "contents": "read",
        "pull-requests": "write",
    }
    permissions = workflow.get("permissions")
    if permissions != expected_permissions:
        errors.append(
            "post-merge-handoff permissions must be exactly actions: read, "
            "contents: read, pull-requests: write"
        )

    try:
        publish_steps = workflow["jobs"]["publish"]["steps"]
        publication_step = next(
            step
            for step in publish_steps
            if step.get("name") == "Create or update post-merge handoff"
        )
        token_value = publication_step["env"]["GITHUB_TOKEN"]
    except (KeyError, StopIteration, TypeError):
        errors.append("post-merge-handoff publication step token binding is missing")
        token_value = None
    if token_value != "${{ github.token }}":
        errors.append("post-merge-handoff must use only github.token")

    parsed_scalars = list(walk_yaml_scalars(workflow))
    token_scalars = [value for value in parsed_scalars if "token" in value.lower()]
    if token_scalars != ["GITHUB_TOKEN", "${{ github.token }}"]:
        errors.append("post-merge-handoff must not define an alternate token binding")
    if any("secrets." in value.lower() for value in parsed_scalars):
        errors.append("post-merge-handoff must not use repository or GitHub App secrets")
    forbidden_credential_keys = {
        "GH_TOKEN",
        "PAT",
        "PERSONAL_ACCESS_TOKEN",
        "APP_ID",
        "PRIVATE_KEY",
    }
    normalized_scalars = {value.upper() for value in parsed_scalars}
    if not forbidden_credential_keys.isdisjoint(normalized_scalars):
        errors.append("post-merge-handoff must not define a PAT or app-token fallback")


for path in CORE.rglob("*.kt"):
    text = path.read_text(encoding="utf-8")
    for marker in FORBIDDEN:
        if marker in text:
            errors.append(f"{path.relative_to(ROOT)} imports forbidden dependency: {marker}")

for path in REQUIRED:
    if not path.exists():
        errors.append(f"Required repository support file missing: {path.relative_to(ROOT)}")

check_post_merge_handoff_permissions()

if errors:
    print("Architecture guardrails: FAIL")
    for error in errors:
        print(f"- {error}")
    sys.exit(1)

print("Architecture guardrails: PASS")
print("- mapping-core is independent from Android, AndroidX, and ARCore imports")
print("- required assistant, coordinate, dataset, and CI files are present")
print("- post-merge handoff permissions use exact least privilege and github.token only")
