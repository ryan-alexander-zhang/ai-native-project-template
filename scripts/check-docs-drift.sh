#!/usr/bin/env bash
# Fail on doc/code drift the prose cannot catch itself. The whiteboard (persimmon) is optional; this
# script is the gate that always runs. Rules come from docs/README.md and the folder READMEs.
#   1.  dangling relative links in root *.md and docs/**/*.md
#   2.  front matter: id well-formed / prefix = type / = filename / unique; status in the kind's
#       vocabulary; only the fields the type carries; decided_by value; parent single-valued
#   2c. spec / rule item grammar: declarations well-formed, spec owns FR and rule owns BR, every
#       item has an AC, every AC names a declared item
#   2b. relation ids name an existing doc (not `archived`) or a declared item
#   2d. supersedes / superseded_by pairing
#   2e. plan resolved gate: every AC in the delivery scope has a pass row in an active record
#       whose parent is the plan; record verifies matches its checklist
#   3.  ARCHITECTURE.md §5 tree vs tracked top-level dirs, both directions
#   4.  decision `enforced_by` paths that do not exist
#   5.  warn: `active` design nobody references
#   6.  strict only (a generated project, marked by .ainpt.json, or --strict): root-guide placeholders
# type -> kind and type -> relation fields are read from whiteboard.config.yaml when present (one
# table shared with the board), else from built-in defaults; files its `exclude` hits are skipped.
# Runs from .githooks/pre-commit and .github/workflows/docs-drift.yml.
set -u
cd "$(git rev-parse --show-toplevel)"
strict=0
{ [ "${1:-}" = --strict ] || [ -f .ainpt.json ]; } && strict=1
fail=0
err() { echo "  $*"; fail=1; }
grep() { command grep -a "$@"; }   # docs are text; never let a stray byte turn a match into "Binary file … matches"

md=$(git ls-files | grep -E '^([^/]+|docs/.+)\.md$' | grep -v 'TEMPLATE\.md$')
inst=$(echo "$md" | grep -E '^docs/[^/]+/[^/]+\.md$' | grep -v '/README\.md$')

# yaml `exclude:` — files it hits are not documents (docs/README.md): raw material such as
# docs/reference/<slug>/source/**. Dropped from every check, the link check included.
cfg=whiteboard.config.yaml
if [ -f "$cfg" ]; then
  rx=$(awk '/^exclude:/{s=1;sub(/^exclude: */,"");if($0!="")print;next} s&&/^ *- /{sub(/^ *- */,"");print;next} s&&/^[^ #]/{exit}' "$cfg" |
       tr -d '[],"'"'" | tr ' ' '\n' | grep . | sed 's/\./\\./g; s/\*\*/\x01/g; s/\*/[^\/]*/g; s/\x01/.*/g' | paste -sd'|' -)
  if [ -n "$rx" ]; then
    md=$(echo "$md" | grep -vE "^docs/($rx)$")
    inst=$(echo "$inst" | grep -vE "^docs/($rx)$")
  fi
fi

# 1. links
for f in $md; do
  d=$(dirname "$f")
  while read -r t; do
    [ -e "$d/$t" ] && continue
    [ "$strict" = 0 ] && [ -f "$d/${t%.md}_TEMPLATE.md" ] && continue   # root guide not yet created from its template
    err "$f: dangling link $t"
  done < <(grep -aoE '\]\([^) ]+' "$f" | sed 's/^](//; s/#.*//' | grep -v -e '^$' -e '^[a-z]*:' -e '<' | sort -u)
done

# type -> living|work. Source: whiteboard.config.yaml `types:` when present (the one table the
# board and this script share); else the docs/README.md default split. Empty = type not declared.
kind_of() {
  if [ -f "$cfg" ]; then sed -n "s/^  $1: *{ *kind: *\([a-z]*\).*/\1/p" "$cfg" | head -1; return; fi
  case "$1" in
    plan|issue) echo work ;;
    idea|prd|analysis|reference|integration|spec|rule|decision|design|record|report|operation|prompt) echo living ;;
  esac
}
# type -> the relation fields it may declare, space-separated (docs/README.md "A field the document's
# type does not carry must not appear at all"). Source: yaml `carries:` when present — a type absent from
# that block is not checked, as the yaml says; else the folder READMEs' Relations sections. "-" = check off.
carries_of() {
  if [ -f "$cfg" ]; then
    # `=` marks a matched line, so `idea: []` (carries nothing, check on) differs from absent (check off)
    awk '/^carries:/{s=1;next} s&&/^[^ #]/{exit} s' "$cfg" | sed -n "s/^  $1: *\[\(.*\)\].*/=\1/p" | tr -d ',' | grep . | sed 's/^=//' || echo -
    return
  fi
  case "$1" in
    prd) echo parent ;;                   analysis) echo parent informs ;;
    reference|integration|rule|design|report) echo informs ;;
    spec) echo parent ;;                  decision) echo motivated_by constrains ;;
    plan|operation) echo implements ;;    issue) echo blocks ;;
    record) echo parent verifies ;;       idea|prompt) echo ;;
    *) echo - ;;
  esac
}

# 2. front matter: id well-formed, prefix = type, = filename, unique; status in the kind's vocabulary
#    (docs/README.md Front Matter Rules)
ids=$(for f in $inst; do echo "$(grep -m1 '^id: ' "$f" | cut -d' ' -f2) $(grep -m1 '^status: ' "$f" | cut -d' ' -f2) $f"; done)
for f in $inst; do
  id=$(grep -m1 '^id: ' "$f" | cut -d' ' -f2)
  t=$(grep -m1 '^type: ' "$f" | cut -d' ' -f2)
  s=$(grep -m1 '^status: ' "$f" | cut -d' ' -f2)
  [ "$(tr -cd '\000' < "$f" | wc -c)" -eq 0 ] || err "$f: contains a NUL byte"
  [ -z "$id" ] && { err "$f: no id"; continue; }
  echo "$id" | grep -qE '^[a-z]+-[0-9]{5}-[a-z0-9-]+$' || err "$f: id $id is not <type>-<nnnnn>-<slug>"
  [ "${id%%-*}" = "$t" ] || err "$f: id prefix ${id%%-*} does not match type"
  [ "$id" = "$(basename "$f" .md)" ] || err "$f: id $id does not match filename"
  case "$(kind_of "$t")" in
    living) ok='draft|active|archived' ;;
    work)   ok='draft|open|resolved|wontfix|archived' ;;
    *)      err "$f: type '$t' is not a declared type"; continue ;;
  esac
  echo "$s" | grep -qxE "$ok" || err "$f: status '$s' not allowed for $t (one of: $ok)"
  # fields the type does not carry (docs/README.md); decided_by / enforced_by belong to decision only
  allow=$(carries_of "$t")
  if [ "$allow" != - ]; then
    allow="id type status supersedes superseded_by $allow"; [ "$t" = decision ] && allow="$allow decided_by enforced_by"
    for k in $(awk 'NR==1&&/^---$/{s=1;next} s&&/^---$/{exit} s&&/^[A-Za-z_]+:/{sub(/:.*/,"");print}' "$f"); do
      echo " $allow " | grep -q " $k " || err "$f: field '$k' is not carried by $t"
    done
  fi
  v=$(grep -m1 '^decided_by:' "$f" | sed 's/^decided_by: *//; s/ *#.*//')
  [ -n "$v" ] && ! echo "$v" | grep -qxE 'human|agent' && err "$f: decided_by '$v' is not human|agent"
  grep -m1 '^parent:' "$f" | grep -qE '\[|,' && err "$f: parent is single-valued"
done

# 2c. spec / rule item grammar (docs/spec/README.md, docs/rule/README.md 机器可读形态): a declaration is a
#     whole line starting with the bold id of THIS doc — `- **<id>-FR-<i>**`, `| **<id>-FR-<i>** |`, or
#     `- **<id>-AC-<i>.<k>** (<id>-FR-<i>)`. spec owns FR, rule owns BR. Every item needs an AC; every AC
#     names an item that exists. `items` collects every declared item/AC id for the checks below.
items= acmap=
for f in $inst; do
  t=$(grep -m1 '^type: ' "$f" | cut -d' ' -f2)
  case "$t" in spec) k=FR; o=BR ;; rule) k=BR; o=FR ;; *) continue ;; esac
  id=$(grep -m1 '^id: ' "$f" | cut -d' ' -f2 | cut -d- -f1,2)   # <type>-<nnnnn>: item ids carry this, not the slug
  decl=$(awk 'NR==1&&/^---$/{s=1;next} s==1&&/^---$/{s=2;next} s==2' "$f" | grep -E "^(- |[|] )?\*\*$id-")
  [ -z "$decl" ] && continue
  its=$(echo "$decl" | sed -nE "s/^(- |[|] )\*\*($id-$k-[0-9]+)\*\*( .*)?$/\2/p")
  acs=$(echo "$decl" | sed -nE "s/^- \*\*($id-AC-[0-9]+\.[0-9]+)\*\* \(($id-$k-[0-9]+)\).*$/\1 \2/p")
  echo "$decl" | grep -E "\*\*$id-$o-" | grep -q . && err "$f: $t owns $k ids, not $o"
  while read -r l; do err "$f: malformed declaration: ${l:0:60}"; done \
    < <(echo "$decl" | grep -vE "^(- |[|] )\*\*$id-$k-[0-9]+\*\*( .*)?$|^- \*\*$id-AC-[0-9]+\.[0-9]+\*\* \($id-$k-[0-9]+\)")
  for d in $(echo "$its" | sort | uniq -d); do err "$f: $d declared twice"; done
  for i in $its; do echo "$acs" | awk '{print $2}' | grep -qx "$i" || err "$f: $i has no acceptance"; done
  for i in $(echo "$acs" | awk '{print $2}' | sort -u); do echo "$its" | grep -qx "$i" || err "$f: acceptance names undeclared $i"; done
  items="$items
$its
$(echo "$acs" | awk '{print $1}')"
  acmap="$acmap
$acs"
done
items=$(echo "$items" | grep .); acmap=$(echo "$acmap" | grep .)
for d in $(echo "$ids" | cut -d' ' -f1 | sort | uniq -d); do
  err "duplicate id $d: $(echo "$ids" | awk -v i="$d" '$1==i{print $3}' | tr '\n' ' ')"
done

# 2b. relation ids
for f in $inst; do
  while read -r id; do
    case "$id" in
      *-FR-*|*-BR-*|*-AC-*) echo "$items" | grep -qx "$id" || err "$f: $id is not a declared item" ;;   # items of archived docs stay resolvable (docs/spec/README.md Splitting)
      *) s=$(echo "$ids" | awk -v i="$id" '$1==i{print $2}')
         [ -z "$s" ] && err "$f: $id names no doc"
         [ "$s" = archived ] && err "$f: $id is archived" ;;
    esac
  done < <(awk 'NR==1{next} /^---$/{exit} /^(parent|implements|informs|motivated_by|constrains|blocks|verifies):/||/^ *- /{print}' "$f" |
           grep -aoE '[a-z]+-[0-9]{5}-[A-Za-z0-9.-]+' | grep -v -- '-example-slug$' | sort -u)
done

# 2d. supersedes / superseded_by pairing (docs/README.md: the new doc carries `supersedes`, the old one is
#     `archived` and carries `superseded_by` back — the only edit an archived doc takes)
fld() { grep -m1 "^$2:" "$1" | sed "s/^$2: *//; s/ *#.*//; s/[][,]/ /g"; }
file_of() { echo "$ids" | awk -v i="$1" '$1==i{print $3}'; }
status_of() { echo "$ids" | awk -v i="$1" '$1==i{print $2}'; }
for f in $inst; do
  me=$(grep -m1 '^id: ' "$f" | cut -d' ' -f2)
  for b in $(fld "$f" supersedes); do
    case "$b" in *-example-slug) continue ;; esac
    bf=$(file_of "$b"); [ -z "$bf" ] && { err "$f: supersedes $b names no doc"; continue; }
    [ "$(status_of "$b")" = archived ] || err "$f: supersedes $b, which is not archived"
    echo " $(fld "$bf" superseded_by) " | grep -q " $me " || err "$bf: superseded by $me but does not declare superseded_by: [$me]"
  done
  sb=$(fld "$f" superseded_by)
  [ -n "$sb" ] && [ "$(status_of "$me")" != archived ] && err "$f: declares superseded_by but is not archived"
  for a in $sb; do
    case "$a" in *-example-slug) continue ;; esac
    af=$(file_of "$a"); [ -z "$af" ] && { err "$f: superseded_by $a names no doc"; continue; }
    echo " $(fld "$af" supersedes) " | grep -q " $me " || err "$f: superseded_by $a, but $a does not declare supersedes: [$me]"
  done
done

# 2e. plan resolved gate (docs/plan/README.md, docs/record/README.md): a `resolved` plan's delivery scope —
#     every AC of every item its `implements` puts in scope — needs a `pass` row in an `active` record whose
#     `parent` is the plan. Row = first cell exactly one item/AC id; header has Test/测试 and Result/结果 off
#     the first column. A record's `verifies` must expand to the same AC set as its checklist.
acs_of() {  # doc id | item id | AC id -> the ACs in scope (docs/README.md: an AC puts its owning item in scope)
  case "$1" in
    *-AC-*)        acs_of "$(echo "$acmap" | awk -v a="$1" '$1==a{print $2}')" ;;
    *-FR-*|*-BR-*) echo "$acmap" | awk -v i="$1" '$2==i{print $1}' ;;
    spec-*|rule-*) p=$(echo "$1" | cut -d- -f1,2); echo "$acmap" | awk -v p="$p-" 'index($2,p)==1{print $1}' ;;
  esac
}
rows_of() {  # record file -> "<first cell>\t<Result cell>" per data row of every checklist table
  awk 'BEGIN{FS="|"} !/^\|/{st=0;next}
       st==0{h=tolower($0); st=2; if(h~/test|测试/&&h~/result|结果/){rc=0;for(i=3;i<NF;i++){c=tolower($i);if(c~/result|结果/)rc=i} if(rc&&tolower($2)!~/test|测试|result|结果/)st=1} next}
       st==1{if($0~/^[| :-]+$/)next; c=$2;gsub(/^ +| +$/,"",c); r=$rc;gsub(/^ +| +$/,"",r); print c "\t" r}' "$1"
}
for pf in $inst; do
  grep -q '^type: plan$' "$pf" && grep -q '^status: resolved$' "$pf" || continue
  plan=$(grep -m1 '^id: ' "$pf" | cut -d' ' -f2)
  scope=$(for i in $(fld "$pf" implements); do acs_of "$i"; done | sort -u)
  [ -z "$scope" ] && continue
  passed=
  for rf in $inst; do
    grep -q '^type: record$' "$rf" && grep -q '^status: active$' "$rf" && [ "$(fld "$rf" parent)" = "$plan" ] || continue
    listed=
    while IFS=$'\t' read -r c r; do
      if echo "$c" | grep -qxE '[a-z]+-[0-9]{5}-(FR|BR|AC)-[0-9.]+'; then
        echo "$items" | grep -qx "$c" || { err "$rf: checklist row $c is not a declared item"; continue; }
        case "$c" in *-AC-*) ;; *) err "$rf: checklist row $c names an item, not an AC — verification is per AC"; continue ;; esac
        listed="$listed $c"
        [ "$r" = pass ] && passed="$passed $c" || err "$rf: $c result '$r'"
      elif echo "$c" | grep -qE '[a-z]+-[0-9]{5}-(FR|BR|AC)-'; then
        err "$rf: malformed checklist row '${c:0:50}' — exactly one id per row"
      fi
    done < <(rows_of "$rf")
    want=$(for i in $(fld "$rf" verifies); do acs_of "$i"; done | sort -u)
    for a in $want; do echo " $listed " | grep -q " $a " || err "$rf: verifies covers $a but the checklist has no row for it"; done
    for a in $(echo "$listed" | tr ' ' '\n' | sort -u); do echo "$want" | grep -qx "$a" || err "$rf: checklist row $a is not covered by verifies"; done
  done
  for a in $scope; do echo " $passed " | grep -q " $a " || err "$pf: resolved, but $a has no pass row in an active record with parent $plan"; done
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
