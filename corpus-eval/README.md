# corpus-eval

Measures what the 142 detectors report on third-party code with a documented thread-safety
contract. The write-up, with the numbers and what they do and do not support, is
[docs/analysis/corpus-eval.md](../docs/analysis/corpus-eval.md).

## Why it is a standalone module

The three corpus libraries are *subjects*, not tools. Keeping the module out of the reactor keeps
them off every classpath that matters: nothing in `async-test-lib`, the published artifacts or the
Gradle build resolves them, exactly as `consumer-fixture/` stays outside for its own reason.

## Running it

```bash
mvn install -DskipTests -Djacoco.skip=true    # so the module can resolve the current version
mvn -f corpus-eval/pom.xml test
```

The run writes `target/corpus-eval/corpus-eval.md` with the JVM, the OS, the configuration and one
row per subject. That file is the source of truth for a given run; the document under `docs/` is a
copy of one.

## Adding a subject

1. Find a class whose **own javadoc** states its thread-safety contract. An inferred contract is
   not ground truth and does not belong here.
2. Add a `Subject` row to `Corpus.java` quoting that sentence, with the file and line in the
   library's sources jar (`mvn dependency:copy -Dartifact=<ga>:<version>:jar:sources`).
3. Add one `@AsyncTest` method to `CorpusEvalTest` named exactly as the row's `testMethod`. Use
   `unsafeOperation(...)` for a documented-unsafe subject, since corruption there can surface as a
   thrown exception that the eval counts rather than fails on, and `safeOperation(...)` otherwise.
4. Run it. `CorpusGates` fails if a method has no row, if a row has no method, or if a
   documented-thread-safe subject draws a VERDICT-tier HIGH or CRITICAL finding.
