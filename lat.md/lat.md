This directory defines the high-level concepts, business logic, and architecture of this project using markdown. It is managed by [lat.md](https://www.npmjs.com/package/lat.md) — a tool that anchors source code to these definitions. Install the `lat` command with `npm i -g lat.md` and run `lat --help`.

Note: lat's source-symbol links don't support `.java`, so code references in these pages are plain backtick paths relative to the repo root; wiki links (`[[...]]`) connect concept sections and are validated by `lat check`.

- [[architecture]] — the big picture: annotation → extension → runner → detectors → reports pipeline, package map, stable API surface
- [[configuration]] — `@AsyncTest` → immutable `AsyncTestConfig`, detector selection resolution rules (includes/excludes/detectAll), presets, severity gate, feature flags
- [[adding-a-detector]] — the synchronized-change playbook for the most common change in this repo; partial wiring compiles but silently detects nothing
- [[execution-model]] — `ConcurrencyRunner`'s N threads × M invocations barrier rounds, the `invocation.skip()` invariant, timeout budget, replay seeds, ThreadLocal context lifecycle
- [[detectors]] — the ~123 detectors: SPI contracts, registry wiring, the house thread-safety idiom, hot-path constraints, severity model
- [[reporting]] — violations, formatter strategy SPI, lifecycle listeners, baseline suppression, and the `failOn` gate
- [[quality-gates]] — test-suite conventions and build quirks, static analysis gates, the PITest mutation gate, license guard, benchmarking, fuzzing
