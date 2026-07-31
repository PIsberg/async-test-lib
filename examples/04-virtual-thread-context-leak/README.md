# Example 04 — Virtual Thread Context Leak

**Detector**: `VirtualThreadContextLeakDetector` (`DetectorType.VIRTUAL_THREAD_CONTEXT_LEAKS`)

## The Problem

Per-request context in a `ThreadLocal` is everywhere — Spring's `RequestContextHolder`,
SLF4J's `MDC`, security principals, tenant IDs. The pattern rests on an assumption that
platform-thread pools made safe enough to stop thinking about: *the thread will be reused,
so clear the value when you are done*.

Virtual threads do not change that rule, they sharpen it. A missing `remove()` on a pooled
platform thread leaks into the next task on that thread. The failure is the same shape here,
and the consequence is the one that matters: **an audit log recording the wrong user**.

The clear() is almost always there. It is just not in a `finally`, so the one path that
skips it is the error path — the path under test least often, and the one where getting the
user wrong matters most.

## The buggy pattern

```java
void handleRequest(String userId) {
    service.setCurrentUser(userId);
    process();                        // ✗ throws -> the line below never runs
    service.clearCurrentUser();
}
```

## The Fix

```java
void handleRequest(String userId) {
    service.setCurrentUser(userId);
    try {
        process();
    } finally {
        service.clearCurrentUser();   // ✓ runs on every path
    }
}
```

Better still on Java 21+, use `ScopedValue`: the binding is scoped to a lambda and unbinds
when that lambda returns, however it returns. There is no `remove()` to forget, which is a
stronger guarantee than remembering to write `finally`.

```java
ScopedValue.where(CURRENT_USER, userId).run(this::process);   // ✓ cannot leak
```

## Why `@Test` Misses It

One request at a time, and the next `set()` overwrites the stale value before anybody reads
it. Cross-request contamination is not observable, so the test passes.

`@AsyncTest` runs many requests concurrently on virtual threads, with a barrier making them
collide, and one of them fails — leaving its context behind for whatever runs next.
`VirtualThreadContextLeakDetector` reports the `ThreadLocal` that was never released.

See [`RequestScopedServiceTest`](src/test/java/se/deversity/asynctest/example/RequestScopedServiceTest.java).
Related: `THREAD_LOCAL_LEAKS` ([example 23](../23-thread-local-leak/)),
`THREAD_LOCAL_CONTAMINATION` ([example 85](../85-thread-local-contamination/)) and
`MDC_CONTEXT_LEAK` ([example 12](../12-mdc-context-leak/)).

## Running

```bash
mvn -f ../../pom.xml install -DskipTests -Dlicense.mock.mode=true
mvn -f pom.xml test
```
