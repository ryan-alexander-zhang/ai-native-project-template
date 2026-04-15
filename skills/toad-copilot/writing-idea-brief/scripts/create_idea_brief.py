#!/usr/bin/env python3

from __future__ import annotations

import argparse
import fcntl
import json
import re
from pathlib import Path


DOC_NAME_RE = re.compile(r"^idea-(\d{5})-[a-z0-9-]+\.md$")
NON_SLUG_RE = re.compile(r"[^a-z0-9]+")
DASH_RE = re.compile(r"-+")
TEMPLATE = """# Idea Brief

## One-line Summary
What is this product in one sentence?

## Problem
What is the core problem users are facing today?

## Target User
Who is most likely to experience this problem?

## Current Alternatives
How are they solving it today?

## Proposed Solution
How does my solution address the problem?

## Why Better
What are the advantages over existing alternatives?

## Why Now
Why is this worth building now?

## Risks
- Risk 1:
- Risk 2:
- Risk 3:

## Decision
- [ ] Continue
- [ ] Pause
- [ ] Drop
"""


def slugify(value: str) -> str:
    slug = NON_SLUG_RE.sub("-", value.lower()).strip("-")
    slug = DASH_RE.sub("-", slug)
    if not slug:
        raise ValueError("slug must contain at least one letter or number")
    return slug


def next_number(ideas_dir: Path) -> int:
    next_value = 1
    for path in ideas_dir.iterdir():
        match = DOC_NAME_RE.match(path.name)
        if match:
            next_value = max(next_value, int(match.group(1)) + 1)
    return next_value


def build_document(doc_id: str, parent: str, status: str) -> str:
    return f"""---
id: {doc_id}
type: idea
role: main
status: {status}
parent: {parent}
---

{TEMPLATE}"""


def default_output_dir() -> Path:
    return Path(__file__).resolve().parents[4] / "docs" / "ideas"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Create an idea brief file with the next available idea id."
    )
    parser.add_argument("slug", help="Idea slug or title fragment")
    parser.add_argument("--parent", default="<id>", help="Parent id to place in front matter")
    parser.add_argument(
        "--status",
        choices=["draft", "active", "archived"],
        default="draft",
        help="Initial document status",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=default_output_dir(),
        help="Directory where idea briefs are stored",
    )
    parser.add_argument(
        "--json",
        action="store_true",
        help="Print structured JSON output instead of the file path",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    slug = slugify(args.slug)
    ideas_dir = args.output_dir.resolve()
    ideas_dir.mkdir(parents=True, exist_ok=True)

    lock_path = ideas_dir / ".idea-number.lock"
    with lock_path.open("a+", encoding="utf-8") as lock_file:
        fcntl.flock(lock_file.fileno(), fcntl.LOCK_EX)
        number = next_number(ideas_dir)
        while True:
            doc_id = f"idea-{number:05d}-{slug}"
            file_path = ideas_dir / f"{doc_id}.md"
            if not file_path.exists():
                break
            number += 1

        file_path.write_text(build_document(doc_id=doc_id, parent=args.parent, status=args.status), encoding="utf-8")

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
        print(file_path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
