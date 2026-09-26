package se.deversity.asynctest.diagnostics;

import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
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
        /**
         * The sends not yet answered, one entry per send. Not keyed by the request: an
         * {@code HttpRequest} is immutable and made to be sent again, and filing sends by the
         * request's identity let a second send of one request overwrite the first, so its
         * response found nothing left to answer and a fully answered run reported a request that
         * never got its response.
         */
        final Queue<RequestState> requests = new ConcurrentLinkedQueue<>();

        ClientState(String name) {
            this.name = name;
        }
    }

    private static class RequestState {
        final String name;
        /** Claimed by exactly one response; a check-then-set let two responses answer one send. */
        final AtomicBoolean answered = new AtomicBoolean();

        RequestState(String name) {
            this.name = name;
        }
    }

    /** How the report names the sends that no client can be tied to. */
    private static final String UNATTRIBUTED_CLIENT = "(no client named)";

    /** Registered clients, by identity. */
    private final Map<IdentityKey, ClientState> clients = new ConcurrentHashMap<>();
    /**
     * Sends recorded without a client while none, or more than one, is registered. They used to
     * be filed under whichever client the map returned first, so with two clients registered the
     * report blamed one that may never have sent anything.
     */
    private final ClientState unattributed = new ClientState(UNATTRIBUTED_CLIENT);
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
        clients.computeIfAbsent(new IdentityKey(client), k -> new ClientState(name));
    }

    /**
     * Record an HTTP request being sent, without saying which client sent it.
     *
     * <p>With exactly one client registered the request is that client's. With none, or more
     * than one, nothing here says which client sent it, so it is reported under
     * {@code (no client named)}; use {@link #recordRequestSent(Object, Object, String)} to
     * name the client.
     *
     * @param request the request instance
     * @param name a descriptive name for tracking
     */
    public void recordRequestSent(Object request, String name) {
        if (!enabled || request == null) {
            return;
        }
        Iterator<ClientState> registered = clients.values().iterator();
        ClientState only = registered.hasNext() ? registered.next() : null;
        send(only != null && !registered.hasNext() ? only : unattributed, name);
    }

    /**
     * Record an HTTP request being sent by {@code client}.
     *
     * <p>The request is reported under that client, registering it under {@code name} if
     * {@link #recordClientCreated} was never called for it.
     *
     * @param client the client sending the request, tracked by identity
     * @param request the request instance
     * @param name a descriptive name for tracking
     * @since 1.12.3
     */
    @API(status = Status.EXPERIMENTAL)
    public void recordRequestSent(Object client, Object request, String name) {
        if (!enabled || client == null || request == null) {
            return;
        }
        send(clients.computeIfAbsent(new IdentityKey(client), k -> new ClientState(name)), name);
    }

    private static void send(ClientState client, String name) {
        client.requestCount.incrementAndGet();
        int current = client.pendingRequests.incrementAndGet();
        client.activeThreads.add(Thread.currentThread().threadId());
        client.maxConcurrentRequests.accumulateAndGet(current, Math::max);
        client.requests.add(new RequestState(name));
    }

    /**
     * Record an HTTP response being received.
     *
     * <p>Answers one unanswered send of that name, from a registered client first. It used to
     * answer one on every client that had one, so a single response completed several sends.
     *
     * @param response the response instance
     * @param name should match the request name
     */
    public void recordResponseReceived(Object response, String name) {
        if (!enabled || response == null) {
            return;
        }
        for (ClientState client : clients.values()) {
            if (answer(client, name)) {
                return;
            }
        }
        answer(unattributed, name);
    }

    private static boolean answer(ClientState client, @Nullable String name) {
        for (Iterator<RequestState> it = client.requests.iterator(); it.hasNext(); ) {
            RequestState request = it.next();
            if ((name == null || name.equals(request.name)) && request.answered.compareAndSet(false, true)) {
                it.remove();
                client.responseCount.incrementAndGet();
                client.pendingRequests.updateAndGet(current -> Math.max(0, current - 1));
                return true;
            }
        }
        return false;
    }

    /**
     * Analyze HTTP client usage for issues.
     *
     * @return a report of detected issues
     */
    public HttpClientConcurrencyReport analyze() {
        HttpClientConcurrencyReport report = new HttpClientConcurrencyReport();
        report.enabled = enabled;

        List<ClientState> all = new ArrayList<>(clients.values());
        if (unattributed.requestCount.get() > 0) {
            all.add(unattributed);
        }
        for (ClientState client : all) {
            int requests = client.requestCount.get();
            int responses = client.responseCount.get();
            int pending = client.pendingRequests.get();

            // Check for unclosed/uncompleted requests
            if (pending > 0) {
                for (RequestState requestState : client.requests) {
                    if (!requestState.answered.get()) {
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
        final List<String> pendingRequests = new ArrayList<>();
        final List<String> uncompletedRequests = new ArrayList<>();
        final List<String> poolExhaustionRisk = new ArrayList<>();
        final Map<String, String> threadActivity = new ConcurrentHashMap<>();

        /**
         * Check if any issues were detected.
         *
         * @return {@code true} when this detector recorded something worth reporting
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

            sb.append("""
  Why: An HTTP request that is sent but never completed (response not read/closed) holds an open
       connection in the connection pool. Under sustained leaking, the pool exhausts its connection
       limit and all subsequent requests block indefinitely waiting for a free connection.
  Fix:
    - Always close the response body: use try-with-resources on HttpResponse.body() or call close()
    - For async requests: always call join() or thenAccept() to consume the response
    - Set a connection pool limit and a response timeout so hung requests time out rather than block forever\
""");
            return sb.toString();
        }
    }
}
