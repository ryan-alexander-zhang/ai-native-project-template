#!/bin/sh
# Propagate the docs-system skeleton from main into every lang/* branch.
#
# The skeleton (see .template-sync.json) is owned by main. This script folds
# main's latest into each lang branch with a merge (never a rebase): lang
# branches are long-lived and published — `ainpt new --lang X` records their
# commit SHA in .ainpt.json, and rebasing would rewrite that history and break
# `ainpt update`. Because lang branches never edit frozen files, these merges
# do not conflict on the skeleton.
#
# Usage:
#   scripts/sync-docs.sh            # sync all origin/lang/* branches
#   scripts/sync-docs.sh lang/java  # sync specific branch(es)
#   PUSH=1 scripts/sync-docs.sh     # also push each updated branch
set -eu

git fetch origin --quiet

if [ "$#" -gt 0 ]; then
  branches="$*"
else
  branches=$(git branch -r | sed -n 's#[[:space:]]*origin/\(lang/.*\)#\1#p')
fi

if [ -z "$branches" ]; then
  echo "No lang/* branches found."
  exit 0
fi

start=$(git rev-parse --abbrev-ref HEAD)
for b in $branches; do
  echo "==> $b"
  git switch "$b" >/dev/null 2>&1 || git switch -c "$b" --track "origin/$b"
  if git merge --no-edit origin/main; then
    [ "${PUSH:-0}" = "1" ] && git push origin "$b"
  else
    echo "!! merge conflict on $b — resolve, commit, then re-run (skipping the rest)."
    exit 1
  fi
done

git switch "$start" >/dev/null 2>&1 || true
echo "Done."
