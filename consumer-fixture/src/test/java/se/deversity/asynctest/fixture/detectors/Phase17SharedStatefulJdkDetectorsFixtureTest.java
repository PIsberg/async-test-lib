package se.deversity.asynctest.fixture.detectors;

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

import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.reachable;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.spin;

/**
 * Phase 17, shared-stateful-JDK-objects group — {@code SHARED_BYTE_BUFFER} through
 * {@code SHARED_JSON_MAPPER_RECONFIG}.
 *
 * <p>These seven detectors have no {@code examples/} module of their own yet — this package
 * is currently their only end-to-end coverage.
 *
 * <p>The JSON fixture asserts reachability only: the consumer fixture declares no JSON
 * library, and pulling one in would widen the fixture's dependency set, which
 * {@code AsyncTestPublishedDependencyTest} deliberately pins.
 */
class Phase17SharedStatefulJdkDetectorsFixtureTest {

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.SHARED_BYTE_BUFFER})
    void sharedByteBuffer() {
        reachable("sharedByteBufferDetector()", AsyncTestContext::sharedByteBufferDetector);

        // position/limit are mutable state; two workers on one buffer corrupt each other.
        try {
            synchronized (SHARED_BUFFER) {
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
        // running value. Fixture-local instance; the detector's subject is the sharing.
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update("payload".getBytes(StandardCharsets.UTF_8));
        spin((int) (crc.getValue() % 16));
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.FILE_CHANNEL_POSITION_RACE})
    void fileChannelPositionRace() {
        reachable("fileChannelPositionRaceDetector()",
            AsyncTestContext::fileChannelPositionRaceDetector);

        // read(buf)/write(buf) advance a shared channel position; the positional overloads
        // do not. The fixture uses the positional form — the safe one — on its own file.
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

        // An Iterator is a cursor: sharing one means two workers consume one sequence and
        // each sees a subset. Fixture-local so the round stays deterministic.
        List<Integer> values = new ArrayList<>(List.of(1, 2, 3));
        Iterator<Integer> cursor = values.iterator();
        int total = 0;
        while (cursor.hasNext()) {
            total += cursor.next();
        }
        spin(total);
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.HIGH_CONTENTION_ATOMIC})
    void highContentionAtomic() {
        reachable("highContentionAtomicDetector()", AsyncTestContext::highContentionAtomicDetector);

        // One AtomicLong hammered by every worker: correct, but CAS-retry bound. LongAdder
        // is the advisory's recommendation.
        for (int i = 0; i < 256; i++) {
            HOT_COUNTER.incrementAndGet();
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.SHARED_JSON_MAPPER_RECONFIG})
    void sharedJsonMapperReconfig() {
        reachable("sharedJsonMapperReconfigDetector()",
            AsyncTestContext::sharedJsonMapperReconfigDetector);
    }

    private static final ByteBuffer SHARED_BUFFER = ByteBuffer.allocate(64);

    private static final CharsetEncoder SHARED_ENCODER = StandardCharsets.UTF_8.newEncoder();

    private static final AtomicLong HOT_COUNTER = new AtomicLong();
}
