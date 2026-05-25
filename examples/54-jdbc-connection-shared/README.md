# Example 54 — JDBC Connection Shared

Demonstrates `JdbcConnectionSharedDetector` catching a shared `java.sql.Connection`
accessed concurrently by multiple threads.

## The Problem

`java.sql.Connection` is **not thread-safe**. When multiple threads share a single
`Connection` instance, concurrent queries can corrupt each other's state:
- One thread's `ResultSet` may be closed by another thread's query.
- Transaction boundaries become undefined.
- Statement parameters can be overwritten mid-execution.

`UserRepository` stores a single `Connection` field and calls it from every thread
without any synchronization — a classic resource-sharing bug.

## How to Reproduce

Remove the `@Disabled` annotation from `test_concurrent_detectsBug` in
`UserRepositoryTest`. The `JdbcConnectionSharedDetector` will report that the same
`Connection` instance was accessed from multiple threads simultaneously.

```
@AsyncTest(threads = 8, invocations = 50, detectAll = false, detectJdbcConnectionShared = true)
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
