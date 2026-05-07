# Shared XML Parser Example

This example demonstrates the **SharedXmlParserDetector** (Phase 12, `async-test-lib` 0.10.0).

## The Problem

`XmlProcessor` holds a single shared `DocumentBuilder` instance.
`DocumentBuilder` is stateful and **not thread-safe**: its internal SAX parser, entity
resolver, error handler, and document locator are all mutable objects shared across
calls to `parse()`. Concurrent invocations corrupt each other's parse state, producing
garbled `Document` objects or throwing `SAXException`/`ConcurrentModificationException`
non-deterministically.

The same issue applies to `SAXParser`, `Transformer`, and `XPath` instances.

## Why Sequential Tests Miss This Bug

```java
@Test
void part1_parseXml_singleThread() throws Exception {
    XmlProcessor proc = new XmlProcessor(sharedBuilder);
    Document doc = proc.parse("<item><id>1</id></item>");
    assertNotNull(doc.getElementsByTagName("id").item(0)); // ✅ Passes
}
```

One thread, one parse at a time — the shared state is never contested. The test always
produces a correct `Document`.

## How `@AsyncTest` Exposes the Bug

```java
@AsyncTest(threads = 4, invocations = 3, detectSharedXmlParser = true, timeoutMs = 5000)
void part2_detectSharedParser() {
    var d = AsyncTestContext.sharedXmlParserDetector();
    d.recordAccess(sharedBuilder, "DocumentBuilder", Thread.currentThread());
    sharedBuilder.parse(...); // shared instance — flagged!
}
```

The detector reports:

```
SHARED XML PARSER DETECTED:
  - 'DocumentBuilder' instance accessed from 4 threads — XML parsers (DocumentBuilder,
    SAXParser, Transformer, XPath) are not thread-safe; concurrent use causes corrupted
    parse results or ConcurrentModificationExceptions.
    Fix: use ThreadLocal<DocumentBuilder> or create a new instance per invocation.
```

## Running the Example

```bash
cd examples/17-shared-xml-parser
mvn clean test
# ✅ Tests pass — @Test gives false confidence

# Upgrade to 0.10.0 and enable @AsyncTest (see comments in the test file)
```

## The Fix

```java
private static final ThreadLocal<DocumentBuilder> THREAD_LOCAL_BUILDER =
    ThreadLocal.withInitial(() -> {
        try { return DocumentBuilderFactory.newInstance().newDocumentBuilder(); }
        catch (Exception e) { throw new RuntimeException(e); }
    });
```

Or create a new `DocumentBuilder` per parse call — `DocumentBuilderFactory` is
thread-safe so instantiation is the only overhead.

## Severity

| Failure mode | Symptom |
|-------------|---------|
| Corrupted `Document` | Parse succeeds but returns wrong data — silent correctness bug |
| `SAXException` / `CME` | Parse throws intermittently — hard to reproduce under lower concurrency |
