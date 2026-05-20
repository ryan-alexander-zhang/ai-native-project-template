#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
from datetime import date
from pathlib import Path

from common import DECISION_ID_RE, find_project_root, render_front_matter, title_from_body
from validate_doc import validate_decision


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Archive a decision record and add a short archival note."
    )
    parser.add_argument("target", help="Decision id or path to the decision markdown file")
    parser.add_argument(
        "--reason",
        required=True,
        help="Short reason that explains why the decision is being archived",
    )
    parser.add_argument(
        "--json",
        action="store_true",
        help="Print JSON instead of a plain file path",
    )
    return parser.parse_args()


def resolve_target(target: str) -> Path:
    raw_path = Path(target)
    if raw_path.exists():
        return raw_path.resolve()

    if not DECISION_ID_RE.fullmatch(target):
        raise ValueError("target must be a decision id like decision-00001-example or a file path")

    project_root = find_project_root(Path.cwd())
    return (project_root / "docs" / "decisions" / f"{target}.md").resolve()


def upsert_archive_note(body: str, note: str) -> str:
    lines = body.splitlines()
    title_index = next((index for index, line in enumerate(lines) if line.startswith("# ")), None)
    if title_index is None:
        raise ValueError("decision record is missing a top-level title")

    note_index = title_index + 1
    while note_index < len(lines) and lines[note_index] == "":
        note_index += 1

    if note_index < len(lines) and lines[note_index].startswith("Archived on "):
        lines[note_index] = note
    else:
        lines[note_index:note_index] = ["", note, ""]

    return "\n".join(lines).rstrip() + "\n"


def main() -> int:
    args = parse_args()
    try:
        doc_path = resolve_target(args.target)
        front_matter, body = validate_decision(doc_path)
    except ValueError as error:
        raise SystemExit(str(error))

    front_matter["status"] = "archived"
    archival_note = f"Archived on {date.today().isoformat()}. Reason: {args.reason.strip()}"
    updated_body = upsert_archive_note(body, archival_note)
    doc_path.write_text(
        render_front_matter(front_matter) + "\n" + updated_body.lstrip("\n"),
        encoding="utf-8",
    )

    output = {
        "file_path": str(doc_path),
        "doc_id": front_matter["id"],
        "title": title_from_body(updated_body) or doc_path.stem,
        "status": front_matter["status"],
        "reason": args.reason.strip(),
    }

    if args.json:
        print(json.dumps(output))
    else:
        print(doc_path)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
