# Async Test Library - Examples

Real-world examples demonstrating common Java concurrency bugs that `@AsyncTest` finds but standard `@Test` misses.

## Available Examples

| # | Example | Primary Detector | Async Problem | Severity |
|---|---------|------------------|---------------|----------|
| 01 | [CompletableFuture Exception Handling](01-completablefuture-exception-handling/) | `CompletableFutureExceptionDetector` | Unhandled exceptions in async chains cause silent data loss | 🔴 Critical |
| 02 | [Visibility/Volatile Flag](02-visibility-volatile-flag/) | `VisibilityMonitor` | Missing `volatile` on shared flags causes threads to never see shutdown signals | 🔴 Critical |

## Quick Start

Each example is a standalone Maven project that:
- ✅ **Passes** with `@Test` (sequential execution - false confidence)
- ❌ **Fails** with `@AsyncTest` (concurrent stress - exposes the real bug)
- 📖 Includes detailed comments explaining the problem and solution

```bash
# Run all examples (they pass with @Test)
for dir in example-*/; do
  mvn -f "$dir/pom.xml" clean test
done

# To see the bugs, change @Test to @AsyncTest in any example test
```

## Example Structure

```
examples/
├── README.md                                    # This file
├── example-01-completablefuture-exception-handling/
│   ├── README.md                                # Detailed explanation of the bug
│   ├── pom.xml
│   └── src/
│       ├── main/java/.../OrderProcessingService.java      # Buggy production code
│       └── test/java/.../OrderProcessingServiceTest.java  # Tests + solution
├── example-02-visibility-volatile-flag/
│   ├── pom.xml
│   └── src/
│       ├── main/java/.../TaskProcessorService.java        # Buggy production code
│       └── test/java/.../TaskProcessorServiceTest.java    # Tests + solution
└── ... (more examples)
```

## Common Async Problems Covered

### 1. Unhandled CompletableFuture Exceptions (Example 01)
**What**: Async operations fail without `.exceptionally()` or `.handle()`, causing silent data loss.

**Impact**: Orders/messages disappear without trace. No error logging, no retries, no fallback.

**Primary Detector**: `CompletableFutureExceptionDetector`
- Flags: "Unhandled exception in CompletableFuture chain"
- Detects: Exceptions that propagate without being caught

**Secondary Detectors**: 
- `RaceConditionDetector` - Unsynchronized access to shared state
- `VisibilityMonitor` - Inconsistent state visibility across threads

### 2. Memory Visibility / Missing volatile (Example 02)
**What**: Non-volatile shared fields cause threads to cache stale values and never see updates.

**Impact**: Graceful shutdown hangs, workers run indefinitely, resources leak.

**Primary Detector**: `VisibilityMonitor`
- Flags: "Field 'running' accessed by multiple threads without volatile keyword"
- Detects: Non-volatile fields read/written by multiple threads

**Secondary Detectors**: 
- `BusyWaitDetector` - Workers spinning indefinitely
- `ThreadLeakDetector` - Workers that never terminate

## How to Use These Examples

### For Learning
1. Start with `@Test` - observe tests pass
2. Change to `@AsyncTest(threads = 10, invocations = 50, detectAll = true)`
3. Run tests - watch them fail with detailed detector reports
4. Read the solution in the test file comments
5. Apply the fix - see tests pass again

### For Your Own Code
1. Identify similar patterns in your codebase
2. Write tests with `@AsyncTest` 
3. Let the library's detectors find the exact bugs
4. Apply the documented solutions

## Adding New Examples

When contributing new examples:
1. Create `example-NN-short-description/` directory
2. Include buggy production code in `src/main/java`
3. Include tests with `@Test` (passes) and commented `@AsyncTest` (fails)
4. Document the problem, root cause, and solution in comments
5. Update this README with the new example

## CI Integration

All examples run in CI to ensure they compile and pass with `@Test`:
```yaml
- name: Run example tests
  run: |
    for dir in example-*/; do
      mvn -Dmaven.repo.local=.m2/repository -f "$dir/pom.xml" test
    done
```

## Key Takeaways

1. **@Test gives false confidence**: Sequential tests don't expose concurrent bugs
2. **@AsyncTest finds real problems**: Stress testing with barriers exposes race conditions, visibility issues, and unhandled exceptions
3. **Always handle async exceptions**: Use `.exceptionally()`, `.handle()`, or equivalent
4. **Use volatile for shared flags**: Any field read/written by multiple threads needs `volatile` or `Atomic*` types
5. **Test under concurrent load**: What works sequentially often fails under real concurrent access
