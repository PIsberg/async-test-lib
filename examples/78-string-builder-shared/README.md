# Example 78 — Shared StringBuilder

**Detector**: `StringBuilderDetector`  
**Flag**: `detectStringBuilderIssues = true`

## The Problem

`RequestLogger` keeps a single `StringBuilder log` as an instance field. Its
`append(String msg)` method calls `log.append(msg).append("\n")` with no
synchronization. `StringBuilder` is explicitly documented as not thread-safe
(use `StringBuffer` or explicit locking if shared).

Under concurrent access:
- The internal `char[]` buffer can be grown by one thread while another is
  copying characters, producing `ArrayIndexOutOfBoundsException`.
- Two threads interleaving their appends produce garbled, interleaved output
  where log entries from different threads are merged into one line.

## How to Reproduce

Remove the `@Disabled` annotation from `test_concurrent_detectsSharedBuilder`
and run the test. `StringBuilderDetector` records every append from each thread
and flags `StringBuilder` instances accessed concurrently from more than one
thread.

## The Fix

Use `StringBuffer` instead of `StringBuilder`, wrap the appends in a
`synchronized` block, or accumulate per-thread strings and merge them at the
end with a `StringJoiner`.
