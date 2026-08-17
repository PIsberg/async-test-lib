# 145 — ThreadLocal cache degradation

**Detector**: `ThreadLocalCacheDegradationDetector` (`DetectorType.THREAD_LOCAL_CACHE_DEGRADATION`) · **Severity**: 🟡 Medium

## The bug

```java
static final ThreadLocal<SimpleDateFormat> FORMAT =
        ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss"));
```

Nothing is wrong with this line. It was written because `SimpleDateFormat` is not thread-safe,
and on a pool it is a good answer: eight workers means eight formatters, built once and reused
for the life of the process. The instance count is bounded by the pool, which is why nobody
counts it.

Then the pool became a thread per task, and the same line started allocating a formatter for
every request and holding it for as long as that thread lives. The code did not change; what it
means did.

Nothing fails. The object is still confined to one thread, so it is still correct. It has just
stopped being a cache.

## The fix

```java
static final DateTimeFormatter FORMAT =            // FIX: immutable, so one instance serves everyone
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(UTC);
```

Prefer the immutable, shareable replacement and drop the `ThreadLocal` entirely —
`DateTimeFormatter` for `SimpleDateFormat`, a shared compiled `Pattern` with per-use `Matcher`s.

Where the helper must stay mutable, pool the *helper* rather than the thread: borrow one for the
call and return it in a `finally`.

## How this differs from `VIRTUAL_THREAD_CONTEXT_LEAKS`

That detector counts distinct `ThreadLocal` **keys** carried by one thread and reports a thread
holding too many. Here there is one key, and the question is how many **instances** it produced.

## What the detector observes

```java
SimpleDateFormat f = FORMAT.get();
detector.recordCachedValue("FORMAT", f, Thread.currentThread());
```

It counts distinct instances by identity, so:

- one instance per virtual thread, above the threshold → 🟡 **Medium**, nothing is being reused;
- the same object on every thread → silent, it is a shared constant;
- fewer instances than threads → silent, that is reuse, and it is the fix;
- platform threads only → silent, because on a pool the bound is real.

Recording the same value repeatedly costs nothing and changes no count.

## Run it

```bash
mvn test                 # or: gradle test
```
