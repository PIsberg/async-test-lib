# Example 59 — Mutable Map Key

Demonstrates `MutableMapKeyDetector` catching a mutable object used as a
`HashMap` key whose post-insertion mutation makes map entries unreachable.

## The Problem

`HashMap` uses `hashCode()` at insertion time to decide the bucket. If an object
used as a key is mutated after insertion, its `hashCode()` changes and `get()`
can no longer find the entry in the correct bucket:

```java
UserSession key = new UserSession("session-1");
map.put(key, "data");   // stored in bucket for hashCode("session-1")
key.setId("session-2"); // hashCode changes!
map.get(key);           // looks in wrong bucket → returns null
```

`SessionRegistry` uses a mutable `UserSession` (with a settable `id` field) as a
`HashMap` key without any override of `hashCode`/`equals` based on immutable state.
Under concurrent access, threads mutating the same key object corrupt each other's
map entries.

## How to Reproduce

Remove the `@Disabled` annotation from `test_concurrent_detectsBug` in
`SessionRegistryTest`. The `MutableMapKeyDetector` will report the key mutation.

```
@AsyncTest(threads = 8, invocations = 50, detectAll = false, detectMutableMapKeys = true)
void test_concurrent_detectsBug() { ... }
```

Run with Maven:
```
mvn test
```

Or with Gradle:
```
./gradlew test
```
