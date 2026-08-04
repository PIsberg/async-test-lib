---
name: adddetector
description: Scaffold a new async-test-lib concurrency detector from just its name and wire it in completely. Use when the user runs /adddetector <Name>, or asks to add / create / scaffold a new detector. Generates the detector + test, performs every synchronized wiring edit (DetectorType, AsyncTest, AsyncTestConfig, DetectorRegistry, LegacyDetectorFactories, the built-in factory list), updates docs, and verifies with the build.
---

# Add a detector

Adding a detector to async-test-lib is a **synchronized change across ~9 files**. A field
without its construction, or an enum constant without a factory, *silently disables detection*
or *fails a wiring test* — never a compile error you'd notice by eye. This skill does the whole
set atomically so nothing is left half-wired.

> Reverse-engineered from the canonical full-wiring commit `6beea29` (Phase 18). If the source
> has drifted from what's described here, trust the source: locate each anchor by the **last
> existing detector**, not by line number.

## Input → names

`$ARGUMENTS` is the detector name. Strip a trailing `Detector` if the user typed it, and
normalise separators, to get the **PascalCase base** (e.g. `SharedFoo`).

From the base, derive every identifier (worked example: base = `SharedFoo`):

| Role | Rule | Example |
|------|------|---------|
| Detector class | base + `Detector` | `SharedFooDetector` |
| Main file | `src/main/java/se/deversity/asynctest/diagnostics/<class>.java` | … |
| Test file | `src/test/java/se/deversity/asynctest/diagnostics/<class>Test.java` | … |
| **`{{CONSTANT}}`** enum | PascalCase → SCREAMING_SNAKE: regex `([a-z0-9])([A-Z])` → `$1_$2`, then upper | `SHARED_FOO` |
| **`{{FLAG}}`** config | `detect` + base | `detectSharedFoo` |
| **`{{FIELD}}`** registry | base with lower first char + `Detector` | `sharedFooDetector` |
| **`{{FACTORY}}`** inner class | = base | `SharedFoo` |

SCREAMING_SNAKE examples to sanity-check your transform: `HttpClientConcurrency` →
`HTTP_CLIENT_CONCURRENCY`, `JdbcConnectionShared` → `JDBC_CONNECTION_SHARED`.

Before doing anything, **verify the name is free**: grep the enum and the diagnostics dir. If
`{{CONSTANT}}` or the class already exists, stop and tell the user.

```bash
grep -n "{{CONSTANT}}" src/main/java/se/deversity/asynctest/DetectorType.java
ls src/main/java/se/deversity/asynctest/diagnostics/ | grep -i "<base>"
```

Also read the current version once — `@since` uses the pom version with any `-RCx`/`-SNAPSHOT`
suffix removed (today `1.7.0-RC8` → `1.7.0`):

```bash
grep -m1 '<version>' pom.xml
```

## Guardrail note (must relay to the user)

`DetectorType.java` is in CLAUDE.md `<locked_files>` — normally never edited. The lock exists to
stop *isolated* edits that break the enum↔flag↔registry↔factory mapping. This skill edits it
**only as one part of the complete synchronized set**, which is exactly what the lock protects.
Call this out when you run: "editing the locked DetectorType.java as part of the atomic wiring."

`DetectorRegistry.java`, `AsyncTestConfig.java`, and `AsyncTestContext.java` are also
Critical/audit-listed. Preserve their invariants: `AsyncTestConfig` stays immutable (all fields
`final`, no setters); `DetectorRegistry` construction stays keyed on the config flag; any
`AsyncTestContext` accessor you add must keep ThreadLocal install/uninstall symmetric.

---

## The edits

Do all of these in one pass. `<base>`, `{{CLASS}}`, `{{CONSTANT}}`, `{{FLAG}}`, `{{FIELD}}`,
`{{FACTORY}}`, `{{VERSION}}` are the values from the table above.

### 1. Detector class *(new file)*
Copy `templates/Detector.java.tmpl` (in this skill dir) to the main file, substituting
`{{CLASS}}`, `{{FACTORY}}`, `{{VERSION}}`. The template is a generic multi-thread-access stub —
its `analyze()` rule is a **placeholder marked TODO**. This skill wires the plumbing; the *actual
detection logic is out of scope* and left for the user (or a follow-up prompt) to fill in.
Keep the SPI-facing shape intact: a public no-arg **`analyze()`** returning a nested **`Report`**
with a public **`hasIssues()`** — `LegacyDetectorAdapter` finds both by reflection.

### 2. Test *(new file)*
Copy `templates/DetectorTest.java.tmpl` to the test file, substituting `{{CLASS}}`. Required, not
optional: every diagnostics detector is in CLAUDE.md `<test_driven_requirements>` (80% coverage,
JUNIT_5) — an implementation without its test is incomplete. When the user later replaces the
placeholder rule, the tests must be updated in the same change.

### 3. `DetectorType.java` — enum constant  ⚠️ locked file, atomic edit
The last constant has **no trailing comma**. Add a comma to it, then append the new constant.

```diff
     FINAL_FIELD_MUTATION,
-    SHARED_KDF
+    SHARED_KDF,
+    {{CONSTANT}}
 }
```
(Anchor: the last constant before the closing `}` — currently `SHARED_KDF`.)

### 4. `AsyncTest.java` — deprecated boolean attribute
Append after the last `detectXxx()` attribute (currently `detectSharedKdf()`). Match the exact
shape, including the blank line after `@Deprecated`:

```java
    /**
     * Enable {{CONSTANT}} detection. See
     * {@link se.deversity.asynctest.diagnostics.{{CLASS}}}.
     * @since {{VERSION}}
     *
     * @deprecated Prefer {@link #preset()}, {@link #includes()}, or {@link #excludes()}
     *     with {@link DetectorType#{{CONSTANT}}} instead of this per-detector boolean flag.
     */
    @Deprecated

    boolean {{FLAG}}() default true;
```

### 5. `AsyncTestConfig.java` — **six** edits (immutable class; keep 1:1 mapping)
Anchor every one after the current-last detector's line (`detectSharedKdf`). The columns are
alignment-padded — copy the surrounding spacing.

1. **Public final field** (field block, ~L216):
   ```java
   public final boolean {{FLAG}};
   ```
2. **Constructor assignment from builder** (~L371):
   ```java
   {{FLAG}} = b.{{FLAG}};
   ```
3. **`from(AsyncTest ann)` chain** (~L561) — append to the builder call chain:
   ```java
   .{{FLAG}}(ann.{{FLAG}}())
   ```
4. **Builder default** (~L717) — defaults are `false`; the resolver flips them on:
   ```java
   private boolean {{FLAG}} = false;
   ```
5. **Builder setter** (~L861):
   ```java
   public Builder {{FLAG}}(boolean v) { {{FLAG}} = v; return this; }
   ```
6. **`build()` — both branches** of the detectAll/excludes resolution:
   - In the `if (detectAll) { … }` block (~L1159), two lines:
     ```java
     if (!excludes.contains(DetectorType.{{CONSTANT}})) {{FLAG}} = true;
         else {{FLAG}} = false;
     ```
   - In the `else { … }` excludes block (~L1293), one line:
     ```java
     if (excludes.contains(DetectorType.{{CONSTANT}})) {{FLAG}} = false;
     ```
   > `includes` / `preset` resolution is generic (it iterates `DetectorType.values()`), so it
   > needs no per-detector edit — the enum constant from step 3 is enough for those paths.

### 6. `DetectorRegistry.java` — **four** edits (root package: `se.deversity.asynctest`)
This is the class that actually runs detectors. All four are required — a field without
construction, or construction without an `analyzeAll` call, silently skips detection.

1. **Import** (with the other `diagnostics.*` imports, ~L126):
   ```java
   import se.deversity.asynctest.diagnostics.{{CLASS}};
   ```
2. **Field** (field block, ~L320):
   ```java
   final {{CLASS}}                     {{FIELD}};
   ```
3. **Constructor construction**, keyed on the flag (~L500):
   ```java
   {{FIELD}}                = cfg.{{FLAG}}                ? new {{CLASS}}()                : null;
   ```
4. **`analyzeAll()` phase call** — append at the end of the last phase block (~L934), before
   `return out;`:
   ```java
   ifIssue({{FIELD}},
           {{CLASS}}::analyze,
           {{CLASS}}.Report::hasIssues, out);
   ```

### 7. `LegacyDetectorFactories.java` — SPI factory
1. **Import** with the other detector imports:
   ```java
   import se.deversity.asynctest.diagnostics.{{CLASS}};
   ```
2. **Factory inner class**, appended before the final closing `}` of the outer class:
   ```java
   public static final class {{FACTORY}} implements DetectorFactory {
       @Override public DetectorType type() { return DetectorType.{{CONSTANT}}; }
       @Override public boolean isEnabledFor(AsyncTestConfig c) { return c.{{FLAG}}; }
       @Override public Detector create(AsyncTestConfig c) {
           return new LegacyDetectorAdapter<>(new {{CLASS}}(), DetectorType.{{CONSTANT}}, "{{FACTORY}}");
       }
   }
   ```

### 8. `META-INF/async-test/builtin-detector-factories`
Append the fully-qualified nested factory name (note the `$`). This is deliberately not a
`META-INF/services` file: ServiceLoader must load a provider class to read its type, and built-ins
are addressability shims the runtime skips, so listing them for discovery cost ~340 ms per forked
JVM. `AllDetectorsSpiCoverageTest` fails if you forget this line.
```
se.deversity.asynctest.spi.adapters.LegacyDetectorFactories${{FACTORY}}
```

### 9. Docs (increment counts + catalog entry)
- `docs/DETECTOR_CATALOG.md` — add a short entry in the right category; bump the "N detectors"
  total near the top.
- `docs/CHANGELOG.md` — add a line under the current version.
- `README.md` — bump the detector-count mentions.
  > Heads-up: the counts in README/catalog are already inconsistent with the true enum count.
  > Report the current `DetectorType.values().length` and let the user reconcile — don't invent a
  > number.

### 10. Optional: `AsyncTestContext.java` accessor
Only if the user wants the `AsyncTestContext.{{FIELD}}()` convenience accessor (some detectors
expose one, e.g. `sharedKdfDetector()`). It's not required for the detector to run via the SPI.
`AsyncTestContext` is audit-listed for **thread safety** — if you add an accessor, keep
ThreadLocal install/uninstall symmetric and match the existing accessor pattern exactly. Skip by
default; mention it as available.

---

## Verify

The wiring tests are the safety net — they fail loudly on any missed step:

```bash
mvn -q -Dlicense.mock.mode=true \
  -Dtest='AllDetectorsSpiCoverageTest,DetectorRegistrySpiTest,AsyncTestConfigBuildResolutionTest,{{CLASS}}Test' \
  test
```

- `AllDetectorsSpiCoverageTest#everyDetectorTypeHasARegisteredFactory` → catches a missing
  factory or services line (steps 7–8).
- `DetectorRegistrySpiTest` / SPI instantiation → catches enum↔factory gaps.
- `AsyncTestConfigBuildResolutionTest` → catches a missing config flag / build-block line.
- `{{CLASS}}Test` → the new detector's own tests.

Then a full compile to be sure nothing else drifted:
```bash
mvn -q -Dlicense.mock.mode=true test-compile
```

Report results honestly. If a wiring test fails, the message names what's missing — fix that step,
don't loosen the test.

## Finish

Summarise: the detector name and all derived identifiers, the files touched, that the
**detection rule is a TODO stub** (with a pointer to `analyze()`), whether the optional
`AsyncTestContext` accessor was added, and the verification outcome. Do **not** commit unless the
user asks.
