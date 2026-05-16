package se.deversity.asynctest.demo;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Demonstrates SharedCollectionDetector catching a shared ArrayList across threads.
 * Used by the demo GIF workflow (tools/demo-commands.sh).
 */
class SharedListTest {

    // shared across all concurrent threads — ArrayList is NOT thread-safe
    private final List<String> requestLog = new ArrayList<>();

    @AsyncTest(threads = 6, invocations = 3, detectSharedCollections = true)
    void requestLog_mustBeThreadSafe() {
        AsyncTestContext.sharedCollectionMonitor()
            .registerCollection(requestLog, "request-log", "ArrayList");

        // multiple threads writing concurrently — race condition!
        AsyncTestContext.sharedCollectionMonitor()
            .recordWrite(requestLog, "request-log", "add");
        requestLog.add("req-" + Thread.currentThread().threadId());
    }
}
