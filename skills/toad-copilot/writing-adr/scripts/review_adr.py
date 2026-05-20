#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
from pathlib import Path

from common import extract_sections, title_from_body
from validate_doc import validate_decision


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Review a decision record for common quality issues."
    )
    parser.add_argument("doc_path", type=Path, help="Path to the decision markdown file")
    parser.add_argument(
        "--json",
        action="store_true",
        help="Print JSON instead of plain text output",
    )
    return parser.parse_args()


def word_count(value: str) -> int:
    return len([word for word in value.replace("\n", " ").split(" ") if word.strip()])


def has_placeholder(value: str) -> bool:
    return "<" in value and ">" in value


def contains_any(value: str, needles: list[str]) -> bool:
    lowered = value.lower()
    return any(needle in lowered for needle in needles)


def review_adr(doc_path: Path) -> dict[str, list[str]]:
    front_matter, body = validate_decision(doc_path)
    findings = {"errors": [], "warnings": [], "passes": []}
    title = title_from_body(body) or ""
    sections = extract_sections(body)

    if title.lower() == "title":
        findings["errors"].append("Replace the placeholder title with the decision title.")
    else:
        findings["passes"].append("Top-level decision title is present.")

    for section_name in ("Context", "Decision", "Consequences"):
        section_text = sections.get(section_name, "")
        if has_placeholder(section_text):
            findings["errors"].append(
                f"{section_name} still contains placeholder markup."
            )
            continue
        if word_count(section_text) < 12:
            findings["warnings"].append(
                f"{section_name} is very short; add enough detail to explain the decision."
            )
        else:
            findings["passes"].append(f"{section_name} contains substantive content.")

    context_text = sections.get("Context", "")
    decision_text = sections.get("Decision", "")
    consequences_text = sections.get("Consequences", "")

    if not contains_any(context_text, ["option", "alternative", "versus", "vs", "instead of"]):
        findings["warnings"].append(
            "Context should identify the main alternatives or competing options."
        )
    else:
        findings["passes"].append("Context records the option space or tradeoff framing.")

    if not contains_any(decision_text, ["because", "why", "chosen option"]):
        findings["warnings"].append(
            "Decision should explain why the chosen option won, not just name it."
        )
    else:
        findings["passes"].append("Decision includes rationale for the chosen option.")

    consequence_lines = [
        line for line in consequences_text.splitlines() if line.strip().startswith("- ")
    ]
    if len(consequence_lines) < 2:
        findings["warnings"].append(
            "Consequences should usually list at least two concrete follow-on effects."
        )
    else:
        findings["passes"].append("Consequences lists multiple concrete effects.")

    if not contains_any(consequences_text, ["negative:", "bad:", "cost", "risk", "limitation", "tradeoff"]):
        findings["warnings"].append(
            "Consequences should include at least one downside, cost, or risk."
        )
    else:
        findings["passes"].append("Consequences includes at least one downside or cost.")

    if front_matter["status"] == "archived" and "Archived on " not in body:
        findings["warnings"].append(
            "Archived decision records should usually include a short archival note near the top."
        )

    return findings


def main() -> int:
    args = parse_args()
    try:
        findings = review_adr(args.doc_path)
    except ValueError as error:
        raise SystemExit(str(error))

    if args.json:
        print(json.dumps(findings))
    else:
        labels = {"errors": "ERROR", "warnings": "WARNING", "passes": "PASS"}
        for key in ("errors", "warnings", "passes"):
            for message in findings[key]:
                print(f"{labels[key]}: {message}")

    return 1 if findings["errors"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
