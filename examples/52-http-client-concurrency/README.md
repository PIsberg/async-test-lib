# Example 52 — HttpClient Created Per Request

Demonstrates **HttpClientConcurrencyDetector** catching a new `HttpClient`
instance created on every request instead of reusing a single shared instance.

## The Problem

`HttpApiClient.fetchUrl()` calls `HttpClient.newHttpClient()` on every
invocation. Each client creates its own connection pool, thread pool, and
selector thread. Under concurrent load this exhausts file descriptors, wastes
memory, and bypasses connection reuse, causing poor throughput and latency spikes.

A plain `@Test` makes one request and sees no ill effects. `HttpClientConcurrencyDetector`
records each client creation and flags when multiple distinct clients are created
across concurrent invocations of the same code path.

## How to Reproduce

1. Open `HttpApiClientTest.java`.
2. Remove the `@Disabled` annotation from `testFetchUrl_concurrent_detectsClientPerRequest`.
3. Run the test — `HttpClientConcurrencyDetector` will report multiple client instances.

## The Fix

Create one `HttpClient` instance (e.g., as a `static final` field or injected
dependency) and reuse it across all requests.
