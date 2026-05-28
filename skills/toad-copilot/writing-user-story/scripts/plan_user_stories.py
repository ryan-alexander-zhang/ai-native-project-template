#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path


PRD_ID_RE = re.compile(r"^prd-\d{5}-[a-z0-9-]+$")
FR_ID_RE = re.compile(r"^FR-\d{2,}$")
FR_LINE_RE = re.compile(r"^\s*-\s*\[[ xX]\]\s*(FR-\d{2,}):", flags=re.MULTILINE)
PROJECT_ROOT_MARKERS = (
    ".git",
    "AGENTS.md",
    "package.json",
    "pyproject.toml",
    "go.mod",
    "Cargo.toml",
)


def find_project_root(start: Path) -> Path:
    current = start.resolve()
    for candidate in (current, *current.parents):
        if any((candidate / marker).exists() for marker in PROJECT_ROOT_MARKERS):
            return candidate
    return current


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Plan a writing-user-story run without creating any files."
    )
    parser.add_argument(
        "--parent",
        required=True,
        help="Parent PRD id, for example prd-00020-checkout-redesign",
    )
    selection = parser.add_mutually_exclusive_group()
    selection.add_argument(
        "--all",
        action="store_true",
        dest="select_all",
        help="Select all FR ids from the parent PRD",
    )
    selection.add_argument(
        "--select",
        help="Comma-separated FR ids, for example FR-01,FR-03",
    )
    return parser.parse_args()


def read_front_matter(doc_path: Path) -> dict[str, str]:
    content = doc_path.read_text(encoding="utf-8")
    match = re.match(r"^---\n(.*?)\n---(?:\n|$)", content, flags=re.DOTALL)
    if not match:
        return {}

    front_matter: dict[str, str] = {}
    for raw_line in match.group(1).splitlines():
        line = raw_line.strip()
        if not line or ":" not in line:
            continue
        key, value = line.split(":", 1)
        front_matter[key.strip()] = value.strip()
    return front_matter


def extract_function_requirement_ids(prd_path: Path) -> list[str]:
    content = prd_path.read_text(encoding="utf-8")
    function_requirement_ids = FR_LINE_RE.findall(content)
    if not function_requirement_ids:
        raise SystemExit(f"no functional requirements found in {prd_path}")
    return function_requirement_ids


def parse_selection(raw_selection: str) -> list[str]:
    selected_ids = [item.strip() for item in raw_selection.split(",") if item.strip()]
    if not selected_ids:
        raise SystemExit("selection must include at least one FR id")
    for function_requirement_id in selected_ids:
        if not FR_ID_RE.fullmatch(function_requirement_id):
            raise SystemExit(
                f"invalid function_requirement_id in selection: {function_requirement_id}"
            )
    return selected_ids


def existing_story_matches(project_root: Path, parent: str) -> dict[str, list[str]]:
    user_story_dir = project_root / "docs" / "user-story"
    matches: dict[str, list[str]] = {}
    if not user_story_dir.exists():
        return matches

    for doc_path in sorted(user_story_dir.glob("*.md")):
        front_matter = read_front_matter(doc_path)
        if front_matter.get("parent") != parent:
            continue
        function_requirement_id = front_matter.get("function_requirement_id")
        if not function_requirement_id:
            continue
        matches.setdefault(function_requirement_id, []).append(str(doc_path.resolve()))
    return matches


def build_plan(
    project_root: Path,
    parent: str,
    selected_ids: list[str],
) -> list[dict[str, object]]:
    matches_by_requirement = existing_story_matches(project_root, parent)
    plan: list[dict[str, object]] = []
    for function_requirement_id in selected_ids:
        existing_paths = matches_by_requirement.get(function_requirement_id, [])
        role = "patch" if existing_paths else "main"
        plan.append(
            {
                "function_requirement_id": function_requirement_id,
                "role": role,
                "existing_story_paths": existing_paths,
            }
        )
    return plan


def main() -> int:
    args = parse_args()
    if not PRD_ID_RE.fullmatch(args.parent):
        raise SystemExit("parent must be a PRD id like prd-00020-checkout-redesign")

    project_root = find_project_root(Path.cwd())
    prd_path = project_root / "docs" / "prd" / f"{args.parent}.md"
    if not prd_path.exists():
        raise SystemExit(f"parent PRD not found: {prd_path}")

    available_ids = extract_function_requirement_ids(prd_path)
    if args.select_all:
        selected_ids = available_ids
        selection_mode = "all"
    elif args.select:
        selected_ids = parse_selection(args.select)
        invalid_ids = [item for item in selected_ids if item not in available_ids]
        if invalid_ids:
            raise SystemExit(
                f"unknown FR ids for {args.parent}: {', '.join(invalid_ids)}"
            )
        selection_mode = "subset"
    else:
        selected_ids = []
        selection_mode = "unselected"

    payload = {
        "parent": args.parent,
        "prd_path": str(prd_path.resolve()),
        "available_function_requirement_ids": available_ids,
        "selection_mode": selection_mode,
        "selected_function_requirement_ids": selected_ids,
        "plan": build_plan(project_root, args.parent, selected_ids),
    }
    print(json.dumps(payload, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
