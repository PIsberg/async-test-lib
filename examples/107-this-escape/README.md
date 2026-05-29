# Example 107 — This-Escape From Constructor

Demonstrates **ThisEscapeDetector** catching a constructor that publishes `this` before it returns.

Requires async-test-lib 1.7.0+.

## The Problem

`EventListenerService`'s constructor calls `sharedRegistry.add(this)` before it finishes
assigning its fields. The `this` reference escapes into a collection that other threads can
read, so a concurrent reader may observe the instance while `ready`/`config` are still unset.
Because these fields are non-final, there is no final-field visibility guarantee — the reader
can see a partially constructed object.

## How to Reproduce

1. Remove `@Disabled` from `test_concurrent_detectsThisEscape` in
   `EventListenerServiceTest`.
2. Run the test:
   ```
   mvn test
   gradle test
   ```
3. **ThisEscapeDetector** will report that `this` was published from the constructor before
   construction completed. A single escape is flagged regardless of whether a concurrent
   reader actually observed the partial state.

## The Fix

Never let `this` escape a constructor. Use a static factory that constructs the object
fully, then registers it via a separate `init()`/`start()` step:

```java
public static EventListenerService create(List<Object> registry) {
    EventListenerService s = new EventListenerService(); // fully constructed
    registry.add(s);                                     // safe: register after construction
    return s;
}
```
