package se.deversity.asynctest.example.service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * BUGGY service that demonstrates resource (InputStream) leaking.
 *
 * BUG: processFile() creates a ByteArrayInputStream on every call and appends
 *      it to an internal list. close() is never called on any stream. In a real
 *      application using FileInputStream this exhausts OS file descriptors.
 *      Under concurrent load with many invocations, hundreds of streams pile up.
 *
 * FIX: Wrap stream creation in a try-with-resources block:
 *      try (InputStream in = new ByteArrayInputStream(data)) { ... }
 *      This guarantees close() is called even when an exception is thrown.
 */
public class FileProcessorService {

    // BUG: leaked streams accumulate here and are never closed
    private final List<InputStream> openedStreams = new ArrayList<>();

    /**
     * Process file data. Opens a stream but never closes it.
     * Thread-unsafe accumulation under concurrent load.
     */
    public int processFile(String path) {
        byte[] data = simulateRead(path);
        InputStream stream = new ByteArrayInputStream(data); // BUG: never closed
        openedStreams.add(stream);                           // BUG: leaks grow unbounded
        int total = 0;
        for (byte b : data) {
            total += b;
        }
        return total;
    }

    /** Returns the latest opened stream for detector registration. */
    public InputStream getLastStream() {
        if (openedStreams.isEmpty()) return null;
        return openedStreams.get(openedStreams.size() - 1);
    }

    private byte[] simulateRead(String path) {
        return (path + "-data").getBytes();
    }
}
