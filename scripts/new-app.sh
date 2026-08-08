#!/bin/sh
# Turn a freshly scaffolded checkout of this template into the consumer's project.
#
# The multi-module reference project under aipersimmon-ddd-scaffold/ is a Maven
# archetype *source*, not something a consumer keeps. This script generates the
# application from that archetype into app/ and then deletes the scaffold, so what
# remains is the building-block library, the samples, and one real application.
#
# template.json's post_create runs this for the `ddd` variant with the values ainpt
# collected. Running it by hand is the same call:
#
#   APP_GROUP_ID=com.acme APP_ARTIFACT_ID=my-ddd APP_VERSION=0.0.1-SNAPSHOT \
#   APP_PACKAGE=com.acme.myddd ARCHETYPE_SOURCE=local ARCHETYPE_VERSION=0.1.0-SNAPSHOT \
#   sh scripts/new-app.sh
#
# ARCHETYPE_SOURCE picks where the archetype comes from:
#   local     — derive it from the bundled scaffold. No external repository, and the
#               archetype cannot drift from the scaffold, but it has to build the
#               library first (see below), which is by far the slowest step.
#   published — take it from a repository it was deployed to. Seconds instead of
#               minutes, at the cost of needing credentials for that repository and
#               a version that actually matches this branch.
set -eu

: "${APP_GROUP_ID:?set APP_GROUP_ID (e.g. com.acme)}"
: "${APP_ARTIFACT_ID:?set APP_ARTIFACT_ID (e.g. my-ddd)}"
: "${APP_VERSION:?set APP_VERSION (e.g. 0.0.1-SNAPSHOT)}"
: "${APP_PACKAGE:?set APP_PACKAGE (e.g. com.acme.myddd)}"
: "${ARCHETYPE_SOURCE:?set ARCHETYPE_SOURCE to local or published}"
: "${ARCHETYPE_VERSION:?set ARCHETYPE_VERSION (e.g. 0.1.0-SNAPSHOT)}"
ARCHETYPE_REPOSITORY="${ARCHETYPE_REPOSITORY:-}"

ARCHETYPE_GROUP_ID=com.ryan.persimmon
ARCHETYPE_ARTIFACT_ID=persimmon-scaffold-archetype
SCAFFOLD=aipersimmon-ddd-scaffold/multi-module

# The realistic mistake is deriving the package from a hyphenated artifactId. Catch
# it here: Maven accepts it and the generated sources then fail to compile, which is
# a much worse place to find out.
case "$APP_PACKAGE" in
  *[!a-zA-Z0-9._]* | .* | *. | *..*)
    echo "APP_PACKAGE '$APP_PACKAGE' is not a legal Java package" >&2
    exit 1
    ;;
esac

[ -d aipersimmon-ddd-scaffold ] || {
  echo "no aipersimmon-ddd-scaffold/ here — run this from the project root, once" >&2
  exit 1
}

repository_arg=''
case "$ARCHETYPE_SOURCE" in
  local)
    # The scaffold's root pom imports aipersimmon-ddd-bom with <scope>import</scope>,
    # and an import BOM that cannot be resolved fails Maven's model building before
    # any goal runs. So the library must reach the local repository before the
    # archetype can be derived at all — that, and nothing else, is why a local
    # derivation pays for a full library build.
    mvn -B --no-transfer-progress -q -f aipersimmon-ddd/pom.xml install -DskipTests

    # The scaffold is the source of truth and the archetype is derived from it;
    # deriving right here is what guarantees the two cannot drift.
    (
      cd "$SCAFFOLD"
      mvn -B --no-transfer-progress -q archetype:create-from-project \
        -Darchetype.properties=./archetype.properties
      mvn -B --no-transfer-progress -q \
        -f target/generated-sources/archetype/pom.xml install
    )
    ;;
  published)
    if [ -n "$ARCHETYPE_REPOSITORY" ]; then
      repository_arg="-DarchetypeRepository=$ARCHETYPE_REPOSITORY"
    fi
    ;;
  *)
    echo "ARCHETYPE_SOURCE must be 'local' or 'published', got '$ARCHETYPE_SOURCE'" >&2
    exit 1
    ;;
esac

# archetype:generate always writes <artifactId>/ under its output directory, and the
# name we want is app/. Stage inside the project so the move is never cross-device,
# and clean up on any exit so a failed generation leaves no debris.
staging=$(mktemp -d "$PWD/.new-app.XXXXXX")
trap 'rm -rf "$staging"' EXIT

# shellcheck disable=SC2086 # repository_arg is one optional flag or nothing
mvn -B --no-transfer-progress -q archetype:generate -DinteractiveMode=false \
  -DarchetypeGroupId="$ARCHETYPE_GROUP_ID" \
  -DarchetypeArtifactId="$ARCHETYPE_ARTIFACT_ID" \
  -DarchetypeVersion="$ARCHETYPE_VERSION" \
  $repository_arg \
  -DgroupId="$APP_GROUP_ID" \
  -DartifactId="$APP_ARTIFACT_ID" \
  -Dversion="$APP_VERSION" \
  -Dpackage="$APP_PACKAGE" \
  -DoutputDirectory="$staging"

mv "$staging/$APP_ARTIFACT_ID" app
rm -rf aipersimmon-ddd-scaffold

# Nothing under .github/ reaches a generated project — CI, releasing and the docs-system
# gate are all the template repository's own business, and template.json's `exclude` drops
# the directory whole before this script runs. So there is no workflow here to delete and
# none to repoint: a consumer writes their own pipeline against app/. Declared in
# template.json rather than deleted here because it has nothing to do with generating the
# app, and because `exclude` covers every variant while this script only runs for `ddd`.
#
# What is left pointing at the path we just deleted is prose. Repoint it at app/ so the
# project that lands does not describe a directory it does not have. Only the paths are
# rewritten — surrounding wording still calls it a reference project, which it no longer is.
for f in ARCHITECTURE.md README.md; do
  if [ -f "$f" ]; then
    sed -e 's#aipersimmon-ddd-scaffold/multi-module#app#g' \
        -e 's#aipersimmon-ddd-scaffold/#app/#g' \
        -e 's#aipersimmon-ddd-scaffold#app#g' "$f" > "$f.new"
    mv "$f.new" "$f"
  fi
done

# The library names the template's own GitHub Packages repository as its `mvn deploy`
# target, and every module inherits it — the BOM writes it out a second time because it
# deliberately has no parent. Left alone, a consumer's deploy aims their copy of the
# library at someone else's registry. It would fail on authentication rather than do
# damage, but the coordinates are simply wrong, and nothing in the generated project says
# so.
#
# Replaced with a placeholder rather than deleted, and that is not cosmetic:
# BomExportsOnlyItsOwnModulesTest asserts the BOM declares a distributionManagement AND
# that it equals the parent's, so deleting the two blocks turns the consumer's very first
# `mvn verify` red. A green build that refuses to deploy is the better of the two
# failures. OWNER/REPO cannot publish anywhere by accident either — no token grants write
# access to it.
#
# The comment above the block is edited in the same pass, one line at a time so the
# replacements carry no newlines: `sed` is the only tool here that has to run on both
# BSD and GNU, and multi-line replacement text is spelled differently on each.
TEMPLATE_REPOSITORY=https://maven.pkg.github.com/ryan-alexander-zhang/ai-native-project-template
for pom in aipersimmon-ddd/pom.xml aipersimmon-ddd/aipersimmon-ddd-bom/pom.xml; do
  sed -e "s#$TEMPLATE_REPOSITORY#https://maven.pkg.github.com/OWNER/REPO#g" \
      -e 's#Publishing target for `mvn deploy` (see the publish-library CI workflow). The#Publishing target for `mvn deploy`; the URL below is a placeholder, replace it. The#' \
      -e 's#repository id `github` matches the server the workflow configures in settings.xml#repository id `github` matches a server your own CI configures in settings.xml#' \
      "$pom" > "$pom.new"
  mv "$pom.new" "$pom"

  # Both directions are checked, because both failures are silent. A missed rewrite ships
  # the template's registry; a rewrite that ate the element ships a library whose own
  # test suite fails on the consumer's first build.
  if grep -q ai-native-project-template "$pom"; then
    echo "$pom still names the template repository — has its format drifted?" >&2
    exit 1
  fi
  if ! grep -q '<distributionManagement>' "$pom"; then
    echo "$pom lost its distributionManagement — BomExportsOnlyItsOwnModulesTest will fail" >&2
    exit 1
  fi
done

echo "app/ is $APP_GROUP_ID:$APP_ARTIFACT_ID ($APP_PACKAGE); the archetype source is gone."
