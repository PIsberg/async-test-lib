# Comparison with Other Tools

> Extracted from the former `docs/README.md`. See [INDEX.md](../INDEX.md) for the full documentation map.

| Feature | async-test | JUnit | Java Stress Tests | ThreadSanitizer |
|---------|-----------|-------|-------------------|-----------------|
| Race condition forcing | ✅ | ❌ | ❌ | ❌ |
| Deadlock detection | ✅ | ❌ | ❌ | ❌ |
| Visibility detection | ✅ | ❌ | ❌ | ❌ |
| False sharing detection | ✅ | ❌ | ❌ | ❌ |
| Virtual thread support | ✅ | ❌ | ❌ | ❌ |
| JMM validation | ✅ | ❌ | ❌ | ❌ |
| ABA problem detection | ✅ | ❌ | ❌ | ❌ |
| Lock order validation | ✅ | ❌ | ❌ | ❌ |
| Async pipeline monitoring | ✅ | ❌ | ❌ | ❌ |
| JUnit 5 integration | ✅ | ✅ | ❌ | ❌ |

