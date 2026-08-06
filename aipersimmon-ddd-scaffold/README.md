# aipersimmon-ddd-scaffold — the archetype source

`multi-module/` is a hand-written, runnable reference project built on the AiPersimmon DDD building
blocks. It is also the **single source of truth** for the Maven archetype consumers generate from.

The archetype is a *derived* artifact: `mvn archetype:create-from-project` reads `multi-module/`
and writes a complete archetype project into `multi-module/target/generated-sources/archetype`.
It is never hand-edited and never committed — to change what consumers get, change
`multi-module/` and re-derive.

```
multi-module/  ──create-from-project──▶  persimmon-scaffold-archetype  ──generate──▶  new project
   (truth)                                  (derived, in target/)
```

## Deriving, installing, and verifying

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)

# 0. The library must be in ~/.m2 — a generated project resolves aipersimmon-ddd-* from there.
mvn -f ../aipersimmon-ddd/pom.xml install -DskipTests

cd multi-module

# 1. Derive. archetype.properties supplies the derived artifact's coordinates and excludePatterns.
mvn archetype:create-from-project -Darchetype.properties=./archetype.properties

# 2. Install the derived archetype.
mvn -f target/generated-sources/archetype/pom.xml install

# 3. Generate a project from it, somewhere outside this repository.
mvn archetype:generate \
  -DarchetypeGroupId=com.ryan.persimmon \
  -DarchetypeArtifactId=persimmon-scaffold-archetype \
  -DarchetypeVersion=0.0.1-SNAPSHOT \
  -DgroupId=com.acme -DartifactId=shop -Dversion=1.0.0-SNAPSHOT -Dpackage=com.acme.shop \
  -DinteractiveMode=false

# 4. Verify. Both checks matter, and neither substitutes for the other.
grep -rn 'com\.example' shop/          # must find nothing: no leaked authoring identity
cd shop && mvn test                    # must be green; needs Docker (Testcontainers)
```

Step 4's `mvn test` is the real gate. A generated project is a different tree at a different path
under a different package, and things that hold only in this repository fail exactly there —
see the third rule below, which is how one such failure was found.

## Rules the source must keep, or the derivation breaks

These are not style preferences; each one corresponds to a way `create-from-project` produces a
broken or misleading result.

1. **Every bounded-context directory is an aggregator pom.** `create-from-project` cannot represent
   a pom-less grouping directory — the nesting in the derived `archetype-metadata.xml` comes out
   wrong. `ordering/`, `inventory/` and `payment/` each carry a `packaging=pom` module.

2. **Internal module dependencies use `${project.groupId}` / `${project.version}`.** The tool
   templatizes the project's own GAV but *not* groupIds written inside `<dependency>` elements, so
   a literal `com.example` there would survive into generated projects and fail to resolve.

3. **Nothing that ships may depend on a file that exists only in this repository** — including
   markers a test looks for. `ShippedCommentsAreSelfContainedTest` used to locate the reactor root
   by `archetype.properties`, which is deliberately *not* carried into the archetype; the test
   errored in every generated project while staying green here. It now keys on `pom.xml` +
   `start/pom.xml`, which both trees have.

4. **No shipped filename may contain the root artifactId.** Path segments matching `multi-module`
   are rewritten to `__artifactId__` and expand to the consumer's artifactId at generation time,
   while references to them from unfiltered files (Markdown) keep the old literal name. That is how
   `docs/multi-module-event-storming.json` became `docs/shop-event-storming.json` with a dangling
   link from `docs/README.md`; it is now `docs/event-storming.json`.
   (Segments matching a *module's* artifactId are safe — module names are fixed by the archetype
   descriptor, so `ReadmeQuick__artifactId__Test.java` under `start/` round-trips to itself.)

5. **Keep `com.example` out of Markdown.** Only `.java`, `.xml` and `.properties` are
   Velocity-filtered; `.md`, `.yml`, `.sql` and `.json` are copied verbatim, so a literal package
   name in prose ships as-is. Say "this project's base package" instead. Do **not** add `md` to
   `archetype.filteredExtensions` to work around this: in Velocity a line starting with `##` is a
   comment, so every Markdown heading would be deleted.

## Round-trip check

Beyond `mvn test`, the strongest check is that a generated project is the source tree with only the
identity changed. Compare file lists (normalising the package path) and then contents:

```bash
diff <(cd multi-module && find . -type f -not -path '*/target/*' | sed 's#/com/example#/PKG#' | sort) \
     <(cd /path/to/shop && find . -type f -not -path '*/target/*' | sed 's#/com/acme/shop#/PKG#' | sort)
```

An empty diff is the expected result. Content differences should reduce to the GAV, the package,
and pom whitespace — `create-from-project` re-serialises poms, so indentation and element order in
`<modules>` change even where nothing else does.
