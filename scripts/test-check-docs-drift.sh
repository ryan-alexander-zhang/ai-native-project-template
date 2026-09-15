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

# The function under test, lifted from the gate itself — no second copy to drift.
eval "$(awk '/^fld\(\) \{/{p=1} p{print} p&&/; \}$/{exit}' scripts/check-docs-drift.sh)"

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

[ $fail -eq 0 ] && echo "OK — fld reads every documented front-matter shape."
exit $fail
