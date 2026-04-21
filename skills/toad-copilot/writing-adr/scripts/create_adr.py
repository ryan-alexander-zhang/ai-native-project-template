#!/usr/bin/env python3

from __future__ import annotations

import argparse
import fcntl
import json
from pathlib import Path

from common import ADR_ID_RE, DOC_RE, GENERIC_DOC_ID_RE, doc_path_for_id, find_project_root, slugify


def default_output_dir() -> Path:
    return find_project_root(Path.cwd()) / "docs" / "adrs"


def template_path() -> Path:
    return Path(__file__).resolve().parents[1] / "assets" / "adr_template.md.tmpl"


def render_template(doc_id: str, role: str, status: str, parent: str) -> str:
    return template_path().read_text(encoding="utf-8").format(
        doc_id=doc_id,
        role=role,
        status=status,
        parent=parent,
    )


def next_number(output_dir: Path) -> int:
    next_value = 1
    for path in output_dir.iterdir():
        match = DOC_RE.match(path.name)
        if match:
            next_value = max(next_value, int(match.group(1)) + 1)
    return next_value


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Create a new ADR file with the next available number."
    )
    parser.add_argument("slug_or_title", help="Slug or title for the ADR")
    parser.add_argument(
        "--role",
        choices=["main", "patch"],
        default="main",
        help="Role for the front matter",
    )
    parser.add_argument(
        "--parent",
        default="<id>",
        help="Parent id for the front matter",
    )
    parser.add_argument(
        "--status",
        choices=["draft", "active", "archived"],
        default="draft",
        help="Status for the front matter",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=default_output_dir(),
        help="Directory where ADRs are stored. Defaults to <current project>/docs/adrs",
    )
    parser.add_argument(
        "--json",
        action="store_true",
        help="Print JSON instead of a plain file path",
    )
    return parser.parse_args()


def validate_parent(project_root: Path, role: str, parent: str) -> None:
    if role == "patch":
        if not ADR_ID_RE.fullmatch(parent):
            raise SystemExit(
                "for role=patch, parent must be an ADR id like adr-00001-example-decision"
            )
        parent_path = project_root / "docs" / "adrs" / f"{parent}.md"
        if not parent_path.exists():
            raise SystemExit(f"parent ADR not found: {parent_path}")
        return

    if parent == "<id>":
        return

    if not GENERIC_DOC_ID_RE.fullmatch(parent):
        raise SystemExit(
            "for role=main, parent must be <id> or a doc id like "
            "idea-00001-project-bootstrap-copilot"
        )

    parent_path = doc_path_for_id(project_root, parent)
    if parent_path is None or not parent_path.exists():
        raise SystemExit(f"parent document not found: {parent}")


def main() -> int:
    args = parse_args()
    slug = slugify(args.slug_or_title)
    project_root = find_project_root(Path.cwd())
    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    validate_parent(project_root, args.role, args.parent)

    lock_path = output_dir / ".adr-number.lock"
    with lock_path.open("a+", encoding="utf-8") as lock_file:
        fcntl.flock(lock_file.fileno(), fcntl.LOCK_EX)
        number = next_number(output_dir)
        file_path = output_dir / f"adr-{number:05d}-{slug}.md"
        while file_path.exists():
            number += 1
            file_path = output_dir / f"adr-{number:05d}-{slug}.md"

        doc_id = file_path.stem
        file_path.write_text(
            render_template(
                doc_id=doc_id,
                role=args.role,
                status=args.status,
                parent=args.parent,
            ),
            encoding="utf-8",
        )

    if args.json:
        print(
            json.dumps(
                {
                    "file_path": str(file_path.resolve()),
                    "doc_id": doc_id,
                    "slug": slug,
                    "role": args.role,
                    "status": args.status,
                    "parent": args.parent,
                }
            )
        )
    else:
        print(file_path.resolve())

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
