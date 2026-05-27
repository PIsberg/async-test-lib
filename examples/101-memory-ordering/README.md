# Example 101 — Memory Ordering

Demonstrates **MemoryOrderingMonitor** detecting stale reads from missing `volatile`.

## The Problem

`DataHolder` uses two plain (non-volatile) fields: `int value` and `boolean ready`.
A producer thread writes `value = v; ready = true;` and a consumer thread reads
`ready ? value : -1`. Without `volatile` or synchronization, the Java Memory Model does
not guarantee that the consumer sees the latest write. The JIT compiler or CPU may
reorder stores, and the consumer's CPU cache may hold stale values. The consumer can
observe `ready == false` even after the producer wrote `true`, or see a stale `value`.

## How to Reproduce

1. Remove `@Disabled` from `test_concurrent_detectsStaleRead` in `DataHolderTest`.
2. Run the test:
   ```
   mvn test
   gradle test
   ```
3. **MemoryOrderingMonitor** will report stale reads — writes observed at a different
   value than reads from another thread.

## The Fix

Declare both fields `volatile`, or use `synchronized` blocks for both the publish and
consume operations to establish a happens-before relationship.
