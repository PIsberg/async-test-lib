package se.deversity.asynctest.fixture.detectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.DetectorType;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;

import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.assertAllReported;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.reachable;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.spin;

/**
 * Phase 17, shared-stateful-JDK-objects group - {@code SHARED_BYTE_BUFFER} through
 * {@code SHARED_JSON_MAPPER_RECONFIG}.
 *
 * <p>Each fixture shares one mutable JDK object across the round's workers, records the access
 * through the detector's public API the way a consumer would, and the class asserts in
 * {@code @AfterAll} that the finding came back out through {@link AsyncFindings}. Before that
 * assertion existed these fixtures proved only that the accessor resolved: they ran the hazard,
 * recorded nothing, and would have passed with the detector deleted.
 *
 * <p>Two fixtures still assert reachability only, and say why at the call site:
 * {@code fileChannelPositionRace} needs one {@code FileChannel} open across workers, which is
 * lifecycle this fixture should not own, and {@code sharedJsonMapperReconfig} has no mapper to
 * share because the consumer fixture declares no JSON library - a dependency
 * {@code AsyncTestPublishedDependencyTest} deliberately pins.
 *
 * <p>Corresponding examples: {@code examples/120-shared-byte-buffer},
 * {@code examples/121-shared-charset-coder}, {@code examples/122-shared-checksum},
 * {@code examples/123-file-channel-position-race}, {@code examples/124-shared-iterator},
 * {@code examples/125-high-contention-atomic},
 * {@code examples/126-shared-json-mapper-reconfig}.
 */
class Phase17SharedStatefulJdkDetectorsFixtureTest {

    private static AsyncFindings findings;

    @BeforeAll
    static void collectFindings() {
        findings = AsyncFindings.collect();
    }

    @AfterAll
    static void everyFedDetectorReported() {
        try {
            assertAllReported(findings,
                    "SharedByteBufferDetector",
                    "SharedCharsetCoderDetector",
                    "SharedChecksumDetector",
                    "SharedIteratorDetector",
                    "HighContentionAtomicDetector");
        } finally {
            findings.close();
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.SHARED_BYTE_BUFFER})
    void sharedByteBuffer() {
        reachable("sharedByteBufferDetector()", AsyncTestContext::sharedByteBufferDetector);

        // position/limit are mutable state; two workers on one buffer corrupt each other. The
        // synchronized block keeps the fixture deterministic - the detector's subject is that
        // two threads touched one buffer's relative-position API, not how they were ordered.
        try {
            synchronized (SHARED_BUFFER) {
                AsyncTestContext.sharedByteBufferDetector()
                        .recordPositionalAccess(SHARED_BUFFER, "put/get");
                SHARED_BUFFER.clear();
                SHARED_BUFFER.put((byte) 1);
                SHARED_BUFFER.flip();
                SHARED_BUFFER.get();
            }
        } catch (RuntimeException expected) {
            // A shared ByteBuffer losing a race is the point of this fixture.
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.SHARED_CHARSET_CODER})
    void sharedCharsetCoder() {
        reachable("sharedCharsetCoderDetector()", AsyncTestContext::sharedCharsetCoderDetector);

        // CharsetEncoder is explicitly documented as not safe for concurrent use.
        try {
            synchronized (SHARED_ENCODER) {
                AsyncTestContext.sharedCharsetCoderDetector()
                        .recordAccess(SHARED_ENCODER, "encode", Thread.currentThread());
                SHARED_ENCODER.reset();
                SHARED_ENCODER.encode(CharBuffer.wrap("payload"));
            }
        } catch (RuntimeException expected) {
            // A shared encoder losing a race is the point of this fixture.
        } catch (java.nio.charset.CharacterCodingException e) {
            throw new AssertionError("ASCII payload must encode as UTF-8", e);
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.SHARED_CHECKSUM})
    void sharedChecksum() {
        reachable("sharedChecksumDetector()", AsyncTestContext::sharedChecksumDetector);

        // A Checksum accumulates across update() calls, so two workers interleave into one
        // running value. Shared deliberately: a fixture-local instance is not shared by
        // anything and gives the detector nothing to see.
        AsyncTestContext.sharedChecksumDetector()
                .recordAccess(SHARED_CRC, "update", Thread.currentThread());
        SHARED_CRC.update("payload".getBytes(StandardCharsets.UTF_8));
        spin((int) (SHARED_CRC.getValue() % 16));
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.FILE_CHANNEL_POSITION_RACE})
    void fileChannelPositionRace() {
        reachable("fileChannelPositionRaceDetector()",
            AsyncTestContext::fileChannelPositionRaceDetector);

        // Reachability only, deliberately. The hazard is two threads calling the
        // implicit-position read(buf)/write(buf) on ONE open channel, which would mean holding
        // a FileChannel open across the round and closing it after the last worker - lifecycle
        // this fixture should not own, and a leak if a round times out. The fixture therefore
        // exercises the positional overloads, which are the safe ones, on its own temp file.
        Path file = null;
        try {
            file = Files.createTempFile("async-test-fixture", ".bin");
            try (FileChannel channel = FileChannel.open(file,
                     StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                channel.write(ByteBuffer.wrap(new byte[] {1, 2, 3, 4}), 0L);
                channel.read(ByteBuffer.allocate(4), 0L);
            }
        } catch (IOException e) {
            throw new AssertionError("temp-file channel I/O must work", e);
        } finally {
            if (file != null) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                    // Best effort; the OS reclaims the temp directory.
                }
            }
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.SHARED_ITERATOR})
    void sharedIterator() {
        reachable("sharedIteratorDetector()", AsyncTestContext::sharedIteratorDetector);

        // An Iterator is a cursor: sharing one means two workers consume one sequence and each
        // sees a subset. Shared across the round on purpose, with the consumption guarded so
        // the fixture cannot throw NoSuchElementException on an exhausted cursor - the detector
        // records the sharing, which is what it reports on.
        synchronized (SHARED_CURSOR_LOCK) {
            AsyncTestContext.sharedIteratorDetector().recordAccess(SHARED_CURSOR, "next");
            if (SHARED_CURSOR.hasNext()) {
                spin(SHARED_CURSOR.next());
            }
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.HIGH_CONTENTION_ATOMIC})
    void highContentionAtomic() {
        reachable("highContentionAtomicDetector()", AsyncTestContext::highContentionAtomicDetector);

        // One AtomicLong hammered by every worker: correct, but CAS-retry bound. LongAdder is
        // the advisory's recommendation, and the retry rate is what the detector counts - it
        // needs at least 1000 attempts from 2+ threads with a failure ratio above 10%.
        //
        // The losing CAS below is forced rather than left to the scheduler: the value is read,
        // then moved by this same worker, so compareAndSet always fails on a stale expectation.
        // That is a genuine lost-update shape and it makes the fixture deterministic - relying
        // on two barrier-released threads to collide often enough would make this flaky, and a
        // flaky fixture teaches consumers to ignore it.
        var detector = AsyncTestContext.highContentionAtomicDetector();
        for (int i = 0; i < 300; i++) {
            long seen = HOT_COUNTER.get();
            HOT_COUNTER.incrementAndGet();
            detector.recordUpdate(HOT_COUNTER);
            boolean won = HOT_COUNTER.compareAndSet(seen, seen + 1);
            detector.recordCasAttempt(HOT_COUNTER, won);
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.SHARED_JSON_MAPPER_RECONFIG})
    void sharedJsonMapperReconfig() {
        // Reachability only, deliberately: the hazard is reconfiguring a JSON mapper after it
        // has been used concurrently, and this module declares no JSON library to share.
        // Feeding the detector a bare Object as a stand-in would assert that the detector
        // counts identities, not that a consumer's mapper misuse is caught - a weaker claim
        // than the assertion above, and easy to misread as the stronger one.
        reachable("sharedJsonMapperReconfigDetector()",
            AsyncTestContext::sharedJsonMapperReconfigDetector);
    }

    private static final ByteBuffer SHARED_BUFFER = ByteBuffer.allocate(64);

    private static final CharsetEncoder SHARED_ENCODER = StandardCharsets.UTF_8.newEncoder();

    private static final CRC32 SHARED_CRC = new CRC32();

    private static final List<Integer> SHARED_VALUES =
            new ArrayList<>(List.of(1, 2, 3, 4, 5, 6, 7, 8));

    private static final Iterator<Integer> SHARED_CURSOR = SHARED_VALUES.iterator();

    private static final Object SHARED_CURSOR_LOCK = new Object();

    private static final AtomicLong HOT_COUNTER = new AtomicLong();
}
