# Key Design Patterns

> Part of the [architecture documentation](../ARCHITECTURE.md).

## Key Design Patterns

### 1. ThreadLocal Context Pattern

**Purpose:** Share detector instances across all worker threads while maintaining thread-safe access.

**Implementation:**
- Runner creates single AsyncTestContext with all detectors
- Each worker thread installs context via `AsyncTestContext.install()`
- All threads access same detector instances concurrently
- Each thread uninstalls via `AsyncTestContext.uninstall()` after completion

### 2. Detector Recording Pattern

**Purpose:** Allow test code to record events for later analysis.

**Implementation:**
- Test code calls static accessor: `AsyncTestContext.falseSharingDetector()`
- Accessor gets context from ThreadLocal
- Returns detector instance
- Test code calls recording method: `recordFieldAccess(this, "counter", long.class)`
- Detector adds event to shared store (thread-safe)
- After test completes, `analyzeAll()` processes all recorded events

### 3. Barrier Synchronization Pattern

**Purpose:** Force all threads to start test body simultaneously for maximum contention.

**Implementation:**
- Runner creates CyclicBarrier with M threads
- Each thread submits task to ExecutorService
- Task calls `barrier.await()` before test body
- All threads block at barrier until last thread arrives
- Barrier releases all threads simultaneously
- Maximum thread contention achieved

---

