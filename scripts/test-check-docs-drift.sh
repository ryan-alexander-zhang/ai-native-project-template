#!/usr/bin/env bash
# Self-check for the `fld` front-matter reader in check-docs-drift.sh.
#
# fld is the gate's single parsing chokepoint: every relation check (2d, 2e, `operated`) reads its
# ids through it, and it reports "no value" and "field absent" the same way — an empty string. So a
# parsing miss does not fail the gate, it makes the gate skip the document silently. Both times that
# happened (5d0411e5, 381cd695) a downstream project's front matter had a shape this repo's own
# docs/ does not contain, which is why these fixtures are shapes rather than real documents.
#
# Run: bash scripts/test-check-docs-drift.sh
set -u
cd "$(dirname "$0")/.."

# The functions under test, lifted from the gate itself — no second copy to drift.
eval "$(awk '/^fld\(\) \{/{p=1} p{print} p&&/; \}$/{exit}' scripts/check-docs-drift.sh)"
eval "$(awk '/^design_kinds=/{print} /^design_check\(\) \{/{p=1} p{print} p&&/^\}$/{exit}' scripts/check-docs-drift.sh)"
grep() { command grep -a "$@"; }

tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
fail=0

# fld pads with the whitespace left by the bracket/comma stripping; callers compare inside " $v ".
norm() { echo "$*" | tr -s '[:space:]' ' ' | sed 's/^ //; s/ $//'; }

check() {  # <name> <key> <expected> <<< document
  local name=$1 key=$2 want=$3 got
  cat > "$tmp/doc.md"
  got=$(norm "$(fld "$tmp/doc.md" "$key")")
  if [ "$got" = "$want" ]; then
    echo "ok   $name"
  else
    echo "FAIL $name: expected [$want], got [$got]"
    fail=1
  fi
}

check "single-line flow list" verifies "spec-00001-AC-1.1 spec-00001-AC-1.2" <<'EOF'
---
id: record-00001-x
verifies: [spec-00001-AC-1.1, spec-00001-AC-1.2]
---
EOF

check "single-line flow list with a trailing comment" verifies "spec-00002-AC-1.1" <<'EOF'
---
id: record-00002-x
verifies: [spec-00002-AC-1.1]   # what this record covers
---
EOF

check "flow list wrapped over several lines" verifies "spec-00003-AC-1.1 spec-00003-AC-1.2" <<'EOF'
---
id: record-00003-x
verifies: [
  spec-00003-AC-1.1,
  spec-00003-AC-1.2
]
status: active
---
EOF

check "wrapped flow list, comment on the opening line" verifies "spec-00004-AC-1.1" <<'EOF'
---
id: record-00004-x
verifies: [   # the ids below
  spec-00004-AC-1.1
]
---
EOF

check "wrapped flow list, a ] inside a comment does not end it" verifies "spec-00005-AC-1.1 spec-00005-AC-1.2" <<'EOF'
---
id: record-00005-x
verifies: [
  spec-00005-AC-1.1,   # not the closing ] of this list
  spec-00005-AC-1.2
]
---
EOF

check "block list" verifies "spec-00006-AC-1.1 spec-00006-AC-1.2" <<'EOF'
---
id: record-00006-x
verifies:
  - spec-00006-AC-1.1
  - spec-00006-AC-1.2
status: active
---
EOF

check "a field with no value reads empty" implements "" <<'EOF'
---
id: record-00007-x
implements:
verifies: [spec-00007-AC-1.1]
---
EOF

check "a body line is prose, not a value" verifies "spec-00008-AC-1.1" <<'EOF'
---
id: record-00008-x
verifies: [spec-00008-AC-1.1]
---
The front matter of a record looks like this:

verifies:
  - spec-99999-AC-9.9
EOF

check "an absent field reads empty, whatever the body says" supersedes "" <<'EOF'
---
id: record-00009-x
verifies: [spec-00009-AC-1.1]
---
An archived doc carries the pairing back, like this:

supersedes: [decision-99999-something]
EOF

# design_check (2h): the docs/design/README.md Machine-readable form, one fixture per gate.
dcheck() {  # <name> <expected finding count> [<finding filter>] <<< document
  local name=$1 want=$2 got
  cat > "$tmp/design-00001-x.md"
  got=$(design_check "$tmp/design-00001-x.md" | grep -c -- "${3:-.}")
  if [ "$got" = "$want" ]; then echo "ok   $name"; else echo "FAIL $name: expected $want findings, got $got"; design_check "$tmp/design-00001-x.md" | sed 's/^/     /'; fail=1; fi
}

dcheck "a clean storage design passes" 0 <<'EOF'
---
id: design-00001-x
type: design
status: active
kind: storage
informs: [spec-00001-y]
---
# Design: Order storage

## 1. Subject
## 2. ER

```mermaid
erDiagram
```

## 3. Columns

Guards cite `rule-00001-BR-2`; the spec's `spec-00001-AC-1.1` decides the code.

## 4. Keys and Indexes
## 5. DDL
## Decisions
EOF

dcheck "numbered sections differ from the kind's template" 1 <<'EOF'
---
kind: deployment
---
## 1. Environment
## 2. Topology

```mermaid
flowchart LR
```

## 3. Components
## 4. Wiring
## Decisions
EOF

dcheck "a contract with a diagram, and no Decisions" 2 <<'EOF'
---
kind: contract
---
## 1. Interface
## 2. Operations
## 3. Schemas
## 4. Conventions Applied

```mermaid
flowchart LR
```
EOF

dcheck "unknown kind" 1 "kind '" <<'EOF'
---
id: design-00001-x
type: design
kind: schema
---
EOF

dcheck "two mermaid fences where one is due" 1 <<'EOF'
---
kind: interaction
---
## 1. Participants
## 2. Trigger and End State
## 3. Sequence

```mermaid
sequenceDiagram
```
```mermaid
flowchart LR
```

## 4. Steps
## Decisions
EOF

dcheck "no mermaid fence where one is due" 1 <<'EOF'
---
kind: lifecycle
---
## 1. Subject
## 2. States
## 3. Transitions
## 4. Guards
## 5. Diagram
## Decisions
EOF

dcheck "shall, a GWT line, a bold declaration — one finding each" 3 <<'EOF'
---
kind: contract
---
## 1. Interface
## 2. Operations
## 3. Schemas
## 4. Conventions Applied
## Decisions
The service shall return 404.
- Given a removed link
- **spec-00001-FR-3** (Unwanted) …
EOF

dcheck "prose opening with When is not a GWT line" 0 <<'EOF'
---
kind: interaction
---
## 1. Participants
## 2. Trigger and End State
## 3. Sequence

When removal locks the row, the cache namespace rotates after commit.

```mermaid
sequenceDiagram
```

## 4. Steps
## Decisions
EOF

{ printf -- '---
kind: algorithm
---
'; yes '- line' | head -150; } > "$tmp/long.md"
got=$(design_check "$tmp/long.md" | grep -c 'limit 150')
[ "$got" = 1 ] && echo "ok   153 lines is over the limit" || { echo "FAIL 153 lines: expected 1 finding, got $got"; fail=1; }
{ printf -- '---\nkind: algorithm\n---\n'; yes '- line' | head -147; } > "$tmp/edge.md"
got=$(design_check "$tmp/edge.md" | grep -c 'limit 150')
[ "$got" = 0 ] && echo "ok   150 lines is within the limit" || { echo "FAIL 150 lines: expected 0 findings, got $got"; fail=1; }

[ $fail -eq 0 ] && echo "OK — fld reads every documented front-matter shape; design_check enforces every design gate."
exit $fail
