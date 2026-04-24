#!/usr/bin/env python3

from __future__ import annotations

import re
from pathlib import Path


ADR_ID_RE = re.compile(r"^adr-\d{5}-[a-z0-9-]+$")
PRD_ID_RE = re.compile(r"^prd-\d{5}-[a-z0-9-]+$")
GENERIC_DOC_ID_RE = re.compile(
    r"^(adr|idea|integration|memory|operation|plan|prd|record|spec|task|us)-\d{5}-[a-z0-9-]+$"
)
DOC_RE = re.compile(r"^adr-(\d{5})-[a-z0-9-]+\.md$")
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
VALID_ROLES = {"main", "patch"}
VALID_STATUSES = {"draft", "active", "archived"}
DOC_DIR_BY_PREFIX = {
    "adr": "adrs",
    "idea": "ideas",
    "integration": "integrations",
    "memory": "memory",
    "operation": "operations",
    "plan": "plans",
    "prd": "prds",
    "record": "records",
    "spec": "specs",
    "task": "tasks",
    "us": "user-stories",
}


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


def split_front_matter(content: str) -> tuple[dict[str, str], str]:
    match = re.match(r"^---\n(.*?)\n---(?:\n|$)(.*)$", content, flags=re.DOTALL)
    if not match:
        raise ValueError("front matter block not found")

    front_matter: dict[str, str] = {}
    for raw_line in match.group(1).splitlines():
        line = raw_line.strip()
        if not line:
            continue
        if ":" not in line:
            raise ValueError(f"invalid front matter line: {raw_line}")
        key, value = line.split(":", 1)
        front_matter[key.strip()] = value.strip()

    return front_matter, match.group(2)


def read_front_matter_and_body(doc_path: Path) -> tuple[dict[str, str], str]:
    return split_front_matter(doc_path.read_text(encoding="utf-8"))


def render_front_matter(front_matter: dict[str, str]) -> str:
    preferred_order = ["id", "type", "role", "status", "parent"]
    lines: list[str] = []
    for key in preferred_order:
        if key in front_matter:
            lines.append(f"{key}: {front_matter[key]}")

    for key in sorted(front_matter):
        if key not in preferred_order:
            lines.append(f"{key}: {front_matter[key]}")

    return "---\n" + "\n".join(lines) + "\n---\n"


def replace_front_matter(content: str, front_matter: dict[str, str]) -> str:
    match = re.match(r"^---\n(.*?)\n---(?:\n|$)(.*)$", content, flags=re.DOTALL)
    if not match:
        raise ValueError("front matter block not found")
    return render_front_matter(front_matter) + "\n" + match.group(2).lstrip("\n")


def doc_path_for_id(project_root: Path, doc_id: str) -> Path | None:
    if doc_id == "<id>":
        return None
    prefix = doc_id.split("-", 1)[0]
    folder = DOC_DIR_BY_PREFIX.get(prefix)
    if not folder:
        return None
    return project_root / "docs" / folder / f"{doc_id}.md"


def title_from_body(body: str) -> str | None:
    for line in body.splitlines():
        if line.startswith("# "):
            return line[2:].strip()
    return None


def extract_sections(body: str) -> dict[str, str]:
    sections: dict[str, list[str]] = {}
    current: str | None = None
    for line in body.splitlines():
        if line.startswith("## "):
            current = line[3:].strip()
            sections[current] = []
            continue
        if current is not None:
            sections[current].append(line)
    return {name: "\n".join(lines).strip() for name, lines in sections.items()}
