# Example 55 — Lazy Init Race

Demonstrates `LazyInitRaceDetector` catching unsynchronized lazy initialization
where multiple threads can create duplicate instances of an expensive resource.

## The Problem

The classic unsynchronized null-check pattern:

```java
if (resource == null) {
    resource = new ExpensiveResource(); // multiple threads can enter here!
}
```

Without synchronization or `volatile`, two or more threads can pass the `null` check
simultaneously and each initialize a separate instance. This leads to:
- Wasted resources (multiple heavy objects created).
- Inconsistent state if downstream code assumes a singleton.
- No visibility guarantee — a thread may see `null` even after another wrote the field.

## How to Reproduce

Remove the `@Disabled` annotation from `test_concurrent_detectsBug` in
`ExpensiveResourceFactoryTest`. The `LazyInitRaceDetector` will report the race
on the null-check / initialization of the `resource` field.

```
@AsyncTest(threads = 8, invocations = 50, detectAll = false, detectLazyInitRace = true)
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
