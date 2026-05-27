package se.deversity.asynctest.example.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Fetches HTTP resources.
 *
 * <p><strong>Bug:</strong> A new {@link HttpClient} instance is created on every
 * call to {@code fetchUrl()}. Each client allocates its own connection pool,
 * selector thread, and executor. Under concurrent load this wastes resources,
 * bypasses connection reuse, and can exhaust file descriptors.
 *
 * <p><strong>Fix:</strong> Create one {@code HttpClient} (e.g., as a
 * {@code static final} field or a constructor-injected dependency) and reuse
 * it across all requests.
 */
public class HttpApiClient {

    /**
     * Fetches the given URL and returns the response body as a string.
     *
     * <p>A new {@link HttpClient} is created per call — the resource-leak bug.
     *
     * @param url the target URL
     * @return the response body
     * @throws Exception if the request fails
     */
    public String fetchUrl(String url) throws Exception {
        // BUG: new client per request — wastes connections and threads
        HttpClient client = HttpClient.newHttpClient();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        HttpResponse<String> response = client.send(
                request, HttpResponse.BodyHandlers.ofString());

        return response.body();
    }
}
