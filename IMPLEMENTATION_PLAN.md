# Implementation Plan for 4 New Detectors

## Status: Detectors Implemented ✅
- [x] ThreadLeakDetector
- [x] SleepInLockDetector  
- [x] UnboundedQueueDetector
- [x] ThreadStarvationDetector

## Remaining Tasks

### 1. Add DetectorType enum values (4 entries)
File: src/main/java/com/github/asynctest/DetectorType.java
- THREAD_LEAKS
- SLEEP_IN_LOCK
- UNBOUNDED_QUEUE
- THREAD_STARVATION

### 2. Add annotation parameters to AsyncTest.java (4 params)
File: src/main/java/com/github/asynctest/AsyncTest.java
- detectThreadLeaks (default true)
- detectSleepInLock (default true)
- detectUnboundedQueue (default true)
- detectThreadStarvation (default true)

### 3. Wire into DetectorRegistry
File: src/main/java/com/github/asynctest/DetectorRegistry.java
- Add 4 detector fields
- Initialize based on config flags
- Add to analyzeAll() method

### 4. Wire into AsyncTestContext  
File: src/main/java/com/github/asynctest/AsyncTestContext.java
- Add 4 detector field references
- Add 4 static accessor methods

### 5. Update AsyncTestConfig
File: src/main/java/com/github/asynctest/AsyncTestConfig.java
- Add 4 config fields
- Add 4 builder methods
- Wire from annotation

### 6. Write tests for detectors 2-4
- SleepInLockDetectorTest.java
- UnboundedQueueDetectorTest.java
- ThreadStarvationDetectorTest.java

### 7. Update README
- Add 4 new detectors to documentation
- Add examples

### 8. Add consumer fixture examples
- File: consumer-fixture/src/test/java/com/github/asynctest/fixture/ConsumerAsyncTestUsageTest.java
- Add 4 example tests
