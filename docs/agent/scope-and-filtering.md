# Instrumentation scope and filtering

Part of the [Agent Instrumentation Guide](../AGENT.md).

## 5. Scope & filtering

### Built-in ignores (always applied)
The agent re-establishes the exclusions Byte Buddy applies by default (a bare `ignore(...)`
call would otherwise **replace** them). A type is ignored when its fully-qualified name starts
with any of:

| Prefix | Why ignored |
|--------|-------------|
| `java.` | JDK core — instrumenting it risks recursion and is never the code under test. |
| `jdk.` | JDK internals. |
| `sun.` | Legacy JDK internals. |
| `com.sun.` | JDK-shipped internals. |
| `net.bytebuddy.` | The weaver itself — instrumenting it would recurse. |
| `se.deversity.asynctest.` | This library — the telemetry pipeline must not observe itself. |

Additionally ignored:
- **Synthetic types** (`ElementMatchers.isSynthetic()`) — e.g. lambda classes.
- **Bootstrap-class-loader types** — every type loaded by the bootstrap loader (a
  class-loader-scoped check applied at the install site).

### How `includes` / `excludes` interact with the built-ins
- **`excludes` is additive.** Each exclude prefix is OR-ed onto the built-in ignore matcher.
  It can only *remove* candidates; it never overrides a built-in ignore.
- **`includes` narrows the positive match.** With no `includes`, the positive matcher is
  `any()` (every non-ignored type). With `includes`, the positive matcher becomes the OR of
  `nameStartsWith(prefix)` — only types under one of those prefixes are candidates, and the
  built-in ignores still apply on top.
- A type is instrumented iff it matches `includes` (or `includes` is empty) **and** is not
  caught by the built-in ignores **and** is not caught by any `excludes` prefix.

Practical guidance: pass `includes=com.myapp` to keep weaving (and startup cost) bounded to
your own code.
