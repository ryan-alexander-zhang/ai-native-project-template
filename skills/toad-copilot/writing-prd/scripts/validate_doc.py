#!/usr/bin/env python3

from __future__ import annotations

import argparse
import re
from pathlib import Path


IDEA_ID_RE = re.compile(r"^idea-\d{5}-[a-z0-9-]+$")
PRD_ID_RE = re.compile(r"^prd-\d{5}-[a-z0-9-]+$")
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


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Validate a generated PRD document."
    )
    parser.add_argument("doc_path", type=Path, help="Path to the generated PRD markdown file")
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
        key, value = line.split(":", 1)
        front_matter[key.strip()] = value.strip()

    return front_matter


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


def main() -> int:
    args = parse_args()
    doc_path = args.doc_path.resolve()
    if not doc_path.exists():
        raise SystemExit(f"document not found: {doc_path}")

    project_root = find_project_root(doc_path.parent)
    front_matter = read_front_matter(doc_path)
    require_fields(doc_path, front_matter, "id", "type", "role", "status", "parent")

    doc_id = front_matter["id"]
    if front_matter["type"] != "prd":
        raise SystemExit(f"{doc_path} has type {front_matter['type']!r}, expected 'prd'")
    if not PRD_ID_RE.fullmatch(doc_id):
        raise SystemExit(f"{doc_path} has invalid id: {doc_id}")
    if doc_path.stem != doc_id:
        raise SystemExit(
            f"{doc_path} file name does not match front matter id {doc_id}"
        )
    if front_matter["role"] not in VALID_ROLES:
        raise SystemExit(f"{doc_path} has invalid role: {front_matter['role']}")
    if front_matter["status"] not in VALID_STATUSES:
        raise SystemExit(f"{doc_path} has invalid status: {front_matter['status']}")

    parent = front_matter["parent"]
    if front_matter["role"] == "main":
        if not IDEA_ID_RE.fullmatch(parent):
            raise SystemExit(
                f"{doc_path} has invalid parent for role=main: {parent}"
            )
        validate_existing_doc(project_root, "ideas", parent)
    else:
        if not PRD_ID_RE.fullmatch(parent):
            raise SystemExit(
                f"{doc_path} has invalid parent for role=patch: {parent}"
            )
        validate_existing_doc(project_root, "prds", parent)

    print(f"validated {doc_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
