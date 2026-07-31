# Example 126 — Shared JSON Mapper Reconfiguration

**Detector**: `SharedJsonMapperReconfigDetector` (`DetectorType.SHARED_JSON_MAPPER_RECONFIG`, also usable standalone)

## The Problem

Start with what is **not** the bug: sharing the mapper. Jackson's `ObjectMapper` is
documented as thread-safe for `readValue`/`writeValue`, and its serializer caches only pay
off when the instance is long-lived. Gson, Moshi and kotlinx.serialization say the same
about their equivalents. One mapper per application is the recommendation, and this detector
never reports it.

The guarantee has a boundary, and the boundary is **configuration**. The `configure()` /
`setDateFormat()` / `registerModule()` / `setSerializationInclusion()` family mutates fields
the serialization path reads without synchronization, and invalidates caches other threads
are in the middle of using. Jackson's javadoc is direct about it: configuration is expected
to happen once, before the mapper is handed out.

So the bug is never "we shared the mapper". It is "we shared the mapper, **and then**
something reconfigured it" — a per-request date format, a feature toggled from a lazily
initialised path, a background config refresh, a test helper flipping pretty-printing on.
The window is small and the corruption is intermittent, which is the worst pair of
properties a bug can have.

## The buggy pattern

```java
private final ObjectMapper sharedMapper = new ObjectMapper();   // ✓ fine, recommended

String serialize(Object body, String dateFormat) {
    sharedMapper.setDateFormat(new SimpleDateFormat(dateFormat));  // ✗ per request, on the
    return sharedMapper.writeValueAsString(body);                  //   already-shared mapper
}
```

## The Fix

```java
private final ObjectMapper sharedMapper = new ObjectMapper()
        .setDateFormat(ISO_8601)          // ✓ configured once, at construction
        .registerModule(new JavaTimeModule());

String serialize(Object body, DateFormat dateFormat) {
    ObjectWriter writer = sharedMapper.writer(dateFormat);   // ✓ derived view, no mutation
    return writer.writeValueAsString(body);
}
```

Configure once, then treat the mapper as immutable. When a request genuinely needs different
settings, derive rather than mutate: `ObjectMapper.copy()`, or the `ObjectWriter` /
`ObjectReader` views, which exist precisely so per-call configuration never touches the
shared instance.

## How to Detect

```java
var d = new SharedJsonMapperReconfigDetector();
d.recordUse(mapper);                                       // on each serializing thread
d.recordConfigMutation(mapper, "setDateFormat(dd/MM/yyyy)");
assertTrue(d.analyze().hasIssues());                       // → flagged (HIGH)
```

The rule it encodes — worth knowing, because it is narrower than "don't reconfigure":

| Situation | Reported? |
|---|---|
| Configured before first use | No — that is the correct lifecycle |
| One thread reconfiguring a mapper only it uses | No — nobody to race with |
| Mutated while ≥ 2 threads are using it | **Yes** |
| Mutated by a thread that is not the one using it | **Yes** |

The mapper is taken as `Object`, so the detector has no dependency on any JSON library and
applies unchanged to `ObjectMapper`, `Gson`, `Moshi` or your own serializer. This example
models one with a small hand-rolled mapper for the same reason.

Inside `@AsyncTest`, grab it with `AsyncTestContext.sharedJsonMapperReconfigDetector()`,
select it alone with `includes = { DetectorType.SHARED_JSON_MAPPER_RECONFIG }`, or drop it
with `excludes`.

See [`JsonSerializationServiceTest`](src/test/java/se/deversity/asynctest/example/JsonSerializationServiceTest.java)
for all four rows of that table plus the cache-drop the reconfiguration causes.

## Running

```bash
mvn -f ../../pom.xml install -DskipTests -Dlicense.mock.mode=true
mvn -f pom.xml test
```
