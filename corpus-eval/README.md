# corpus-eval

Measures what the 142 detectors report on 42 third-party classes with a documented
thread-safety contract, and how many of the 142 the run could feed at all. The write-up, with the
numbers and what they do and do not support, is
[docs/analysis/corpus-eval.md](../docs/analysis/corpus-eval.md).

## Why it is a standalone module

The seven corpus libraries are *subjects*, not tools. Keeping the module out of the reactor keeps
them off every classpath that matters: nothing in `async-test-lib`, the published artifacts or the
Gradle build resolves them, exactly as `consumer-fixture/` stays outside for its own reason.

## Running it

```bash
mvn install -DskipTests -Djacoco.skip=true    # so the module can resolve the current version
mvn -f corpus-eval/pom.xml test
```

A run executes the same subjects twice, and writes one report per lane under
`target/corpus-eval/`:

| Lane | Report | What it is |
|---|---|---|
| `agent-on` | `corpus-eval.md` | The agent attached as `fields=true,collections=true`. Every number the write-up quotes comes from here. |
| `agent-off` | `corpus-eval-agent-off.md` | The same subjects with nothing attached. The control: `DetectorFeeds` says the two agent-fed detectors have no input without the agent, so this lane must observe nothing from them, and `CorpusGates` asserts it. |

Those files are the source of truth for a given run; the document under `docs/` is a copy of one.

The attached lane uses `-javaagent:` at JVM startup rather than the library's self-attach, and
`CorpusGates` fails the run if that changes. Self-attach happens partway through the run and weaves
only what loads after it, so which subjects the agent can see depends on which test class Surefire
runs first. That ordering differs between a developer machine and a CI runner, and it moved this
eval from 20 of 20 documented-unsafe subjects detected to 6 of 20 with nothing else changed;
`-Dsurefire.runOrder=reversealphabetical` reproduces it. A launch flag takes the file order out of
the measurement.

## Exposure, and why the reports lead with it

A finding count with no denominator cannot be read. The corpus records nothing by hand, so 137 of
the 142 detectors are never fed here at all, and their zero means "never ran", not "looked and
found nothing". Each report therefore prints, before any rate: how many detectors the lane could
feed, split by `DetectorFeed`, and per exposed detector how many documented-safe and
documented-unsafe subjects it was exposed to. `CorpusGates` fails the run if a detector reports
that the feed table says cannot be fed, so those denominators are checked rather than asserted.

## Where the tests live

The subjects live in `com.example.corpus`, not under the library's own package root. That is
deliberate: `CollectionAccessWeaver` excludes `se.deversity.asynctest.` from collection
substitution to stop the recording path from re-entering itself, so a corpus in that namespace
would have measured a narrower path than a user's suite does.
`TestBodyCollectionIsObservedTest` pins the canonical case that depends on it: a `HashMap` shared
by the test body itself is reported. It runs in the attached lane only.

## Adding a subject

1. Find a class whose **own javadoc** states its thread-safety contract. An inferred contract is
   not ground truth and does not belong here. A class that states nothing is not a subject, however
   obvious its behaviour looks: that is why the corpus carries Netty's allocator and none of its
   buffers.
2. Add a `Subject` row to `Corpus.java` quoting that sentence, with the file and line in the
   library's sources jar. Unpack it **outside the repository**:
   `mvn dependency:copy -Dartifact=<ga>:<version>:jar:sources -DoutputDirectory=$TMPDIR/corpus-sources`.
   Third-party sources anywhere under the repo root end up in the architecture diagrams,
   because `tools/generate-architecture-diagrams.sh` scans `.` for the module picture, and
   the Guardrail job then fails on drift that exists only on your machine.
3. Add one `@AsyncTest` method to `CorpusEvalTest` named exactly as the row's `testMethod`. Use
   `unsafeOperation(...)` for a documented-unsafe subject, since corruption there can surface as a
   thrown exception that the eval counts rather than fails on, and `safeOperation(...)` otherwise.
4. Add the library to `docs/DEPENDENCIES.md` (section 6) with the reason it earns a row.
5. Run it. `CorpusGates` fails if a method has no row, if a row has no method, if a
   documented-thread-safe subject draws a VERDICT-tier HIGH or CRITICAL finding, if a detector the
   feed table says cannot be fed reports anyway, or if the control lane hears from the agent-fed
   pair.
