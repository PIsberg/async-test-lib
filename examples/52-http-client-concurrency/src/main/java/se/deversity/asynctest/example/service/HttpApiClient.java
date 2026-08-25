package se.deversity.asynctest.example.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Fetches HTTP resources.
 *
 * <p><strong>Bug 1:</strong> a new {@link HttpClient} is created on every call. Each one
 * allocates its own connection pool, selector thread and executor, so nothing is reused and
 * enough calls will exhaust file descriptors.
 *
 * <p><strong>Bug 2, and the one this example's detector can see:</strong>
 * {@link #notifyAsync(String)} sends a request and drops the returned future on the floor.
 * The request goes out; whether it succeeded, failed, or returned a 500 is never established
 * by anybody. Fire-and-forget telemetry is the usual excuse, and it is usually wrong: the
 * cost of a failing endpoint is a silence indistinguishable from success.
 *
 * <p><strong>Fix:</strong> hold one {@code HttpClient} for the process, and complete every
 * request you start, even if completing it only means logging the failure.
 *
 * <p><strong>INSTRUMENTATION:</strong> HttpClientConcurrencyDetector pairs requests with
 * responses, so it needs to be told about both. The hooks below report them from the points
 * where they happen; they default to no-ops, so the production path never touches the test
 * library. This is the seam, not the bug.
 */
public class HttpApiClient {

    private volatile Consumer<Object> onClientCreated = client -> { };

    private volatile BiConsumer<Object, String> onRequestSent = (request, name) -> { };

    private volatile BiConsumer<Object, String> onResponseReceived = (response, name) -> { };

    /**
     * Fetches the given URL and returns the response body.
     *
     * <p>Still creates a client per call, but at least the request is completed.
     *
     * @param url the target URL
     * @return the response body
     * @throws Exception if the request fails
     */
    public String fetchUrl(String url) throws Exception {
        HttpClient client = HttpClient.newHttpClient();   // BUG 1: new client per request
        onClientCreated.accept(client);

        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
        onRequestSent.accept(request, "GET " + url);

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        onResponseReceived.accept(response, "GET " + url);

        return response.body();
    }

    /**
     * Sends a request and does not wait for it.
     *
     * <p>BUG: the {@code CompletableFuture} returned by {@code sendAsync} is discarded, so no
     * code anywhere ever observes the outcome. The request is counted as sent and never as
     * completed, which is exactly what HttpClientConcurrencyDetector reports.
     *
     * @param url the target URL
     */
    public void notifyAsync(String url) {
        HttpClient client = HttpClient.newHttpClient();   // BUG 1 again
        onClientCreated.accept(client);

        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
        onRequestSent.accept(request, "GET " + url);

        // BUG 2: the future is dropped here. Nothing calls onResponseReceived, because nothing
        // in this process will ever look at the answer.
        client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Installs the hooks HttpClientConcurrencyDetector needs. No-ops by default.
     *
     * @param clientCreated    called with each newly built HttpClient
     * @param requestSent      called with the request and a label as it goes out
     * @param responseReceived called with the response and the same label when one arrives
     */
    public void observeHttp(Consumer<Object> clientCreated,
                            BiConsumer<Object, String> requestSent,
                            BiConsumer<Object, String> responseReceived) {
        this.onClientCreated = clientCreated;
        this.onRequestSent = requestSent;
        this.onResponseReceived = responseReceived;
    }
}
