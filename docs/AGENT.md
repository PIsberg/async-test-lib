# Agent Instrumentation Guide

`AsyncTestAgent` is an optional [Byte Buddy](https://bytebuddy.net) Java agent that
records field-access telemetry from your code **without any source changes**. This guide
covers what it is, why it exists, how it attaches by default and the three explicit ways, how to consume the
events it produces, how instrumentation scope is controlled, and its honest limitations.

> **Artifact:** `se.deversity.async-test-lib:async-test-agent` — a **separate module** since 1.7.0
> **Package:** `se.deversity.asynctest.agent`
> **Entry points:** `AsyncTestAgent.premain` / `agentmain` / `selfAttach`
> **Since:** agent 1.6.0; dynamic self-attach, package filters, diagnostics, and the
> telemetry bridge 1.7.0.

> ### Moved out of the library JAR
>
> The agent used to ship inside `async-test-lib.jar`, which carried the `Premain-Class` manifest
> and forced Byte Buddy onto every consumer's test classpath whether they used the agent or not.
> It is now its own artifact. Two things changed for you:
>
> ```xml
> <dependency>
>     <groupId>se.deversity.async-test-lib</groupId>
>     <artifactId>async-test-agent</artifactId>
>     <version><!-- same version as async-test-lib --></version>
>     <scope>test</scope>
> </dependency>
> ```
>
> and the attach flag now names the agent JAR: `-javaagent:async-test-agent-<version>.jar`.
> Nothing about the API, the entry points or the event format changed.
>
> Attach it at JVM startup with `-javaagent:`; the README quick start has the Maven and Gradle
> snippet, which CI builds as it stands. With only the dependency in place the runner loads the
> agent into the running JVM as a fallback, which JDK 21+ warns about and a future JDK will refuse
> ([attaching.md](agent/attaching.md)).

## Topics

Each topic lives in its own file under [`agent/`](agent/). Read the one you need rather than the whole set.

| Document | What it covers |
|----------|----------------|
| [overview.md](agent/overview.md) | What the agent records, why hand-written hooks fall short, backpressure and overhead |
| [attaching.md](agent/attaching.md) | The default attach from the test classpath, the launch flag with and without arguments, runtime self-attach, and build snippets |
| [consuming-events.md](agent/consuming-events.md) | `TelemetryBridge`, the raw callback, `TelemetryRegistry.buffer()`, and the event identifier format |
| [scope-and-filtering.md](agent/scope-and-filtering.md) | The built-in ignores and how `includes` / `excludes` interact with them |
| [diagnostics.md](agent/diagnostics.md) | What the runner reports when a detector cannot see, and fixes for common attach failures |
| [limitations.md](agent/limitations.md) | What the agent does not see, stated plainly |

## See also

- [ARCHITECTURE.md](ARCHITECTURE.md) — telemetry buffer and agent internals (§ High-Precision
  Contention Engine).
- [USAGE.md](USAGE.md) — full `@AsyncTest` reference.
