#!/usr/bin/env python3

from __future__ import annotations

import argparse
import fcntl
import json
import re
from pathlib import Path


DOC_RE = re.compile(r"^idea-(\d{5})-[a-z0-9-]+\.md$")
NON_SLUG_RE = re.compile(r"[^a-z0-9]+")
DASH_RE = re.compile(r"-+")
PROJECT_ROOT_MARKERS = (
    ".git",
    "AGENTS.md",
    "package.json",
    "pyproject.toml",
    "go.mod",
    "Cargo.toml",
)


def slugify(value: str) -> str:
    slug = NON_SLUG_RE.sub("-", value.lower()).strip("-")
    slug = DASH_RE.sub("-", slug)
    if not slug:
        raise ValueError("slug must contain at least one letter or number")
    return slug


def find_project_root(start: Path) -> Path:
    current = start.resolve()
    for candidate in (current, *current.parents):
        if any((candidate / marker).exists() for marker in PROJECT_ROOT_MARKERS):
            return candidate
    return current


def default_output_dir() -> Path:
    return find_project_root(Path.cwd()) / "docs" / "ideas"


def template_path() -> Path:
    return Path(__file__).resolve().parents[1] / "assets" / "idea_brief_template.md.tmpl"


def render_template(doc_id: str, status: str, parent: str) -> str:
    return template_path().read_text(encoding="utf-8").format(
        doc_id=doc_id,
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
        description="Create a new idea brief file with the next available number."
    )
    parser.add_argument("slug_or_title", help="Slug or title for the idea brief")
    parser.add_argument("--parent", default="<id>", help="Parent id for the front matter")
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
        help="Directory where idea briefs are stored. Defaults to <current project>/docs/ideas",
    )
    parser.add_argument(
        "--json",
        action="store_true",
        help="Print JSON instead of a plain file path",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    slug = slugify(args.slug_or_title)
    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)

    lock_path = output_dir / ".idea-number.lock"
    with lock_path.open("a+", encoding="utf-8") as lock_file:
        fcntl.flock(lock_file.fileno(), fcntl.LOCK_EX)
        number = next_number(output_dir)
        file_path = output_dir / f"idea-{number:05d}-{slug}.md"
        while file_path.exists():
            number += 1
            file_path = output_dir / f"idea-{number:05d}-{slug}.md"

        doc_id = file_path.stem
        file_path.write_text(
            render_template(doc_id=doc_id, status=args.status, parent=args.parent),
            encoding="utf-8",
        )

    if args.json:
        print(
            json.dumps(
                {
                    "file_path": str(file_path.resolve()),
                    "doc_id": doc_id,
                    "slug": slug,
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
