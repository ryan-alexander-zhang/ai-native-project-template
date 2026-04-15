#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path


FRONT_MATTER_RE = re.compile(r"\A---\n(.*?)\n---\n", re.DOTALL)
DOC_ID_RE = re.compile(r"^idea-\d{5}-[a-z0-9-]+$")
SECTION_HEADINGS = [
    "# Idea Brief",
    "## One-line Summary",
    "## Problem",
    "## Target User",
    "## Current Alternatives",
    "## Proposed Solution",
    "## Why Better",
    "## Why Now",
    "## Risks",
    "## Decision",
]
PLACEHOLDER_PATTERNS = [
    r"(?m)^What is this product in one sentence\?\s*$",
    r"(?m)^What is the core problem users are facing today\?\s*$",
    r"(?m)^Who is most likely to experience this problem\?\s*$",
    r"(?m)^How are they solving it today\?\s*$",
    r"(?m)^How does my solution address the problem\?\s*$",
    r"(?m)^What are the advantages over existing alternatives\?\s*$",
    r"(?m)^Why is this worth building now\?\s*$",
    r"(?m)^- Risk 1:\s*$",
    r"(?m)^- Risk 2:\s*$",
    r"(?m)^- Risk 3:\s*$",
]
DECISION_LINES = [
    "- [ ] Continue",
    "- [ ] Pause",
    "- [ ] Drop",
]
REQUIRED_FRONT_MATTER = ["id", "type", "role", "status", "parent"]
ALLOWED_STATUS = {"draft", "active", "archived"}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Validate an idea brief markdown file."
    )
    parser.add_argument("path", type=Path, help="Path to the idea brief markdown file")
    return parser.parse_args()


def parse_front_matter(text: str) -> tuple[dict[str, str], int]:
    match = FRONT_MATTER_RE.match(text)
    if not match:
        return {}, 0

    fields: dict[str, str] = {}
    for line in match.group(1).splitlines():
        key, separator, value = line.partition(":")
        if not separator:
            continue
        fields[key.strip()] = value.strip()
    return fields, match.end()


def heading_positions(body: str) -> dict[str, int]:
    positions: dict[str, int] = {}
    for heading in SECTION_HEADINGS:
        match = re.search(rf"(?m)^{re.escape(heading)}$", body)
        positions[heading] = match.start() if match else -1
    return positions


def section_body(body: str, heading: str, positions: dict[str, int]) -> str:
    start = positions[heading]
    if start < 0:
        return ""

    start = body.find("\n", start)
    if start < 0:
        return ""
    start += 1

    later_positions = [
        position
        for other_heading, position in positions.items()
        if other_heading != heading and position > positions[heading]
    ]
    end = min(later_positions) if later_positions else len(body)
    return body[start:end].strip()


def validate(path: Path) -> dict[str, object]:
    errors: list[str] = []

    if not path.exists():
        errors.append(f"File does not exist: {path}")
        return {"ok": False, "errors": errors, "warnings": [], "file_path": str(path)}

    text = path.read_text(encoding="utf-8")
    fields, body_start = parse_front_matter(text)
    if not fields:
        errors.append("Document must start with YAML front matter delimited by ---")
        return {"ok": False, "errors": errors, "warnings": [], "file_path": str(path)}

    for key in REQUIRED_FRONT_MATTER:
        if not fields.get(key):
            errors.append(f"Front matter is missing `{key}`")

    doc_id = fields.get("id", "")
    if doc_id and not DOC_ID_RE.fullmatch(doc_id):
        errors.append("`id` must match `idea-<five digits>-<slug>`")
    if doc_id and path.stem != doc_id:
        errors.append("File name must match the front matter `id`")
    if fields.get("type") and fields["type"] != "idea":
        errors.append("`type` must be `idea`")
    if fields.get("role") and fields["role"] != "main":
        errors.append("`role` must be `main`")
    if fields.get("status") and fields["status"] not in ALLOWED_STATUS:
        errors.append("`status` must be one of: draft, active, archived")

    body = text[body_start:].lstrip()
    positions = heading_positions(body)

    last_position = -1
    for heading in SECTION_HEADINGS:
        position = positions[heading]
        if position < 0:
            errors.append(f"Missing required heading: `{heading}`")
            continue
        if position <= last_position:
            errors.append(f"Heading is out of order: `{heading}`")
        last_position = position

    for heading in SECTION_HEADINGS[1:-2]:
        content = section_body(body, heading, positions)
        if not content:
            errors.append(f"Section `{heading}` must not be empty")

    risks = [line.strip() for line in section_body(body, "## Risks", positions).splitlines() if line.strip()]
    if len(risks) != 3:
        errors.append("`## Risks` must contain exactly three bullet lines")
    else:
        for index, line in enumerate(risks, start=1):
            prefix = f"- Risk {index}:"
            if not line.startswith(prefix):
                errors.append(f"Risk line {index} must start with `{prefix}`")
                continue
            if not line[len(prefix):].strip():
                errors.append(f"Risk line {index} must include real content after `{prefix}`")

    decisions = [line.strip() for line in section_body(body, "## Decision", positions).splitlines() if line.strip()]
    if decisions != DECISION_LINES:
        errors.append("`## Decision` must contain the exact three unchecked decision lines")

    for pattern in PLACEHOLDER_PATTERNS:
        match = re.search(pattern, text)
        if match:
            errors.append(f"Replace scaffold text before saving: `{match.group(0).strip()}`")

    return {
        "ok": not errors,
        "errors": errors,
        "warnings": [],
        "file_path": str(path.resolve()),
        "id": fields.get("id", ""),
    }


def main() -> int:
    args = parse_args()
    result = validate(args.path.resolve())
    print(json.dumps(result, indent=2))
    return 0 if result["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
