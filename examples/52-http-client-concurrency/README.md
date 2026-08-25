# Example 52 — A Request Nobody Waits For

Demonstrates **HttpClientConcurrencyDetector** catching a request that was sent and never
completed, on a service that also builds a new `HttpClient` for every call.

## The Problem

`HttpApiClient` has two bugs, and they compound.

`HttpClient.newHttpClient()` on every invocation gives each call its own connection pool,
executor and selector thread. Nothing is reused, and enough calls will exhaust file
descriptors.

Then `notifyAsync()` throws away the `CompletableFuture` that `sendAsync()` returns:

```java
// BUG: the future is dropped here. Nothing in this process will ever look at the answer.
client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
```

Fire-and-forget telemetry is the usual excuse, and it is usually wrong: a failing endpoint
produces a silence indistinguishable from success. It is also the bug this example's detector
can actually see.

## What this detector models, and what it does not

`HttpClientConcurrencyDetector` reports **requests sent and never completed**, a
**request/response count mismatch**, and a concurrent request count high enough to
**exhaust a connection pool**.

It does *not* report "many distinct `HttpClient` instances". That is what this example used to
claim, and to record: a `recordClientCreated` call and nothing else. `recordClientCreated` files
the client so requests have something to attach to; on its own it produces no finding. Enabling
the demonstration produced no output at all, which is issue #346.

## Why there is an HTTP server in the test

The requests are real, over loopback, to a `com.sun.net.httpserver.HttpServer` the test starts on
port 0 and stops afterwards. Recording a request/response lifecycle that never happened would be
the same mistake in a different costume, and a socket on 127.0.0.1 needs no network access from
CI.

## How to Reproduce

1. Open `HttpApiClientTest.java`.
2. Remove the `@Disabled` annotation from `testNotifyAsync_concurrent_detectsAbandonedRequests`.
3. Run the test:

```
HTTP CLIENT CONCURRENCY ISSUES DETECTED:
  Pending Requests (not completed):
    - HttpApiClient: request 'GET http://127.0.0.1:65196/ping' sent but not completed
    ...
```

`failOn = FailOn.LOW` is what turns that report into a failed run.

## The Fix

Hold one `HttpClient` for the process, as a `static final` field or an injected dependency, and
complete every request you start. Completing it can mean nothing more than logging the failure:

```java
client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
      .whenComplete((response, failure) -> {
          if (failure != null) {
              log.warn("notification to {} failed", url, failure);
          }
      });
```
