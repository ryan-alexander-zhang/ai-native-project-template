#!/bin/sh
# Release the Java DDD stack: the building-block library and the scaffold archetype.
#
# Both are published by GitHub Actions, and the only thing that triggers them is a tag
# matching lang/java/ddd/v* arriving at the remote. So this script's job is to produce
# exactly that tag, on a tree whose 44 library poms — and the three files outside that
# reactor which name its version — carry the released version, and to stop.
#
# It does not push by default. Everything up to that point is local and undoable — the
# script prints how — while a pushed tag deploys and cannot be recalled. PUSH=1 opts in,
# the same way scripts/sync-docs.sh gates its push.
#
# The one job genuinely worth automating here is the tag name. Everything downstream
# hangs on it matching lang/java/ddd/v*, and a tag that does not match is created
# perfectly happily and triggers nothing at all — no error, no workflow run, no clue.
# Building it from the version is the difference between a typo you find in ten seconds
# and one you find after twenty minutes of reading Actions logs that do not exist.
#
# Usage:
#   scripts/release.sh 0.1.0                         # bump, commit, tag. Nothing leaves the machine
#   scripts/release.sh 0.1.0 0.2.0-SNAPSHOT          # ...and reopen development
#   PUSH=1 scripts/release.sh 0.1.0 0.2.0-SNAPSHOT   # ...and push, which is what deploys
#
# See docs/operation/operation-00001-releasing-the-java-ddd-stack.md.
set -eu

BRANCH=lang/java/ddd
REACTOR=aipersimmon-ddd/pom.xml
PUSH="${PUSH:-0}"

release_version="${1:-}"
next_version="${2:-}"

if [ -z "$release_version" ]; then
  echo "usage: scripts/release.sh <release-version> [next-development-version]" >&2
  echo "   eg: scripts/release.sh 0.1.0 0.2.0-SNAPSHOT" >&2
  exit 1
fi

tag="$BRANCH/v$release_version"

# ---------------------------------------------------------------- preflight

[ -f "$REACTOR" ] || {
  echo "no $REACTOR here — run this from the repository root" >&2
  exit 1
}

# The tag prefix is this branch's name. Releasing from anywhere else would mint a tag
# that claims to be this stack's and points at a tree that is not.
current=$(git rev-parse --abbrev-ref HEAD)
[ "$current" = "$BRANCH" ] || {
  echo "on '$current', but this script releases '$BRANCH'" >&2
  exit 1
}

# A release commit must contain the version bump and nothing else, so that what the tag
# points at is reviewable as one thing.
[ -z "$(git status --porcelain)" ] || {
  echo "working tree is not clean — commit or stash first:" >&2
  git status --short >&2
  exit 1
}

case "$release_version" in
  *SNAPSHOT*)
    echo "'$release_version' is a snapshot; a released artifact must not be one" >&2
    echo "(a snapshot can be overwritten by a later build, so the tag would stop" >&2
    echo " describing what a consumer actually resolves)" >&2
    exit 1
    ;;
esac

if [ -n "$next_version" ]; then
  case "$next_version" in
    *SNAPSHOT*) ;;
    *)
      echo "'$next_version' is the next DEVELOPMENT version and must be a -SNAPSHOT" >&2
      exit 1
      ;;
  esac
fi

# Re-tagging is how a released version silently changes meaning underneath whoever
# already consumed it.
if git rev-parse -q --verify "refs/tags/$tag" >/dev/null; then
  echo "tag '$tag' already exists locally" >&2
  exit 1
fi
if git ls-remote --exit-code --tags origin "$tag" >/dev/null 2>&1; then
  echo "tag '$tag' already exists on origin" >&2
  exit 1
fi

undo_to=$(git rev-parse HEAD)

echo "Releasing $release_version as $tag"
echo

# The version lives in four places, and only the first is a Maven version. The other three
# are consumers of the library that sit OUTSIDE its reactor, so versions:set never sees
# them; each one has its own way of going wrong, and all three go wrong silently.
#
# Only the reactor is rewritten by Maven. The other three are rewritten as TEXT, and that
# is a correctness requirement rather than a shortcut — see the scaffold/samples note below.
#
# Rewrites go through a temporary file rather than `sed -i`, whose in-place flag takes a
# mandatory backup suffix on BSD/macOS and rejects one on GNU: there is no spelling that
# works on both, and this script runs on whichever machine cut the release.
rewrite() {
  file="$1"
  script="$2"
  rewritten=$(mktemp)
  sed "$script" "$file" >"$rewritten"
  mv "$rewritten" "$file"
  grep -q "$version" "$file" || {
    echo "rewriting $file to $version changed nothing — has its format drifted?" >&2
    exit 1
  }
}

set_version() {
  version="$1"

  # -DprocessAllModules is what reaches aipersimmon-ddd-bom. By default versions:set walks
  # the reactor by PARENT link, and the BOM deliberately has none (it would leak the
  # parent's 1600-odd managed pins to everyone importing it), so it declares its own
  # version and the default walk steps straight past it. This is what broke 0.1.0: 43 poms
  # took the release version, the BOM kept 0.1.0-SNAPSHOT, and because its entries all say
  # ${project.version} the BOM then aligned consumers onto a version of every module that
  # was never built. It took down both publish workflows — library `deploy` and archetype
  # derivation, which cannot resolve an import BOM that is not there.
  # BomExportsOnlyItsOwnModulesTest asserts the BOM and the parent agree.
  mvn -B --no-transfer-progress -q -f "$REACTOR" versions:set -DnewVersion="$version" -DprocessAllModules=true
  # Drop the .versionsBackup files: past this point the undo is git, not the plugin.
  mvn -B --no-transfer-progress -q -f "$REACTOR" versions:commit -DprocessAllModules=true

  # The scaffold and the samples IMPORT the library's BOM, so they name its version in a
  # property rather than carrying it as their own. Left behind, they keep resolving the
  # previous version: fine while it is still in ~/.m2 from an earlier build, which is
  # exactly why it survives locally and fails on a clean runner.
  #
  # Rewritten with sed and NOT with versions:set-property, which cannot do the job here.
  # Any Maven goal has to build the project model first, and building this one means
  # RESOLVING the very BOM whose version is about to change. Reopening development is
  # precisely when that import cannot resolve: the poms still name the version just
  # released, which by definition has not been built or installed anywhere. So the plugin
  # dies reading the file it was invoked to edit — "Non-resolvable import POM ... (absent)"
  # — after the release commit and tag already exist. sed does not care what resolves.
  for consumer in aipersimmon-ddd-scaffold/multi-module/pom.xml aipersimmon-ddd-samples/pom.xml; do
    rewrite "$consumer" \
      "s|<aipersimmon-ddd\.version>[^<]*</aipersimmon-ddd\.version>|<aipersimmon-ddd.version>$version</aipersimmon-ddd.version>|"
  done

  # Not a pom at all: the archetype derivation reads this file for the version to stamp on
  # the archetype it publishes. The publish-archetype workflow overwrites it in the
  # workspace at tag time, so a stale value here never fails a release — it just makes the
  # file disagree with every other one, for the next reader to puzzle over.
  rewrite aipersimmon-ddd-scaffold/multi-module/archetype.properties \
    "s/^archetype\.version=.*/archetype.version=$version/"
}

# ------------------------------------------------------------------ release

echo "==> setting the library reactor to $release_version"
set_version "$release_version"

changed=$(git diff --name-only | grep -c 'pom\.xml$' || true)
[ "$changed" -gt 0 ] || {
  echo "versions:set changed no poms — is the reactor already at $release_version?" >&2
  exit 1
}
echo "    $changed poms rewritten"

git commit -q -am "release $release_version"
# Annotated, with a message, and not by preference: this repository's git is configured
# with tag.gpgsign=true, which makes every tag annotated and signed — and a bare
# `git tag <name>` then dies with "fatal: no tag message?". Discovered by running it. The
# failure would land at the worst possible moment, after the bump and the release commit,
# leaving a committed version with no tag to publish it.
git tag -m "release $release_version" "$tag"
git rev-parse -q --verify "refs/tags/$tag" >/dev/null || {
  echo "tagging reported success but '$tag' does not exist" >&2
  exit 1
}
echo "==> committed and tagged $tag"

# ------------------------------------------------- reopen development (opt.)

if [ -n "$next_version" ]; then
  echo "==> reopening development at $next_version"
  set_version "$next_version"
  git commit -q -am "back to development"
fi

# --------------------------------------------------------------------- push

echo
if [ "$PUSH" = "1" ]; then
  echo "==> pushing (this triggers the publish workflows)"
  git push --follow-tags
  echo
  echo "Watch them: gh run list --limit 5"
  echo "If neither 'Publish library' nor 'Publish archetype' appears, the tag did not"
  echo "match lang/java/ddd/v* — check: git tag -l 'lang/java/ddd/*'"
else
  echo "Nothing has been pushed. Review, then:"
  echo
  echo "    git log --oneline $undo_to..HEAD"
  echo "    git show $tag --stat"
  echo "    git push --follow-tags        # this is what deploys"
  echo
  echo "Or undo everything this script just did:"
  echo
  echo "    git tag -d $tag && git reset --hard $undo_to"
fi
