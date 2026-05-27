# Example 72 — Shared Random

Demonstrates **SharedRandomDetector**: a `TokenGenerator` uses a single static
`java.util.Random` instance shared across threads. While `Random` is internally
synchronized, all threads contend on the same atomic seed update — degrading
throughput and making the distribution predictable under high concurrency.

## The Problem

`TokenGenerator` declares `private static final Random RANDOM = new Random()`.
Every call to `generateToken()` contends with all other concurrent callers on
the same `AtomicLong` seed inside `Random`. Under 8+ threads the throughput
collapses because every `nextInt()` call requires a CAS on the shared seed.
The correct fix is `ThreadLocalRandom.current()` which gives each thread its
own seed without synchronisation overhead.

SharedRandomDetector records all access threads for each `Random` instance and
reports instances that are accessed from more than one thread.

## How to Reproduce

1. Remove `@Disabled` from `testGenerateToken_concurrent_detectsSharedRandom`.
2. Run: `mvn test` or `./gradlew test`
3. The test fails with a **SharedRandomDetector** report showing the static
   `Random` instance accessed from multiple threads simultaneously.

**Fix**: replace `RANDOM.nextInt()` with `ThreadLocalRandom.current().nextInt()`.
