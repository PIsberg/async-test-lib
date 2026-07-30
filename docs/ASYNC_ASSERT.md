# AsyncAssert — Side-Effect Polling

> Extracted from the former `docs/README.md`. See [INDEX.md](INDEX.md) for the full documentation map.

Wait for async operations cleanly without blocking:

```java
@Test
void testAsync() {
    triggerAsyncProcess();
    
    // Poll until condition is true
    AsyncAssert.awaitUntil(() -> database.hasRecord("id-123"), Duration.ofSeconds(5));
}
```

Capture CompletableFuture results seamlessly:

```java
CompletableFuture<String> future = myService.runAsync();
AsyncAssert.FutureCapture<String> capture = AsyncAssert.capture(future);

capture.awaitDone(Duration.ofSeconds(2));
assertEquals("SUCCESS", capture.getResult());
```

