package com.github.asynctest.diagnostics;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Detects HTTP client concurrency issues, particularly with Java 11+ HttpClient.
 *
 * Common HTTP client issues detected:
 * - Unclosed HTTP responses (HttpResponse body streams not consumed/closed)
 * - Connection pool exhaustion from too many concurrent requests
 * - Concurrent access to shared HttpClient instances without proper configuration
 * - Requests initiated but never awaited/completed
 *
 * Usage:
 * <pre>{@code
 * @AsyncTest(threads = 10, detectHttpClientIssues = true)
 * void testHttpClient() throws Exception {
 *     HttpClient client = HttpClient.newHttpClient();
 *     AsyncTestContext.httpClientDetector()
 *         .recordClientCreated(client, "api-client");
 *     
 *     HttpRequest request = HttpRequest.newBuilder()
 *         .uri(URI.create("http://example.com"))
 *         .build();
 *     
 *     AsyncTestContext.httpClientDetector()
 *         .recordRequestSent(request, "api-call");
 *     
 *     HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
 *     AsyncTestContext.httpClientDetector()
 *         .recordResponseReceived(response, "api-call");
 * }
 * }</pre>
 */
public class HttpClientConcurrencyDetector {

    private static class ClientState {
        final String name;
        final AtomicInteger requestCount = new AtomicInteger(0);
        final AtomicInteger responseCount = new AtomicInteger(0);
        final AtomicInteger pendingRequests = new AtomicInteger(0);
        final AtomicInteger maxConcurrentRequests = new AtomicInteger(0);
        final Set<Long> activeThreads = ConcurrentHashMap.newKeySet();
        final Map<String, RequestState> requests = new ConcurrentHashMap<>();

        ClientState(String name) {
            this.name = name;
        }
    }

    private static class RequestState {
        final String name;
        final long startTime;
        volatile boolean completed;
        volatile boolean responseRecorded;
        volatile String status = "sent";

        RequestState(String name) {
            this.name = name;
            this.startTime = System.currentTimeMillis();
        }
    }

    private final Map<String, ClientState> clients = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;

    /**
     * Disable this detector.
     */
    public void disable() {
        enabled = false;
    }

    /**
     * Enable this detector.
     */
    public void enable() {
        enabled = true;
    }

    /**
     * Record creation of an HTTP client.
     *
     * @param client the client instance (used for identity)
     * @param name a descriptive name for tracking
     */
    public void recordClientCreated(Object client, String name) {
        if (!enabled || client == null) {
            return;
        }
        clients.computeIfAbsent(String.valueOf(System.identityHashCode(client)),
            k -> new ClientState(name));
    }

    /**
     * Record an HTTP request being sent.
     *
     * @param request the request instance
     * @param name a descriptive name for tracking
     */
    public void recordRequestSent(Object request, String name) {
        if (!enabled || request == null) {
            return;
        }
        String key = String.valueOf(System.identityHashCode(request));
        RequestState requestState = new RequestState(name);
        
        // Find or create client state
        ClientState client = clients.values().stream().findFirst()
            .orElseGet(() -> {
                ClientState newClient = new ClientState(name);
                clients.put("unknown", newClient);
                return newClient;
            });
        
        client.requestCount.incrementAndGet();
        client.pendingRequests.incrementAndGet();
        client.activeThreads.add(Thread.currentThread().threadId());
        int current = client.pendingRequests.get();
        client.maxConcurrentRequests.updateAndGet(max -> Math.max(max, current));
        client.requests.put(key, requestState);
    }

    /**
     * Record an HTTP response being received.
     *
     * @param response the response instance
     * @param name should match the request name
     */
    public void recordResponseReceived(Object response, String name) {
        if (!enabled || response == null) {
            return;
        }
        for (ClientState client : clients.values()) {
            RequestState matchedRequest = null;
            for (RequestState requestState : client.requests.values()) {
                if (!requestState.responseRecorded &&
                    (name == null || name.equals(requestState.name))) {
                    matchedRequest = requestState;
                    break;
                }
            }
            if (matchedRequest != null) {
                matchedRequest.responseRecorded = true;
                matchedRequest.completed = true;
                client.responseCount.incrementAndGet();
                client.pendingRequests.updateAndGet(current -> Math.max(0, current - 1));
            }
        }
    }

    /**
     * Analyze HTTP client usage for issues.
     *
     * @return a report of detected issues
     */
    public HttpClientConcurrencyReport analyze() {
        HttpClientConcurrencyReport report = new HttpClientConcurrencyReport();
        report.enabled = enabled;

        for (ClientState client : clients.values()) {
            int requests = client.requestCount.get();
            int responses = client.responseCount.get();
            int pending = client.pendingRequests.get();

            // Check for unclosed/uncompleted requests
            if (pending > 0) {
                for (RequestState requestState : client.requests.values()) {
                    if (!requestState.completed) {
                        report.pendingRequests.add(String.format(
                            "%s: request '%s' sent but not completed",
                            client.name, requestState.name));
                    }
                }
            }

            // Check for request/response mismatch
            if (requests > responses) {
                report.uncompletedRequests.add(String.format(
                    "%s: %d requests sent, only %d responses received",
                    client.name, requests, responses));
            }

            // Check for potential connection pool exhaustion
            if (client.maxConcurrentRequests.get() > 50) {
                report.poolExhaustionRisk.add(String.format(
                    "%s: high concurrent request count (%d) may exhaust connection pool",
                    client.name, client.maxConcurrentRequests.get()));
            }

            // Track thread activity
            if (!client.activeThreads.isEmpty()) {
                report.threadActivity.put(client.name, String.format(
                    "%d threads made HTTP requests",
                    client.activeThreads.size()));
            }
        }

        return report;
    }

    /**
     * Report class for HTTP client concurrency issues.
     */
    public static class HttpClientConcurrencyReport {
        private boolean enabled = true;
        final java.util.List<String> pendingRequests = new java.util.ArrayList<>();
        final java.util.List<String> uncompletedRequests = new java.util.ArrayList<>();
        final java.util.List<String> poolExhaustionRisk = new java.util.ArrayList<>();
        final Map<String, String> threadActivity = new ConcurrentHashMap<>();

        /**
         * Check if any issues were detected.
         */
        public boolean hasIssues() {
            return !pendingRequests.isEmpty() || 
                   !uncompletedRequests.isEmpty() || 
                   !poolExhaustionRisk.isEmpty();
        }

        @Override
        public String toString() {
            if (!enabled) {
                return "HttpClientConcurrencyReport: disabled";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("HTTP CLIENT CONCURRENCY ISSUES DETECTED:\n");

            if (!pendingRequests.isEmpty()) {
                sb.append("  Pending Requests (not completed):\n");
                for (String issue : pendingRequests) {
                    sb.append("    - ").append(issue).append("\n");
                }
            }

            if (!uncompletedRequests.isEmpty()) {
                sb.append("  Uncompleted Requests:\n");
                for (String issue : uncompletedRequests) {
                    sb.append("    - ").append(issue).append("\n");
                }
            }

            if (!poolExhaustionRisk.isEmpty()) {
                sb.append("  Connection Pool Exhaustion Risk:\n");
                for (String issue : poolExhaustionRisk) {
                    sb.append("    - ").append(issue).append("\n");
                }
            }

            if (!threadActivity.isEmpty()) {
                sb.append("  Thread Activity:\n");
                for (Map.Entry<String, String> entry : threadActivity.entrySet()) {
                    sb.append("    - ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
                }
            }

            if (!hasIssues()) {
                sb.append("  No issues detected.\n");
            }

            sb.append("  Fix: ensure all HTTP requests are properly completed and responses are consumed/closed");
            return sb.toString();
        }
    }
}
