#!/usr/bin/env python3

from __future__ import annotations

import argparse
import fcntl
import json
import re
from pathlib import Path


DOC_RE = re.compile(r"^spec-(\d{5})-[a-z0-9-]+\.md$")
PRD_ID_RE = re.compile(r"^prd-\d{5}-[a-z0-9-]+$")
SPEC_ID_RE = re.compile(r"^spec-\d{5}-[a-z0-9-]+$")
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


def display_title(value: str, slug: str) -> str:
    cleaned = " ".join(value.strip().split())
    if not cleaned:
        raise ValueError("title must not be empty")

    if cleaned.lower() == slug:
        return " ".join(part.capitalize() for part in slug.split("-"))

    return cleaned


def find_project_root(start: Path) -> Path:
    current = start.resolve()
    for candidate in (current, *current.parents):
        if any((candidate / marker).exists() for marker in PROJECT_ROOT_MARKERS):
            return candidate
    return current


def default_output_dir() -> Path:
    return find_project_root(Path.cwd()) / "docs" / "specs"


def render_document(
    *,
    doc_id: str,
    role: str,
    status: str,
    parent: str,
    title: str,
) -> str:
    return (
        "---\n"
        f"id: {doc_id}\n"
        "type: spec\n"
        f"role: {role}\n"
        f"status: {status}\n"
        f"parent: {parent}\n"
        "---\n\n"
        f"# {title}\n"
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
        description="Create a new spec file with the next available number."
    )
    parser.add_argument("slug_or_title", help="Slug or title for the spec")
    parser.add_argument(
        "--role",
        choices=["main", "patch"],
        default="main",
        help="Role for the front matter",
    )
    parser.add_argument(
        "--parent",
        required=True,
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
        help="Directory where specs are stored. Defaults to <current project>/docs/specs",
    )
    parser.add_argument(
        "--json",
        action="store_true",
        help="Print JSON instead of a plain file path",
    )
    return parser.parse_args()


def validate_parent(role: str, parent: str) -> None:
    if role == "main":
        if not PRD_ID_RE.fullmatch(parent):
            raise SystemExit(
                "for role=main, parent must be a PRD id like "
                "prd-00001-project-bootstrap-copilot"
            )
        return

    if not SPEC_ID_RE.fullmatch(parent):
        raise SystemExit(
            "for role=patch, parent must be a spec id like spec-00001-example-spec"
        )


def main() -> int:
    args = parse_args()
    slug = slugify(args.slug_or_title)
    title = display_title(args.slug_or_title, slug)
    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    validate_parent(args.role, args.parent)

    lock_path = output_dir / ".spec-number.lock"
    with lock_path.open("a+", encoding="utf-8") as lock_file:
        fcntl.flock(lock_file.fileno(), fcntl.LOCK_EX)
        number = next_number(output_dir)
        file_path = output_dir / f"spec-{number:05d}-{slug}.md"
        while file_path.exists():
            number += 1
            file_path = output_dir / f"spec-{number:05d}-{slug}.md"

        doc_id = file_path.stem
        file_path.write_text(
            render_document(
                doc_id=doc_id,
                role=args.role,
                status=args.status,
                parent=args.parent,
                title=title,
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
                    "title": title,
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
