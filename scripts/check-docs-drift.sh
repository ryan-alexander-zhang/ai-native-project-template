#!/usr/bin/env bash
# Fail on doc/code drift the prose cannot catch itself. The whiteboard (persimmon) is optional; this
# script is the gate that always runs. Rules come from docs/README.md and the folder READMEs.
#   1.  dangling relative links in root *.md and docs/**/*.md
#   2.  front matter: id well-formed / prefix = type / = filename / unique; status in the kind's
#       vocabulary; only the fields the type carries; decided_by value; parent single-valued
#   2c. spec / rule / quality item grammar: declarations well-formed, spec owns FR, rule owns BR,
#       quality owns QR; every item has an AC (QS for quality); every AC / QS names a declared item;
#       a QR carries a vocabulary tag, a QS its [method | stage] and all six parts (QUALITY.md)
#   2b. relation ids name an existing doc (not `archived`) or a declared item
#   2d. supersedes / superseded_by pairing
#   2e. plan resolved gate: every AC / QS in the delivery scope has a pass row in an active record
#       whose parent is the plan; a load / chaos / observe QS row carries Evidence; a runtime QS in
#       scope has an active operation doc implementing it; record verifies matches its checklist
#   2f. active quality doc: a build QS needs `enforced_by`; warn on a runtime QS no operation implements
#   2g. profile `n/a` cells only under the no-runtime exception: decision cited, mandatory rows valued, all rows present
#   3.  ARCHITECTURE.md §5 tree vs tracked top-level dirs, both directions
#   4.  decision / quality `enforced_by` paths that do not exist
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
    idea|prd|analysis|reference|integration|spec|rule|quality|decision|design|record|report|operation|prompt) echo living ;;
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
    reference|integration|rule|report) echo informs ;;   design) echo informs implements ;;
    spec) echo parent ;;                  quality) echo parent informs ;;
    decision) echo motivated_by constrains ;;
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
  # fields the type does not carry (docs/README.md); decided_by belongs to decision only, enforced_by to decision and quality
  allow=$(carries_of "$t")
  if [ "$allow" != - ]; then
    allow="id type status supersedes superseded_by $allow"
    [ "$t" = decision ] && allow="$allow decided_by enforced_by"; [ "$t" = quality ] && allow="$allow enforced_by"
    for k in $(awk 'NR==1&&/^---$/{s=1;next} s&&/^---$/{exit} s&&/^[A-Za-z_]+:/{sub(/:.*/,"");print}' "$f"); do
      echo " $allow " | grep -q " $k " || err "$f: field '$k' is not carried by $t"
    done
  fi
  v=$(grep -m1 '^decided_by:' "$f" | sed 's/^decided_by: *//; s/ *#.*//')
  [ -n "$v" ] && ! echo "$v" | grep -qxE 'human|agent' && err "$f: decided_by '$v' is not human|agent"
  grep -m1 '^parent:' "$f" | grep -qE '\[|,' && err "$f: parent is single-valued"
done

# 2c. spec / rule / quality item grammar (docs/spec/README.md, docs/rule/README.md, docs/quality/README.md
#     机器可读形态): a declaration is a whole line starting with the bold id of THIS doc — `- **<id>-FR-<i>**`,
#     `| **<id>-FR-<i>** |`, `- **<id>-AC-<i>.<k>** (<id>-FR-<i>)`; for quality `- **<id>-QR-<i>** (<Tag>) …` and
#     `- **<id>-QS-<i>.<k>** (<id>-QR-<i>) [<method> | <stage>]` followed by its six labelled parts. spec owns FR,
#     rule owns BR, quality owns QR. Every item needs an AC / QS; every AC / QS names an item that exists.
#     `items` collects every declared id; `acmap` maps AC / QS -> item; `qsmeta` maps QS -> method stage file.
tags='Efficient|Reliable|Secure|Maintainable|Operable|Flexible|Usable|Safe'   # single source: QUALITY.md Attribute Axis (arc42 Q42 tags)
items= acmap= qsmeta=
for f in $inst; do
  t=$(grep -m1 '^type: ' "$f" | cut -d' ' -f2)
  case "$t" in spec) k=FR; o='BR|QR' ;; rule) k=BR; o='FR|QR' ;; quality) k=QR; o='FR|BR' ;; *) continue ;; esac
  id=$(grep -m1 '^id: ' "$f" | cut -d' ' -f2 | cut -d- -f1,2)   # <type>-<nnnnn>: item ids carry this, not the slug
  body=$(awk 'NR==1&&/^---$/{s=1;next} s==1&&/^---$/{s=2;next} s==2' "$f")
  decl=$(echo "$body" | grep -E "^(- |[|] )?\*\*$id-")
  [ -z "$decl" ] && continue
  if [ "$t" = quality ]; then
    itemrx="^- \*\*$id-QR-[0-9]+\*\* \(($tags)\) .+$"
    acrx="^- \*\*$id-QS-[0-9]+\.[0-9]+\*\* \($id-QR-[0-9]+\) \[(fitness|test|load|chaos|observe) \| (build|release|runtime)\]$"
    its=$(echo "$decl" | grep -E "$itemrx" | sed -nE "s/^- \*\*($id-QR-[0-9]+)\*\*.*$/\1/p")
    acs=$(echo "$decl" | grep -E "$acrx" | sed -nE "s/^- \*\*($id-QS-[0-9]+\.[0-9]+)\*\* \(($id-QR-[0-9]+)\) \[([a-z]+) \| ([a-z]+)\]$/\1 \2 \3 \4/p")
    # every QS carries Source / Stimulus / Artifact / Environment / Response / Measure as indented labelled lines
    while read -r q m; do err "$f: $q lacks $m"; done < <(echo "$body" | awk -v id="$id" '
      function flush(  i,m,p) { split("Source Stimulus Artifact Environment Response Measure", p, " ")
        m=""; for (i=1;i<=6;i++) if (!(p[i] in seen)) m = m (m ? "," : "") p[i]
        if (m) print q, m; q=""; split("", seen) }
      $0 ~ "^- \\*\\*" id "-QS-" { if (q) flush(); q=$0; sub(/^- \*\*/,"",q); sub(/\*\*.*/,"",q); next }
      q && /^  +[A-Za-z]+:/ { l=$0; sub(/^ +/,"",l); sub(/:.*/,"",l); seen[l]=1; next }
      q && !/^  / { flush() }
      END { if (q) flush() }')
  else
    itemrx="^(- |[|] )\*\*$id-$k-[0-9]+\*\*( .*)?$"
    acrx="^- \*\*$id-AC-[0-9]+\.[0-9]+\*\* \($id-$k-[0-9]+\)"
    its=$(echo "$decl" | sed -nE "s/^(- |[|] )\*\*($id-$k-[0-9]+)\*\*( .*)?$/\2/p")
    acs=$(echo "$decl" | sed -nE "s/^- \*\*($id-AC-[0-9]+\.[0-9]+)\*\* \(($id-$k-[0-9]+)\).*$/\1 \2/p")
  fi
  echo "$decl" | grep -E "\*\*$id-($o)-" | grep -q . && err "$f: $t owns $k ids, not $(echo "$o" | tr '|' '/')"
  while read -r l; do err "$f: malformed declaration: ${l:0:60}"; done \
    < <(echo "$decl" | grep -vE "$itemrx|$acrx")
  for d in $(echo "$its" | sort | uniq -d); do err "$f: $d declared twice"; done
  for i in $its; do echo "$acs" | awk '{print $2}' | grep -qx "$i" || err "$f: $i has no acceptance"; done
  for i in $(echo "$acs" | awk '{print $2}' | sort -u); do echo "$its" | grep -qx "$i" || err "$f: acceptance names undeclared $i"; done
  items="$items
$its
$(echo "$acs" | awk '{print $1}')"
  acmap="$acmap
$(echo "$acs" | awk '{print $1, $2}')"
  [ "$t" = quality ] && qsmeta="$qsmeta
$(echo "$acs" | awk -v f="$f" '{print $1, $3, $4, f}')"
done
items=$(echo "$items" | grep .); acmap=$(echo "$acmap" | grep .); qsmeta=$(echo "$qsmeta" | grep .)
for d in $(echo "$ids" | cut -d' ' -f1 | sort | uniq -d); do
  err "duplicate id $d: $(echo "$ids" | awk -v i="$d" '$1==i{print $3}' | tr '\n' ' ')"
done

# 2b. relation ids
for f in $inst; do
  while read -r id; do
    case "$id" in
      *-FR-*|*-BR-*|*-QR-*|*-AC-*|*-QS-*) echo "$items" | grep -qx "$id" || err "$f: $id is not a declared item" ;;   # items of archived docs stay resolvable (docs/spec/README.md Splitting)
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
#     every AC / QS of every item its `implements` puts in scope — needs a `pass` row in an `active` record whose
#     `parent` is the plan. Row = first cell exactly one item/AC/QS id; header has Test/测试 and Result/结果 off
#     the first column. A QS row whose method is load / chaos / observe needs a non-empty Evidence/证据 cell
#     (QUALITY.md: a test name is not evidence that a measure held); a runtime QS in scope needs an active
#     operation doc implementing it or its doc. A record's `verifies` must expand to the same set as its checklist.
acs_of() {  # doc id | item id | AC/QS id -> the ACs / QSs in scope (docs/README.md: an AC puts its owning item in scope)
  case "$1" in
    *-AC-*|*-QS-*)        acs_of "$(echo "$acmap" | awk -v a="$1" '$1==a{print $2}')" ;;
    *-FR-*|*-BR-*|*-QR-*) echo "$acmap" | awk -v i="$1" '$2==i{print $1}' ;;
    spec-*|rule-*|quality-*) p=$(echo "$1" | cut -d- -f1,2); echo "$acmap" | awk -v p="$p-" 'index($2,p)==1{print $1}' ;;
  esac
}
qs_method() { echo "$qsmeta" | awk -v q="$1" '$1==q{print $2}'; }
qs_stage()  { echo "$qsmeta" | awk -v q="$1" '$1==q{print $3}'; }
operated() {  # QS id -> 0 when a non-archived operation doc `implements` it or its quality doc
  for of in $(echo "$inst" | grep '^docs/operation/'); do
    [ "$(grep -m1 '^status: ' "$of" | cut -d' ' -f2)" = archived ] && continue
    echo " $(fld "$of" implements) " | grep -qE " ($1|$(echo "$1" | cut -d- -f1,2)-[a-z0-9-]+) " && return 0
  done
  return 1
}
rows_of() {  # record file -> "<first cell>\t<Result cell>\t<Evidence cell>" per data row of every checklist table
  awk 'BEGIN{FS="|"} !/^\|/{st=0;next}
       st==0{h=tolower($0); st=2; if(h~/test|测试/&&h~/result|结果/){rc=0;ec=0;for(i=3;i<NF;i++){c=tolower($i);if(c~/result|结果/)rc=i;if(c~/evidence|证据/)ec=i} if(rc&&tolower($2)!~/test|测试|result|结果/)st=1} next}
       st==1{if($0~/^[| :-]+$/)next; c=$2;gsub(/^ +| +$/,"",c); r=$rc;gsub(/^ +| +$/,"",r); e=(ec?$ec:""); gsub(/^ +| +$/,"",e); print c "\t" r "\t" e}' "$1"
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
    while IFS=$'\t' read -r c r e; do
      if echo "$c" | grep -qxE '[a-z]+-[0-9]{5}-(FR|BR|QR|AC|QS)-[0-9.]+'; then
        echo "$items" | grep -qx "$c" || { err "$rf: checklist row $c is not a declared item"; continue; }
        case "$c" in *-AC-*|*-QS-*) ;; *) err "$rf: checklist row $c names an item, not an AC / QS — verification is per AC / QS"; continue ;; esac
        listed="$listed $c"
        case "$c" in *-QS-*) case "$(qs_method "$c")" in load|chaos|observe) [ -n "$e" ] || err "$rf: $c is $(qs_method "$c") — its Evidence cell is empty" ;; esac ;; esac
        [ "$r" = pass ] && passed="$passed $c" || err "$rf: $c result '$r'"
      elif echo "$c" | grep -qE '[a-z]+-[0-9]{5}-(FR|BR|QR|AC|QS)-'; then
        err "$rf: malformed checklist row '${c:0:50}' — exactly one id per row"
      fi
    done < <(rows_of "$rf")
    want=$(for i in $(fld "$rf" verifies); do acs_of "$i"; done | sort -u)
    for a in $want; do echo " $listed " | grep -q " $a " || err "$rf: verifies covers $a but the checklist has no row for it"; done
    for a in $(echo "$listed" | tr ' ' '\n' | sort -u); do echo "$want" | grep -qx "$a" || err "$rf: checklist row $a is not covered by verifies"; done
  done
  for a in $scope; do
    echo " $passed " | grep -q " $a " || err "$pf: resolved, but $a has no pass row in an active record with parent $plan"
    case "$a" in *-QS-*) [ "$(qs_stage "$a")" = runtime ] && ! operated "$a" && err "$pf: resolved, but runtime $a has no active operation doc implementing it" ;; esac
  done
done

# 2f. active quality doc (docs/quality/README.md, QUALITY.md): a build-stage QS is verified by the tests in
#     `enforced_by` — required; a runtime QS with no operation doc yet is a warning here and an error in 2e
#     once a plan carrying it turns resolved.
for f in $(echo "$inst" | grep '^docs/quality/'); do
  grep -q '^status: active' "$f" || continue
  if echo "$qsmeta" | awk -v f="$f" '$4==f && $3=="build"' | grep -q . && [ -z "$(fld "$f" enforced_by | tr -d ' ')" ]; then
    err "$f: has a build-stage QS but no enforced_by"
  fi
  for q in $(echo "$qsmeta" | awk -v f="$f" '$4==f && $3=="runtime"{print $1}'); do
    operated "$q" || echo "  warn: $f: runtime $q has no operation doc implementing it yet"
  done
done

# 2g. profile n/a cells (QUALITY_PROFILE.md Rules, no-runtime exception): a quality doc whose §2 table holds
#     an `n/a` cell needs every catalogue dimension as a row, no `n/a` on a mandatory dimension, an `active`
#     decision motivated by that doc cited in every `n/a` cell, and no runtime QS anywhere in the repo.
dims=$(grep -aoE '^\| `[a-z-]+` \|' QUALITY_PROFILE.md 2>/dev/null | tr -d '`| ')
for f in $(echo "$inst" | grep '^docs/quality/'); do
  [ "$(grep -m1 '^status: ' "$f" | cut -d' ' -f2)" = archived ] && continue
  na=$(awk 'BEGIN{FS="|"} /^\| `[a-z-]+` \|/{d=$2;gsub(/[` ]/,"",d); v=$3;gsub(/^ +| +$/,"",v); if(tolower(v)~/^n\/a/)print d "\t" $0}' "$f")
  [ -n "$na" ] || continue
  id=$(grep -m1 '^id: ' "$f" | cut -d' ' -f2)
  echo "$qsmeta" | awk '$3=="runtime"' | grep -q . && err "$f: profile has n/a cells but a runtime QS exists in the repo (no-runtime exception void)"
  for d in $dims; do grep -qE "^\| \`$d\` \|" "$f" || err "$f: profile row \`$d\` missing (every catalogue dimension needs a row)"; done
  while IFS=$'\t' read -r d row; do
    case "$d" in maturity|team-shape|integration-surface|portability|harm) err "$f: profile \`$d\` is n/a — mandatory dimension" ;; esac
    dec=$(echo "$row" | grep -oE 'decision-[0-9]{5}-[a-z0-9-]+' | head -1)
    if [ -z "$dec" ]; then err "$f: profile \`$d\` n/a cites no decision"; continue; fi
    df=$(echo "$inst" | grep -m1 "^docs/decision/$dec\.md$")
    if [ -z "$df" ]; then err "$f: profile \`$d\` cites $dec, not found"; continue; fi
    [ "$(grep -m1 '^status: ' "$df" | cut -d' ' -f2)" = active ] || err "$f: profile \`$d\` cites $dec, not active"
    echo " $(fld "$df" motivated_by) " | grep -q " $id " || err "$f: profile \`$d\` cites $dec, whose motivated_by does not name $id"
  done <<EOF2
$na
EOF2
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

# 4. enforced_by (decision and quality docs)
for f in $(echo "$inst" | grep -E '^docs/(decision|quality)/'); do
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
