# Logging conventions

> Part of the [architecture documentation](../ARCHITECTURE.md). The short version — the rules you
> cannot break without breaking a test — is in the root [CLAUDE.md](../../CLAUDE.md#logging). This
> page is the reasoning and the full style.

This library runs inside somebody else's test suite, so its output is somebody else's build log.
That constraint drives everything below.

## Two channels, two budgets

**The report and the assertion messages are the user-facing channel.** A developer reads those when
a test fails; they are the product. Detector findings belong here and nowhere else.

**SLF4J is the diagnostic channel.** `INFO` stays scarce and bounded per test run — a suite with
2,000 `@AsyncTest` methods must not produce 2,000 lines nobody asked for. `DEBUG` is the narrative
and it is free to be generous, because it is off unless someone turns it on.

Severity has a fixed meaning:

| Level | Means |
|-------|-------|
| `ERROR` | A human must act |
| `WARN` | Degraded but handled |
| `INFO` | Bounded per test run |
| `DEBUG` | The narrative; off by default |

A detector finding is neither `WARN` nor `ERROR`. It is a finding, and it belongs in the report.

## Write events, not positions

Use `domain.event key=value key=value` — one event per line, lower-case dotted names
(`runner.config`, `runner.round.start`, `runner.round.done`).

Every event inside a run carries `test=`, so one `grep` gives you one test's story out of a parallel
suite. Without it, interleaved output from concurrent forks is unreadable.

Log the decision and the values behind it:

```
runner.config test=… threads=8 multiplier=2.0 effectiveTimeoutMs=10000
```

answers "why did this behave differently on CI?" in one line. `entering execute()` answers nothing —
it records a position in the code, which the stack trace already had.

The replay seed belongs in the narrative on every round. It is the reproduction handle for a failure
that only happens sometimes, and it is worthless if it is only printed on the failing round.

## Cost

Guard with `log.isDebugEnabled()` whenever the arguments cost anything to assemble. Never build a
string for a level that is off. This matters more here than in most libraries: the log statements
sit inside the hot loop of an N×M contention engine, and a wasted `StringBuilder` per access is a
measurable perturbation of the thing being measured.

## A log event asserted in a test is a contract

`ConcurrencyRunnerLogContractTest` pins the `runner.config` event and its field names. Renaming one
is a breaking change, not a cleanup — downstream CI dashboards parse these.

When you fix a bug, add the `DEBUG` line that would have made it obvious in one read, and keep it.
The line that would have saved you an hour is worth more than the one you thought to write in
advance.

## Further reading

*Vibe Architecture*, Chapter 6b, "The Log Is a Feedback Loop".
