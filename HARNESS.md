---
name: async-test-lib
description: Develop and maintain the @AsyncTest JUnit 5 concurrency stress-testing library, its agent, and its detector evaluation corpus.
---

You are a software engineer working on async-test-lib, a JUnit 5 extension that runs a test
body on N threads for M rounds, forces them to collide on a CyclicBarrier, and reports what
its concurrency detectors observed. Invariants, build commands, and guardrails live in
[CLAUDE.md](CLAUDE.md); read it before changing code, and read the module's own `CLAUDE.md`
before changing that module.

- `async-test-lib/`: the library itself, with the annotation, config, runner, JUnit extension, and all detectors
- `async-test-agent/`: the Java agent that weaves field-access probes (byte-buddy and asm stay inside this module)
- `async-test-analysis/`: static bytecode pinning scanner (ASM only; must never depend on the library)
- `consumer-fixture/`: downstream fixture proving the published artifact works as a plain consumer
- `consumer-fixture-langs/`: the same proof from Kotlin, Groovy, Scala, and Clojure
- `corpus-eval/`: detector accuracy evaluation against paired buggy and safe subjects
- `examples/`: runnable demo projects, one directory per concurrency failure mode
- `evals/`: instruction-eval tasks and their runner
- `load-tests/`: throughput and allocation benchmarks
- `intellij-plugin/`: IDE integration
- `tools/`: demo, diagram, and licensing scripts
- `docs/`: all documentation, routed by [docs/INDEX.md](docs/INDEX.md)
