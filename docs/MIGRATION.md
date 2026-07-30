# Migration Guide

> Extracted from the former `docs/README.md`. See [INDEX.md](INDEX.md) for the full documentation map.

### From JUnit to async-test

**Before** (JUnit):
```java
@Test
void testCounter() {
    counter = 0;
    counter++;
    assertEquals(1, counter);
}
```

**After** (async-test):
```java
@AsyncTest(threads = 50, invocations = 100)
void testCounter() {
    counter++;
}

@AfterEach
void verify() {
    assertEquals(5000, counter);  // Catches race condition
}
```

### Adding Phase 2 Detectors

Incrementally enable detectors as needed:

```java
// Phase 1: Core
@AsyncTest(threads = 50, invocations = 100)
void test1() { }

// Phase 2: Add lock validation
@AsyncTest(threads = 50, invocations = 100, validateLockOrder = true)
void test2() { }

// Phase 2: Add cache detection
@AsyncTest(threads = 50, invocations = 100, validateLockOrder = true, 
           detectFalseSharing = true)
void test3() { }

// Phase 2: Comprehensive
@AsyncTest(threads = 50, invocations = 100,
           validateLockOrder = true,
           detectFalseSharing = true,
           detectABAProblem = true,
           validateConstructorSafety = true,
           monitorThreadPool = true)
void test4() { }
```

---

