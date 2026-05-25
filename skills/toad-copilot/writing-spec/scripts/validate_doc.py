#!/usr/bin/env python3

from __future__ import annotations

import argparse
import re
from pathlib import Path


PRD_ID_RE = re.compile(r"^prd-\d{5}-[a-z0-9-]+$")
SPEC_ID_RE = re.compile(r"^spec-\d{5}-[a-z0-9-]+$")
PROJECT_ROOT_MARKERS = (
    ".git",
    "AGENTS.md",
    "package.json",
    "pyproject.toml",
    "go.mod",
    "Cargo.toml",
)
VALID_ROLES = {"main", "patch"}
VALID_STATUSES = {"draft", "active", "archived"}
PLACEHOLDER_RE = re.compile(r"^<[^>\n]+>$", flags=re.MULTILINE)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Validate a generated spec document.")
    parser.add_argument("doc_path", type=Path, help="Path to the generated spec markdown file")
    return parser.parse_args()


def find_project_root(start: Path) -> Path:
    current = start.resolve()
    for candidate in (current, *current.parents):
        if any((candidate / marker).exists() for marker in PROJECT_ROOT_MARKERS):
            return candidate
    return current


def read_front_matter(doc_path: Path) -> dict[str, str]:
    content = doc_path.read_text(encoding="utf-8")
    match = re.match(r"^---\n(.*?)\n---(?:\n|$)", content, flags=re.DOTALL)
    if not match:
        raise SystemExit(f"front matter block not found in {doc_path}")

    front_matter: dict[str, str] = {}
    for raw_line in match.group(1).splitlines():
        line = raw_line.strip()
        if not line:
            continue
        if ":" not in line:
            raise SystemExit(f"invalid front matter line in {doc_path}: {raw_line}")
        key, value = raw_line.split(":", 1)
        front_matter[key.strip()] = value.strip()

    return front_matter


def read_body(doc_path: Path) -> str:
    content = doc_path.read_text(encoding="utf-8")
    match = re.match(r"^---\n.*?\n---\n?(?P<body>.*)$", content, flags=re.DOTALL)
    if not match:
        raise SystemExit(f"document body not found in {doc_path}")
    return match.group("body")


def require_fields(doc_path: Path, front_matter: dict[str, str], *fields: str) -> None:
    missing = [field for field in fields if not front_matter.get(field)]
    if missing:
        raise SystemExit(
            f"missing required front matter field(s) in {doc_path}: {', '.join(missing)}"
        )


def validate_existing_doc(project_root: Path, folder: str, doc_id: str) -> None:
    doc_path = project_root / "docs" / folder / f"{doc_id}.md"
    if not doc_path.exists():
        raise SystemExit(f"parent does not exist: {doc_path}")


def validate_title(doc_path: Path, body: str) -> None:
    match = re.match(r"^# .+\n?", body.lstrip())
    if not match:
        raise SystemExit(f"{doc_path} must start with a single title line")

def validate_nonempty_body(doc_path: Path, body: str) -> None:
    stripped = body.lstrip()
    title_match = re.match(r"^# .+\n?", stripped)
    if not title_match:
        raise SystemExit(f"{doc_path} must start with a single title line")

    remaining = stripped[title_match.end():].strip()
    if not remaining:
        raise SystemExit(f"{doc_path} must include body content after the title")


def validate_placeholders(doc_path: Path, body: str) -> None:
    placeholders = PLACEHOLDER_RE.findall(body)
    if placeholders:
        raise SystemExit(
            f"{doc_path} still has unresolved placeholder lines: {', '.join(placeholders)}"
        )


def main() -> int:
    args = parse_args()
    doc_path = args.doc_path.resolve()
    if not doc_path.exists():
        raise SystemExit(f"document not found: {doc_path}")

    project_root = find_project_root(doc_path.parent)
    body = read_body(doc_path)
    front_matter = read_front_matter(doc_path)
    require_fields(doc_path, front_matter, "id", "type", "role", "status", "parent")

    doc_id = front_matter["id"]
    if front_matter["type"] != "spec":
        raise SystemExit(f"{doc_path} has type {front_matter['type']!r}, expected 'spec'")
    if not SPEC_ID_RE.fullmatch(doc_id):
        raise SystemExit(f"{doc_path} has invalid id: {doc_id}")
    if doc_path.stem != doc_id:
        raise SystemExit(f"{doc_path} file name does not match front matter id {doc_id}")
    if front_matter["role"] not in VALID_ROLES:
        raise SystemExit(f"{doc_path} has invalid role: {front_matter['role']}")
    if front_matter["status"] not in VALID_STATUSES:
        raise SystemExit(f"{doc_path} has invalid status: {front_matter['status']}")

    parent = front_matter["parent"]
    if front_matter["role"] == "main":
        if not PRD_ID_RE.fullmatch(parent):
            raise SystemExit(f"{doc_path} has invalid parent for role=main: {parent}")
        validate_existing_doc(project_root, "prds", parent)
    else:
        if not SPEC_ID_RE.fullmatch(parent):
            raise SystemExit(f"{doc_path} has invalid parent for role=patch: {parent}")
        validate_existing_doc(project_root, "specs", parent)

    validate_title(doc_path, body)
    validate_nonempty_body(doc_path, body)
    validate_placeholders(doc_path, body)

    print(f"validated {doc_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
