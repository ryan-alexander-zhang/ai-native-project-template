#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
from pathlib import Path

from common import DOC_RE, find_project_root, read_front_matter_and_body, title_from_body


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="List decision records in docs/decision.")
    parser.add_argument(
        "--status",
        choices=["draft", "active", "archived"],
        help="Filter decision records by front matter status",
    )
    parser.add_argument(
        "--json",
        action="store_true",
        help="Print JSON instead of plain text output",
    )
    return parser.parse_args()


def list_adrs(project_root: Path, status: str | None) -> list[dict[str, str]]:
    adrs_dir = project_root / "docs" / "decision"
    if not adrs_dir.exists():
        return []

    rows: list[dict[str, str]] = []
    for path in sorted(adrs_dir.iterdir()):
        if not DOC_RE.fullmatch(path.name):
            continue
        front_matter, body = read_front_matter_and_body(path)
        if status and front_matter.get("status") != status:
            continue
        rows.append(
            {
                "id": front_matter.get("id", path.stem),
                "role": front_matter.get("role", ""),
                "status": front_matter.get("status", ""),
                "parent": front_matter.get("parent", ""),
                "title": title_from_body(body) or "",
                "path": str(path.resolve()),
            }
        )

    return rows


def main() -> int:
    args = parse_args()
    rows = list_adrs(find_project_root(Path.cwd()), args.status)
    if args.json:
        print(json.dumps(rows))
    else:
        for row in rows:
            print(
                f"{row['id']}\t{row['status']}\t{row['role']}\t{row['title'] or '(untitled)'}"
            )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
