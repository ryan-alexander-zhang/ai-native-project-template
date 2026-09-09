#!/usr/bin/env bash
# Fail on doc/code drift the prose cannot catch itself:
#   1. dangling relative links in root *.md and docs/**/*.md
#   2. relation ids that name no doc, or an `archived` one
#   3. ARCHITECTURE.md §5 tree vs tracked top-level dirs, both directions
#   4. decision `enforced_by` paths that do not exist
#   5. warn: `active` design nobody references
#   6. strict only (a generated project, marked by .ainpt.json, or --strict): root-guide placeholders
# Runs from .githooks/pre-commit and .github/workflows/docs-drift.yml.
set -u
cd "$(git rev-parse --show-toplevel)"
strict=0
{ [ "${1:-}" = --strict ] || [ -f .ainpt.json ]; } && strict=1
fail=0
err() { echo "  $*"; fail=1; }

md=$(git ls-files | grep -E '^([^/]+|docs/.+)\.md$' | grep -v 'TEMPLATE\.md$')
inst=$(echo "$md" | grep -E '^docs/[^/]+/[^/]+\.md$' | grep -v '/README\.md$')

# 1. links
for f in $md; do
  d=$(dirname "$f")
  while read -r t; do
    [ -e "$d/$t" ] && continue
    [ "$strict" = 0 ] && [ -f "$d/${t%.md}_TEMPLATE.md" ] && continue   # root guide not yet created from its template
    err "$f: dangling link $t"
  done < <(grep -aoE '\]\([^) ]+' "$f" | sed 's/^](//; s/#.*//' | grep -v -e '^$' -e '^[a-z]*:' -e '<' | sort -u)
done

# 2. relation ids
ids=$(for f in $inst; do echo "$(grep -m1 '^id: ' "$f" | cut -d' ' -f2) $(grep -m1 '^status: ' "$f" | cut -d' ' -f2)"; done)
for f in $inst; do
  while read -r id; do
    case "$id" in
      *-FR-*|*-BR-*|*-AC-*) echo "$ids" | grep -q "^${id%%-[FBA][RC]-*}-" || err "$f: $id names no doc" ;;
      *) s=$(echo "$ids" | awk -v i="$id" '$1==i{print $2}')
         [ -z "$s" ] && err "$f: $id names no doc"
         [ "$s" = archived ] && err "$f: $id is archived" ;;
    esac
  done < <(awk 'NR==1{next} /^---$/{exit} /^(parent|implements|informs|motivated_by|constrains|blocks|verifies):/||/^ *- /{print}' "$f" |
           grep -aoE '[a-z]+-[0-9]{5}-[A-Za-z0-9.-]+' | grep -v -- '-example-slug$' | sort -u)
done

# 3. ARCHITECTURE.md §5 tree
if [ -f ARCHITECTURE.md ]; then
  tree=$(awk '/^## 5[. ]/{s=1;next} s&&/^## /{exit} s&&/^```/{f=!f;if(!f&&n)exit;next} s&&f{print;n=1}' ARCHITECTURE.md |
         grep -oE '^[├└]── [^ ]+/' | sed 's/^[├└]── //; s#/$##')
  for d in $tree; do [ -d "$d" ] || err "ARCHITECTURE.md §5 lists $d/, not on disk"; done
  for d in $(git ls-tree --name-only -d HEAD | grep -v '^\.'); do
    echo "$tree" | grep -qx "$d" || err "ARCHITECTURE.md §5 misses $d/"
  done
fi

# 4. enforced_by
for f in $(echo "$inst" | grep '^docs/decision/'); do
  while read -r p; do [ -e "$p" ] || err "$f: enforced_by $p not found"; done \
    < <(grep -m1 '^enforced_by:' "$f" | sed 's/^enforced_by: *//; s/#.*//; s/[][,]/ /g' | tr ' ' '\n' | grep -v -e '^$' -e '<')
done

# 5. orphan design
for f in $(echo "$inst" | grep '^docs/design/'); do
  grep -q '^status: active' "$f" || continue
  grep -q '^informs:' "$f" && continue
  id=$(grep -m1 '^id: ' "$f" | cut -d' ' -f2)
  grep -rl --include='*.md' -- "$id" docs | grep -v "^$f$" | grep -q . || echo "  warn: $f: active design nobody references"
done

# 6. placeholders
if [ "$strict" = 1 ]; then
  for f in $(echo "$md" | grep -E '^[^/]+\.md$' | grep -v -e '_TEMPLATE\.md$' -e '^README\.md$'); do
    grep -nE '<command>|<check>|<path>|<value>|<default>|\*\(none yet\)\*' "$f" | head -3 | sed "s#^#  $f:#; s#\$# placeholder#"
    grep -qE '<command>|<check>|<path>|<value>|<default>|\*\(none yet\)\*' "$f" && fail=1
  done
fi

[ "$fail" = 0 ] && echo "OK — no doc drift." || { echo "Doc drift found (scripts/check-docs-drift.sh)."; exit 1; }
