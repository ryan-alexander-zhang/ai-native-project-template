#!/usr/bin/env python3
"""Fail if any aipersimmon.ddd.* setting is unreachable to a reader.

A setting can be missed in two ways, and both are silent at runtime — the
application starts, the key does nothing, and nothing says so:

  1. the key is read by the code but carries no configuration metadata, so a
     consumer's IDE will not complete it and a typo looks like a valid line;
  2. the key has metadata but no row in CONFIGURATION.md, so a reader deciding
     a deployment cannot find out that the knob exists at all.

Both directions are checked. Run after `mvn -f aipersimmon-ddd/pom.xml install`,
which is what generates the metadata this reads.

    python3 scripts/check-config-coverage.py [--library-dir aipersimmon-ddd]

Every read mechanism the library actually uses is scanned: `${...}` placeholders
(which covers @Value, @Scheduled(fixedDelayString), @SchedulerLock, @KafkaListener),
@ConditionalOnProperty, @ConditionalOnExpression, and Environment.getProperty.
@ConfigurationProperties classes need no scanning — the annotation processor
derives their keys, which is exactly what lands in the metadata.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys

KEY = r"[a-zA-Z][a-zA-Z0-9_.\-]*"
PREFIX = "aipersimmon.ddd."

PLACEHOLDER = re.compile(r"\$\{(" + KEY + r")")
CONDITIONAL_ON_PROPERTY = re.compile(r"@ConditionalOnProperty\s*\(([^)]*)\)", re.S)
CONDITIONAL_PREFIX = re.compile(r'prefix\s*=\s*"([^"]*)"')
CONDITIONAL_NAMES = re.compile(r'(?:name|value)\s*=\s*(?:\{([^}]*)\}|"([^"]*)")')
QUOTED = re.compile(r'"([^"]*)"')
ENVIRONMENT_LOOKUP = re.compile(r'get(?:Required)?Property\s*\(\s*"([^"]*)"')
CONDITIONAL_ON_EXPRESSION = re.compile(r'@ConditionalOnExpression\s*\(\s*"([^"]*)"')

# CONFIGURATION.md groups a namespace under a heading and then writes one row per
# key. Two shorthands stand in for a list of sibling keys, and both are expanded
# here so the check reads the document the way a person does.
SECTION = re.compile(r"##+ `(aipersimmon\.ddd\.[a-z.-]+)`")
#   | `a.b` / `.c` / `.d` | ...     -> a.b, a.c, a.d
#   | `effect-relay.*` | ...        -> every known key under effect-relay
ROW = re.compile(r"\|\s*`([a-z][a-z0-9.\-]*\*?)`((?:\s*/\s*`?\.?[a-z][a-z0-9.\-]*`?)*)\s*\|")
ROW_CONTINUATION = re.compile(r"`?\.?([a-z][a-z0-9.\-]*)`?")
# Sections that document other projects' properties, not this library's.
NOT_OUR_NAMESPACE = ("## Properties owned by other projects", "## A production checklist")


def keys_read_by_code(library: pathlib.Path) -> dict[str, set[str]]:
    """Every aipersimmon.ddd.* key the sources read, mapped to where they read it."""
    found: dict[str, set[str]] = {}

    def record(key: str, where: str) -> None:
        key = key.strip()
        if key.startswith(PREFIX):
            found.setdefault(key, set()).add(where)

    for path in sorted(library.glob("*/src/main/java/**/*.java")):
        module = path.relative_to(library).parts[0]
        source = path.read_text(encoding="utf-8")

        for number, line in enumerate(source.split("\n"), start=1):
            for match in PLACEHOLDER.finditer(line):
                record(match.group(1), f"{module} :: {path.name}:{number}")

        for match in CONDITIONAL_ON_PROPERTY.finditer(source):
            body = match.group(1)
            where = f"{module} :: {path.name}:{source[: match.start()].count(chr(10)) + 1}"
            prefix = CONDITIONAL_PREFIX.search(body)
            for braced, single in CONDITIONAL_NAMES.findall(body):
                for name in QUOTED.findall(braced) if braced else [single]:
                    record(f"{prefix.group(1)}.{name}" if prefix else name, where)

        for match in ENVIRONMENT_LOOKUP.finditer(source):
            where = f"{module} :: {path.name}:{source[: match.start()].count(chr(10)) + 1}"
            record(match.group(1), where)

        for match in CONDITIONAL_ON_EXPRESSION.finditer(source):
            for placeholder in PLACEHOLDER.finditer(match.group(1)):
                record(placeholder.group(1), f"{module} :: {path.name}")

    return found


def keys_in_metadata(library: pathlib.Path) -> set[str]:
    """Every key the build published as configuration metadata."""
    keys: set[str] = set()
    for path in library.glob("*/target/classes/META-INF/spring-configuration-metadata.json"):
        for prop in json.loads(path.read_text(encoding="utf-8")).get("properties", []):
            if prop["name"].startswith(PREFIX):
                keys.add(prop["name"])
    return keys


def keys_in_documentation(reference: pathlib.Path, known: set[str]) -> set[str]:
    """Every key CONFIGURATION.md has a row for, with its shorthands expanded."""
    documented: set[str] = set()
    namespace = None
    skipping = False

    for line in reference.read_text(encoding="utf-8").split("\n"):
        if line.startswith(NOT_OUR_NAMESPACE):
            skipping = True
        heading = SECTION.match(line)
        if heading:
            namespace, skipping = heading.group(1), False
            continue
        if skipping or namespace is None:
            continue

        row = ROW.match(line)
        if not row:
            continue

        head = row.group(1)
        if head.endswith("*"):
            # `deadline-worker.*` — the row stands in for every key beneath it.
            stem = f"{namespace}.{head.rstrip('*').rstrip('.')}"
            documented.update(k for k in known if k.startswith(f"{stem}."))
            continue

        documented.add(f"{namespace}.{head}")
        # `/ .max / .multiplier` continuations are siblings of the head key.
        parent = head.rsplit(".", 1)[0] + "." if "." in head else ""
        for continuation in ROW_CONTINUATION.findall(row.group(2) or ""):
            leaf = continuation if "." in continuation else parent + continuation
            documented.add(f"{namespace}.{leaf}")

    return documented


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--library-dir", default="aipersimmon-ddd")
    arguments = parser.parse_args()

    library = pathlib.Path(arguments.library_dir).resolve()
    reference = library / "CONFIGURATION.md"
    if not reference.is_file():
        print(f"error: {reference} not found — is --library-dir right?", file=sys.stderr)
        return 2

    metadata = keys_in_metadata(library)
    if not metadata:
        print(
            "error: no spring-configuration-metadata.json under "
            f"{library}/*/target/ — run `mvn -f {arguments.library_dir}/pom.xml install` first",
            file=sys.stderr,
        )
        return 2

    read = keys_read_by_code(library)
    documented = keys_in_documentation(reference, metadata)

    unindexed = {key: where for key, where in read.items() if key not in metadata}
    undocumented = sorted(metadata - documented)

    if unindexed:
        print(f"Read by the code but carrying no configuration metadata ({len(unindexed)}):\n")
        for key in sorted(unindexed):
            print(f"  {key}")
            for where in sorted(unindexed[key]):
                print(f"      {where}")
        print(
            "\n  Fix: add the key to that module's"
            " src/main/resources/META-INF/additional-spring-configuration-metadata.json"
            "\n  (and spring-boot-configuration-processor to its pom, if it has none).\n"
        )

    if undocumented:
        print(f"Has metadata but no row in CONFIGURATION.md ({len(undocumented)}):\n")
        for key in undocumented:
            print(f"  {key}")
        print("\n  Fix: add a row under that namespace's heading.\n")

    if unindexed or undocumented:
        print("A setting missed here fails silently: the key does nothing and nothing says so.")
        return 1

    print(
        f"OK — {len(metadata)} aipersimmon.ddd.* keys, all indexed and all documented "
        f"({len(read)} read through @Value/@ConditionalOnProperty/@Scheduled/Environment)."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
