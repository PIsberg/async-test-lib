package se.deversity.asynctest.demo;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.DetectorType;

import java.util.ArrayList;
import java.util.List;

/**
 * Demonstrates SharedCollectionDetector catching a shared ArrayList across threads.
 * Used by the demo GIF workflow (tools/demo-commands.sh).
 */
class SharedListTest {

    // shared across all concurrent threads — ArrayList is NOT thread-safe
    private final List<String> requestLog = new ArrayList<>();

    // includes = exactly this detector and nothing else: keeps the recorded demo
    // focused on the one finding it is about. (Historically also load-bearing: the
    // recording workflow dirties pom.xml before recording, and the since-removed
    // UncommittedChangesDetector printed "uncommitted files" findings into the GIF.)
    @AsyncTest(threads = 6, invocations = 3, includes = DetectorType.SHARED_COLLECTIONS)
    void requestLog_mustBeThreadSafe() {
        AsyncTestContext.sharedCollectionMonitor()
            .registerCollection(requestLog, "request-log", "ArrayList");

        // multiple threads writing concurrently — race condition!
        AsyncTestContext.sharedCollectionMonitor()
            .recordWrite(requestLog, "request-log", "add");
        requestLog.add("req-" + Thread.currentThread().threadId());
    }
}
