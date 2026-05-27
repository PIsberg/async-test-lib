package se.deversity.asynctest.example.service;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * BUGGY service that demonstrates unclosed stream resource leak.
 *
 * BUG: openPipeline() creates a Stream and registers it for tracking but
 *      never calls close() on it. In production this would be backed by
 *      Files.lines(path) — each unclosed stream holds a file descriptor.
 *      Under concurrent load the OS file-descriptor limit is quickly exhausted.
 *
 * FIX: use try-with-resources around every I/O-backed stream:
 *
 * <pre>{@code
 * try (Stream<String> lines = Files.lines(path)) {
 *     return lines.filter(s -> !s.isBlank()).collect(Collectors.toList());
 * }
 * }</pre>
 */
public class DataPipelineService {

    // Tracks all streams opened so the test can inspect them.
    private final List<Stream<String>> openStreams = new ArrayList<>();

    /**
     * Open a data pipeline stream and return its contents.
     * BUG: the stream is never closed, leaking its underlying resource.
     *
     * @param data lines to process (simulates data read from an I/O source)
     * @return filtered non-blank lines
     */
    public List<String> openPipeline(List<String> data) {
        // BUG: stream is created but close() is never called.
        Stream<String> stream = data.stream().filter(s -> !s.isBlank());
        synchronized (openStreams) {
            openStreams.add(stream);
        }
        // In production this would be: Files.lines(path) — a resource stream.
        return stream.toList();
    }

    /** Returns the count of streams that have been opened (and never closed). */
    public int openStreamCount() {
        synchronized (openStreams) {
            return openStreams.size();
        }
    }

    /**
     * Returns one of the open streams so the test can register it with the
     * detector as a {@link Closeable} handle.
     */
    public Stream<String> getLastOpenStream() {
        synchronized (openStreams) {
            if (openStreams.isEmpty()) return null;
            return openStreams.get(openStreams.size() - 1);
        }
    }
}
