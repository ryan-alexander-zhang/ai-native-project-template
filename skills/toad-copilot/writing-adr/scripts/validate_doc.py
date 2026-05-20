#!/usr/bin/env python3

from __future__ import annotations

import argparse
from pathlib import Path

from common import (
    DECISION_ID_RE,
    VALID_ROLES,
    VALID_STATUSES,
    doc_path_for_id,
    extract_sections,
    find_project_root,
    is_valid_main_decision_parent,
    read_front_matter_and_body,
    title_from_body,
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Validate a generated decision record."
    )
    parser.add_argument("doc_path", type=Path, help="Path to the generated decision markdown file")
    return parser.parse_args()


def require_fields(doc_path: Path, front_matter: dict[str, str], *fields: str) -> None:
    missing = [field for field in fields if not front_matter.get(field)]
    if missing:
        raise ValueError(
            f"missing required front matter field(s) in {doc_path}: {', '.join(missing)}"
        )


def validate_decision(doc_path: Path) -> tuple[dict[str, str], str]:
    resolved_path = doc_path.resolve()
    if not resolved_path.exists():
        raise ValueError(f"document not found: {resolved_path}")

    project_root = find_project_root(resolved_path.parent)
    front_matter, body = read_front_matter_and_body(resolved_path)
    require_fields(resolved_path, front_matter, "id", "type", "role", "status", "parent")

    doc_id = front_matter["id"]
    if front_matter["type"] != "decision":
        raise ValueError(f"{resolved_path} has type {front_matter['type']!r}, expected 'decision'")
    if not DECISION_ID_RE.fullmatch(doc_id):
        raise ValueError(f"{resolved_path} has invalid id: {doc_id}")
    if resolved_path.stem != doc_id:
        raise ValueError(
            f"{resolved_path} file name does not match front matter id {doc_id}"
        )
    if front_matter["role"] not in VALID_ROLES:
        raise ValueError(f"{resolved_path} has invalid role: {front_matter['role']}")
    if front_matter["status"] not in VALID_STATUSES:
        raise ValueError(f"{resolved_path} has invalid status: {front_matter['status']}")

    parent = front_matter["parent"]
    if front_matter["role"] == "patch":
        if not DECISION_ID_RE.fullmatch(parent):
            raise ValueError(
                f"{resolved_path} has invalid parent for role=patch: {parent}"
            )
        parent_path = project_root / "docs" / "decisions" / f"{parent}.md"
        if not parent_path.exists():
            raise ValueError(f"parent does not exist: {parent_path}")
    else:
        if not is_valid_main_decision_parent(parent):
            raise ValueError(
                f"{resolved_path} has invalid parent for role=main; expected an idea, PRD, or spec id, got: {parent}"
            )
        parent_path = doc_path_for_id(project_root, parent)
        if parent_path is None or not parent_path.exists():
            raise ValueError(f"parent does not exist: {parent}")

    title = title_from_body(body)
    if not title:
        raise ValueError(f"{resolved_path} is missing a top-level title")

    sections = extract_sections(body)
    for section_name in ("Context", "Decision", "Consequences"):
        if section_name not in sections:
            raise ValueError(f"{resolved_path} is missing section: {section_name}")

    return front_matter, body


def main() -> int:
    args = parse_args()
    try:
        validate_decision(args.doc_path)
    except ValueError as error:
        raise SystemExit(str(error))

    print(f"validated {args.doc_path.resolve()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
