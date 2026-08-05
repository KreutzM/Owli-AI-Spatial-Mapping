#!/usr/bin/env python3
from pathlib import Path
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
]

errors = []
for path in CORE.rglob("*.kt"):
    text = path.read_text(encoding="utf-8")
    for marker in FORBIDDEN:
        if marker in text:
            errors.append(f"{path.relative_to(ROOT)} imports forbidden dependency: {marker}")

for path in REQUIRED:
    if not path.exists():
        errors.append(f"Required repository support file missing: {path.relative_to(ROOT)}")

if errors:
    print("Architecture guardrails: FAIL")
    for error in errors:
        print(f"- {error}")
    sys.exit(1)

print("Architecture guardrails: PASS")
print("- mapping-core is independent from Android, AndroidX, and ARCore imports")
print("- required assistant, coordinate, dataset, and CI files are present")
