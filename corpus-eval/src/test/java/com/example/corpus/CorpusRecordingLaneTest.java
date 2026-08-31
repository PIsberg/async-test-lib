package com.example.corpus;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.benmanes.caffeine.cache.Cache;
import com.google.common.collect.ConcurrentHashMultiset;
import com.google.common.collect.Multiset;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.commons.collections4.collection.SynchronizedCollection;
import org.apache.commons.collections4.list.CursorableLinkedList;
import org.apache.commons.lang3.mutable.MutableInt;
import org.apache.commons.collections4.map.LRUMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.util.ConcurrentReferenceHashMap;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.WeakHashMap;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentMap;
import java.util.List;
import java.util.Collection;
import java.util.Iterator;
import java.util.Collections;
import java.util.ArrayList;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.InvalidKeyException;

/**
 * The recording lane: the same libraries, with test bodies that cooperate.
 *
 * <p><strong>Why this is a separate measurement.</strong> The corpus's headline claim is that no
 * line of the subject and no line of the test cooperates with a detector, and that is exactly
 * what makes 137 of the 142 detectors unreachable there: they are fed by the {@code record*} and
 * {@code register*} calls the corpus refuses to make. The result was a report in which "no false
 * positive from detector X" and "X never ran" were the same row for 96% of the roster. This lane
 * fixes that for a named handful by doing what a user following {@code AsyncTestContext} does,
 * and its numbers must never be merged into the unmodified lanes'.
 *
 * <p><strong>Why its assertions are stronger.</strong> {@link CorpusEvalTest} gates only at the
 * group level, because whether one particular race is observed in one particular run is
 * probabilistic. Here it is not. A recording-fed detector's verdict is a function of the calls
 * the body made, so each subject states {@code MUST_FIRE} or {@code MUST_STAY_SILENT} and
 * {@link CorpusGates} holds it to that. Every subject is half of a pair: a MUST_FIRE row alone
 * would pass for a detector that fires on everything, and a MUST_STAY_SILENT row alone would
 * pass for one that was never wired up.
 *
 * <p>The receivers are still unmodified third-party classes. What changed is the test body.
 */
@ExtendWith(SubjectTracking.class)
class CorpusRecordingLaneTest {

    static final int THREADS = 6;
    static final int INVOCATIONS = 40;

    private static final Map<String, String> PAYLOAD = Map.of("key", "value");

    /** The bytes both crypto pairs feed their instance, so the two rows differ only in sharing. */
    private static final byte[] PAYLOAD_BYTES = "corpus".getBytes(StandardCharsets.UTF_8);

    /** The HMAC key, fixed so the confined and shared rows build identical instances. */
    private static final byte[] HMAC_KEY = "corpus-key".getBytes(StandardCharsets.UTF_8);

    /** One SHA-256 for the whole run, used with nothing held: the loud digest row. */
    private static MessageDigest sharedDigest;

    /** The same sharing with every access inside synchronized (guardedDigest): the silent row. */
    private static MessageDigest guardedDigest;

    /** One HmacSHA256 for the whole run: the loud crypto row. */
    private static Mac sharedMac;

    /** A Mac per thread, so no instance is ever recorded from two: the silent crypto row. */
    private static final ThreadLocal<Mac> CONFINED_MAC = ThreadLocal.withInitial(
            CorpusRecordingLaneTest::newMac);

    /**
     * Holds every buffer the leak row deliberately does not release.
     *
     * <p>Netty reclaims an unreleased unpooled heap buffer through the collector, and a
     * collected buffer could hand its identity hash to a later one, which is the key the
     * detector tracks instances by. Keeping a strong reference makes each leaked instance a
     * distinct row for the whole run, so the count the gate reads is the count the body made.
     */
    private static final List<ByteBuf> LEAKED = Collections.synchronizedList(new ArrayList<>());

    /** Configured once and never again: the pattern Jackson's own javadoc asks for. */
    private final ObjectMapper configuredMapper =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** Reconfigured while other threads write through it: the exception that javadoc names. */
    private final ObjectMapper reconfiguredMapper = new ObjectMapper();

    /** The key the loud row mutates after filing it. */
    private final MutableInt mutatedKey = new MutableInt(1);

    /** The identical key the quiet row files and then leaves alone. */
    private final MutableInt untouchedKey = new MutableInt(1);

    /** The map both keys are filed in; the map itself is not the subject. */
    private final Map<Object, String> keyedMap = new ConcurrentHashMap<>();

    /** One insertion per key for the run: recordKeyInserted resets the mutation count. */
    private final AtomicBoolean mutatedKeyFiled = new AtomicBoolean();

    /** The quiet row's own one-shot latch. */
    private final AtomicBoolean untouchedKeyFiled = new AtomicBoolean();

    /** Its own javadoc says, in bold, that the implementation is not synchronized. */
    private final List<String> cursorableList = new CursorableLinkedList<>();

    /** The twin guava documents as supporting concurrent modification. */
    private final Multiset<String> concurrentMultisetForModification = ConcurrentHashMultiset.create();

    /** One declaration per collection for the run; see unlockedDeclared for why. */
    private final AtomicBoolean cursorableDeclared = new AtomicBoolean();

    /** The safe row's own one-shot latch. */
    private final AtomicBoolean concurrentMultisetDeclared = new AtomicBoolean();

    /**
     * The same class as {@link #cursorableList}, mutated under the collection's own monitor.
     *
     * <p>Added for #406. The existing silent row for this detector is a different class, so what
     * separated fire from silence there was the type as much as the synchronization. This one
     * varies the synchronization and nothing else: same implementation, same recorded operation,
     * same six threads, and a monitor the detector can see. {@code ConcurrentModificationDetector}
     * intersects the locks held across recorded mutations and reports only when that intersection
     * is empty, so the pair separates exactly on the rule under test.
     */
    private final List<String> guardedCursorableList = new CursorableLinkedList<>();

    /** The guarded row's own one-shot latch. */
    private final AtomicBoolean guardedCursorableDeclared = new AtomicBoolean();

    /**
     * A second reference map, recorded to per-thread keys rather than one shared key.
     *
     * <p>Added for #406, replacing nothing: the caffeine row stays. Its purpose is that the
     * silence comes from a decision the detector made. The row this replaced called no detector
     * API at all, so a detector that fired on every single {@code recordCheckThenAct} would have
     * passed it; this one makes the same calls on the same class as the firing row and differs only
     * in the key, which is the other half of the site identity the detector groups on.
     */
    private final ConcurrentReferenceHashMap<String, String> perThreadKeyMap =
            new ConcurrentReferenceHashMap<>();

    /** Documented to support concurrent modification; its iterator is still confined state. */
    private final Multiset<String> concurrentMultiset =
            ConcurrentHashMultiset.create(List.of("alpha", "beta", "gamma"));

    /**
     * One iterator for the whole run: the object the shared row shares.
     *
     * <p>Held as a field rather than taken per body, which is the entire difference between the
     * two rows. The detector keys on the iterator's identity, so a per-body iterator would be a
     * different subject each time and could not accumulate a second thread.
     */
    private final Iterator<String> sharedIterator = concurrentMultiset.iterator();

    /** Traversed without the decorator's lock: the defect its own javadoc warns about. */
    private final Collection<String> unlockedCollection = syncCollection();

    /** The same traversal inside synchronized (coll): the pattern that javadoc prints. */
    private final Collection<String> lockedCollection = syncCollection();

    /**
     * One declaration per wrapper for the whole run, not one per worker.
     *
     * <p>{@code recordWrapperCreated} is idempotent now, but it was not until this row was
     * written - it installed a fresh {@code WrapperInfo} and discarded the iterations counted
     * so far, which is what a per-worker declaration would have hit. Declaring once is also
     * simply what a consumer does, so the row measures the shape people write.
     */
    private final AtomicBoolean unlockedDeclared = new AtomicBoolean();

    /** The locked row's own one-shot latch; separate wrapper, separate declaration. */
    private final AtomicBoolean lockedDeclared = new AtomicBoolean();

    /** Documented not synchronised, used as a cache the way the detector's javadoc shows. */
    private final LRUMap<String, String> lruMap = new LRUMap<>(64);

    /** Documented thread-safe, recorded identically to the row above. */
    private final Cache<String, String> caffeineCache = Caffeine.newBuilder().maximumSize(64).build();

    /** The key both merge rows re-enter on, seeded before the run so neither takes the absent path. */
    private static final String RECURSION_KEY = "recursion-key";

    /** The map whose remapping function merges the same key again: the loud recursion row. */
    private final Cache<String, String> recursiveCache = seededCache();

    /** The twin whose remapping function stays out of the map: the silent recursion row. */
    private final Cache<String, String> selfContainedCache = seededCache();

    /** The map whose remapping function merges a DIFFERENT key of itself: the cross-key row. */
    private final Cache<String, String> crossKeyCache = seededCache();

    /** The outer map of the cross-map pair, whose function reaches the inner one. */
    private final Cache<String, String> outerCache = seededCache();

    /** The inner map of the cross-map pair, which is a different map and so not a finding. */
    private final Cache<String, String> innerCache = seededCache();

    /** The second key the cross-key row re-enters on, seeded alongside the first. */
    private static final String OTHER_KEY = "other-key";

    /** Documented thread-safe, and used with a check-then-act anyway: the defect is the usage. */
    private final ConcurrentReferenceHashMap<String, String> referenceMap =
            new ConcurrentReferenceHashMap<>();

    /**
     * A pool of exactly one, so every thread in the run gets the same physical connection.
     *
     * <p>Sized deliberately. A pool as large as the thread count would give each thread its own
     * connection and the row would prove nothing; one connection makes the reuse across threads
     * the thing being measured. Nothing blocks on I/O - the connections are inert - so waiting
     * for a checkout costs nothing and the result does not depend on how fast the runner is.
     */
    private static HikariDataSource pool;

    /** Checked out once and never returned: the hoisted handle the second row shares. */
    private static Connection hoisted;

    /** Buffers are not safe for use by multiple concurrent threads: the relative-op row. */
    private final ByteBuffer relativeBuffer = ByteBuffer.allocate(256);

    /** The twin instance, shared just as widely and touched only at absolute indices. */
    private final ByteBuffer absoluteBuffer = ByteBuffer.allocate(256);

    /** One channel for the implicit-read row; every thread advances its shared cursor. */
    private static FileChannel implicitChannel;

    /** The twin channel, read only through the positional overload. */
    private static FileChannel positionalChannel;

    /** The file both channels read; created in installRecorder, deleted in reportAndGate. */
    private static Path channelFile;

    /** Its own javadoc: like most collection classes, this class is not synchronized. */
    private final Map<String, String> sharedWeakMap = new WeakHashMap<>();

    /** The same class, touched only inside synchronized (guardedWeakMap). */
    private final Map<String, String> guardedWeakMap = new WeakHashMap<>();

    /** A compile-time constant key, so no referent is ever cleared and expunge stays a no-op. */
    private static final String WEAK_KEY = "corpus-weak-key";

    @BeforeAll
    static void installRecorder() throws IOException, SQLException, NoSuchAlgorithmException {
        CorpusRecorder.install();

        sharedDigest = MessageDigest.getInstance("SHA-256");
        guardedDigest = MessageDigest.getInstance("SHA-256");
        sharedMac = newMac();

        HikariConfig config = new HikariConfig();
        config.setDataSource(new StubDataSource());
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(1);
        config.setPoolName("corpus-pool");
        pool = new HikariDataSource(config);

        HikariConfig hoistedConfig = new HikariConfig();
        hoistedConfig.setDataSource(new StubDataSource());
        hoistedConfig.setMaximumPoolSize(1);
        hoistedConfig.setPoolName("corpus-hoisted-pool");
        hoistedPool = new HikariDataSource(hoistedConfig);
        hoisted = hoistedPool.getConnection().unwrap(Connection.class);

        channelFile = Files.createTempFile("corpus-channel", ".bin");
        Files.write(channelFile, new byte[4096]);
        implicitChannel = FileChannel.open(channelFile, StandardOpenOption.READ);
        positionalChannel = FileChannel.open(channelFile, StandardOpenOption.READ);
    }

    /** Kept so the hoisted connection's own pool can be closed with it. */
    private static HikariDataSource hoistedPool;

    /**
     * The premise the pooled row's silence depends on, measured rather than assumed.
     *
     * <p>A MUST_STAY_SILENT row is only evidence if the detector had something to be silent
     * about. If the pool had handed each thread its own connection, or if only one thread had
     * ever touched it, the detector would short-circuit before reaching the rule under test and
     * the row would pass for the wrong reason. These two record what actually happened, and
     * {@link #thePooledRowsPremiseHeld()} refuses the result if it did not.
     */
    private static final Set<Integer> PHYSICAL_CONNECTIONS = ConcurrentHashMap.newKeySet();

    private static final Set<Long> THREADS_THAT_USED_THE_POOL = ConcurrentHashMap.newKeySet();

    @AfterAll
    static void reportAndGate() throws IOException {
        CorpusRecorder.uninstall();
        thePooledRowsPremiseHeld();
        pool.close();
        hoistedPool.close();
        implicitChannel.close();
        positionalChannel.close();
        Files.deleteIfExists(channelFile);
        CorpusLane lane = CorpusLane.current();
        Path report = CorpusReport.writeRecording(
                CorpusRecorder.findings(), THREADS, INVOCATIONS, lane);
        System.out.println("Corpus recording-lane report written to " + report.toAbsolutePath());
        System.out.println(CorpusReport.recordingSummary(CorpusRecorder.findings(), lane));
        CorpusGates.checkRecordingLane(CorpusRecorder.findings(), lane);
    }

    /**
     * Refuses a pass the pooled row would not have earned.
     *
     * <p>Its claim is that one physical connection reached many threads and drew no finding. Both
     * halves have to be true for the silence to mean anything: one connection, and more than one
     * thread. Without this, a pool that quietly opened six connections would produce the same
     * green result and the row would be measuring nothing.
     */
    private static void thePooledRowsPremiseHeld() {
        assertEquals(1, PHYSICAL_CONNECTIONS.size(),
                "the pool is sized to one so that every thread gets the same physical connection; "
                        + "with more than one, the silent row proves nothing about reuse across "
                        + "threads. Saw " + PHYSICAL_CONNECTIONS.size() + " distinct connections");
        assertTrue(THREADS_THAT_USED_THE_POOL.size() > 1,
                "that one connection has to reach more than one thread, or the detector "
                        + "short-circuits before it reaches the rule under test and the silence "
                        + "is not evidence. Saw " + THREADS_THAT_USED_THE_POOL.size() + " thread(s)");
    }

    // --- SharedJsonMapperReconfig ------------------------------------------------------------

    /**
     * Records a use, then a config mutation, from every thread.
     *
     * <p>The detector fires when a mutation is recorded after the instance has been used by two
     * or more threads, or from a thread that never used it. Six threads on a barrier all record
     * a use in the first round, so from the second round on the precondition is met by
     * construction rather than by luck - which is why the gate can require a finding.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_objectMapper_reconfigureWhileWriting() {
        CorpusRecorder.countBodyExecution();
        AsyncTestContext.sharedJsonMapperReconfigDetector().recordUse(reconfiguredMapper);
        AsyncTestContext.sharedJsonMapperReconfigDetector()
                .recordConfigMutation(reconfiguredMapper, "setDateFormat");
        try {
            reconfiguredMapper.setDateFormat(new SimpleDateFormat("yyyy-MM-dd"));
            reconfiguredMapper.writeValueAsString(PAYLOAD);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Records uses and never a mutation: config-then-use, which is the documented safe pattern. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_objectMapper_configuredThenShared() {
        CorpusRecorder.countBodyExecution();
        AsyncTestContext.sharedJsonMapperReconfigDetector().recordUse(configuredMapper);
        try {
            configuredMapper.writeValueAsString(PAYLOAD);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    // --- CacheConcurrency --------------------------------------------------------------------

    /**
     * A documented-unsafe map used as a cache, recorded the way the detector's javadoc shows.
     *
     * <p>{@code registerCache} is called once for the whole run rather than per worker: a
     * per-thread register scatters one shared subject across duplicate entries and the
     * cross-thread contention the detector measures becomes invisible exactly when it is real.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_lruMap_getAndPut() {
        CorpusRecorder.countBodyExecution();
        AsyncTestContext.cacheConcurrencyDetector().recordPut(lruMap, "lru-cache", "key", "value");
        lruMap.put("key", "value");
        AsyncTestContext.cacheConcurrencyDetector().recordGet(lruMap, "lru-cache", "key");
        lruMap.get("key");
    }

    /**
     * The same recorded calls against a receiver whose javadoc promises a thread-safe map.
     *
     * <p>The detector is handed identical evidence for both rows and has only the receiver to
     * separate them, which is the whole test: Caffeine's view implements {@code ConcurrentMap}
     * and keeps the contract, so a finding here is noise on correct code.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_caffeineAsMap_getAndPut() {
        CorpusRecorder.countBodyExecution();
        ConcurrentMap<String, String> view = caffeineCache.asMap();
        AsyncTestContext.cacheConcurrencyDetector().recordPut(view, "caffeine-cache", "key", "value");
        view.put("key", "value");
        AsyncTestContext.cacheConcurrencyDetector().recordGet(view, "caffeine-cache", "key");
        view.get("key");
    }

    // --- ConcurrentMapCheckThenAct -----------------------------------------------------------

    /**
     * Get-then-put on one key from six threads, on a map documented as thread-safe.
     *
     * <p>Each call is atomic and the pair is not, so this is the lost update the detector
     * reports. The row exists to make the lane's ground truth explicit: here the class is right
     * and the caller is wrong, which is the opposite of what the unmodified lanes measure.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_concurrentReferenceHashMap_checkThenAct() {
        CorpusRecorder.countBodyExecution();
        AsyncTestContext.nonAtomicConcurrentMapUpdateDetector()
                .recordCheckThenAct(referenceMap, "key", "get-then-put", Thread.currentThread());
        String existing = referenceMap.get("key");
        referenceMap.put("key", existing == null ? "first" : existing + "+");
    }

    // --- JdbcConnectionShared ----------------------------------------------------------------

    /**
     * The pool doing its job: one physical connection, many threads, one at a time.
     *
     * <p>The pool holds a single connection, so every thread in the run genuinely gets the same
     * physical handle - which is the whole point. HikariCP will not hand a checked-out connection
     * to a second thread, so no two ever hold it simultaneously, and the body says so by
     * recording a release. That makes the silence structural: it follows from the pool's checkout
     * discipline, not from how the threads happened to interleave.
     *
     * <p>The handle is unwrapped first because each checkout returns a fresh proxy. Recording
     * those would make every checkout look like a different resource, and the reuse this row
     * exists to measure would be invisible.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_hikariPool_checkoutPerThread() {
        CorpusRecorder.countBodyExecution();
        try (Connection pooled = pool.getConnection()) {
            Connection physical = pooled.unwrap(Connection.class);
            PHYSICAL_CONNECTIONS.add(System.identityHashCode(physical));
            THREADS_THAT_USED_THE_POOL.add(Thread.currentThread().threadId());
            AsyncTestContext.jdbcConnectionSharedDetector()
                    .recordAccess(physical, "hikari-conn", Thread.currentThread());
            AsyncTestContext.jdbcConnectionSharedDetector()
                    .recordRelease(physical, Thread.currentThread());
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The bug the pool exists to prevent: one connection hoisted out and shared.
     *
     * <p>Checked out once in {@code @BeforeAll} and never returned, so every thread uses the same
     * handle at the same time. Nothing is released, so the detector keeps the stricter model and
     * reports it. HikariDataSource is thread-safe and the caller defeated it, which is exactly the
     * distinction this pair exists to draw.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_hoistedConnection_sharedAcrossThreads() {
        CorpusRecorder.countBodyExecution();
        AsyncTestContext.jdbcConnectionSharedDetector()
                .recordAccess(hoisted, "hoisted-conn", Thread.currentThread());
    }

    // --- SharedMessageDigest -----------------------------------------------------------------

    /**
     * One SHA-256 instance, six threads, nothing held while it is used.
     *
     * <p>The detector fires when one JCA instance is recorded from more than one thread with no
     * lock held across every access. Six threads on a barrier meet both halves by construction,
     * so the finding is owed by the recorded calls rather than by an interleaving. The subject is
     * the JDK's own {@code MessageDigest}, whose class javadoc states that instances are not
     * safe for use by multiple threads without external synchronization.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_messageDigest_sharedAcrossThreads() {
        CorpusRecorder.countBodyExecution();
        AsyncTestContext.sharedMessageDigestDetector()
                .recordAccess(sharedDigest, "shared-sha256", Thread.currentThread());
        toleratingCorruption(() -> sharedDigest.update(PAYLOAD_BYTES));
    }

    /**
     * The same instance, the same six threads, and the external synchronization the javadoc asks
     * for.
     *
     * <p>The evidence handed to the detector is identical in every respect but one: every access
     * happens while this thread holds the digest's own monitor, which
     * {@code SelfGuard.TrackedInstance} sees through {@code Thread.holdsLock} without any agent
     * attached. That leaves a non-empty candidate lock set across every recorded access, so the
     * multi-thread half of the rule is met and the unguarded half is not. A detector that fires
     * here is telling someone who fixed their race that it is still broken.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_messageDigest_guardedByItsOwnMonitor() {
        CorpusRecorder.countBodyExecution();
        synchronized (guardedDigest) {
            AsyncTestContext.sharedMessageDigestDetector()
                    .recordAccess(guardedDigest, "guarded-sha256", Thread.currentThread());
            guardedDigest.update(PAYLOAD_BYTES);
        }
    }

    // --- SharedStatefulCrypto ----------------------------------------------------------------

    /**
     * One HmacSHA256 shared by every thread: the running MAC state is one object's field.
     *
     * <p>The loud half of a pair that differs by confinement rather than by locking, so between
     * the two crypto pairs both documented fixes are covered. {@code Mac} is stateful across
     * {@code update} and {@code doFinal}, and its javadoc makes no thread-safety promise.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_mac_sharedAcrossThreads() {
        CorpusRecorder.countBodyExecution();
        AsyncTestContext.sharedStatefulCryptoDetector()
                .recordAccess(sharedMac, "shared-hmac", Thread.currentThread());
        toleratingCorruption(() -> sharedMac.update(PAYLOAD_BYTES));
    }

    /**
     * A {@code Mac} per thread, which is what confinement looks like in the access stream.
     *
     * <p>Same class, same calls, same number of recordings; the only difference is that no
     * instance is ever recorded from a second thread, so the rule's first clause is never met.
     * This is the direction that catches a detector keyed on the class rather than on the
     * instance: one keyed on {@code Mac} would report six correct threads as a race.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_mac_confinedToOneThreadEach() {
        CorpusRecorder.countBodyExecution();
        Mac mine = CONFINED_MAC.get();
        AsyncTestContext.sharedStatefulCryptoDetector()
                .recordAccess(mine, "confined-hmac", Thread.currentThread());
        mine.update(PAYLOAD_BYTES);
    }

    // --- ResourceLeak ------------------------------------------------------------------------

    /**
     * A Netty buffer acquired and released inside the same body execution.
     *
     * <p>The detector reports an instance whose opens outnumber its closes, or one still open
     * when the run is analysed. A fresh buffer per execution, released before the body returns,
     * gives every tracked instance exactly one open and one close and leaves none open, which is
     * a structural claim rather than a timing one: no other thread ever touches this buffer, so
     * no interleaving can change either count.
     *
     * <p>Per execution rather than one shared buffer on purpose. A single shared instance would
     * have its {@code currentlyOpen} flag set by whichever thread wrote last, and a run that
     * happened to end on an acquire would report a still-open resource on correct code. That
     * would be a flaky row, which is worse than an absent one.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_nettyByteBuf_releasedAfterUse() {
        CorpusRecorder.countBodyExecution();
        ByteBuf buffer = Unpooled.buffer(16);
        AsyncTestContext.resourceLeakDetector()
                .registerResource(buffer, "released-bytebuf", "ByteBuf");
        AsyncTestContext.resourceLeakDetector().recordResourceOpened(buffer, "released-bytebuf");
        buffer.writeInt(1);
        buffer.release();
        AsyncTestContext.resourceLeakDetector().recordResourceClosed(buffer, "released-bytebuf");
    }

    /**
     * The same buffer lifecycle with the release left out: Netty's canonical leak.
     *
     * <p>Identical recorded calls minus the close, which is precisely the thing the detector is
     * supposed to notice. {@code ByteBuf} is reference counted and the caller owns the release;
     * an unreleased buffer is a leak whatever else the program does, so the finding follows from
     * the counts rather than from a race. The buffers are unpooled and heap-backed, so what
     * leaks here is reclaimed by the collector rather than by an allocator.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_nettyByteBuf_neverReleased() {
        CorpusRecorder.countBodyExecution();
        ByteBuf buffer = Unpooled.buffer(16);
        AsyncTestContext.resourceLeakDetector()
                .registerResource(buffer, "leaked-bytebuf", "ByteBuf");
        AsyncTestContext.resourceLeakDetector().recordResourceOpened(buffer, "leaked-bytebuf");
        buffer.writeInt(1);
        LEAKED.add(buffer);
    }

    /**
     * A {@code merge} whose remapping function merges a <em>different</em> key of the same map.
     *
     * <p>The shape #343 opened the rule to, and the one most likely to be in code that ships:
     * {@code ConcurrentHashMap}'s contract is "the mapping function must not modify this map",
     * not "must not modify this key", and this version usually returns normally rather than
     * throwing, so it survives review. Measured at this lane's own six threads and forty
     * invocations before the row was written: 240 of 240 nested mapping functions ran, nothing
     * thrown.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_caffeineAsMap_crossKeyMerge() {
        CorpusRecorder.countBodyExecution();
        ConcurrentMap<String, String> view = crossKeyCache.asMap();
        Thread self = Thread.currentThread();
        view.merge(RECURSION_KEY, "outer", (oldOuter, newOuter) -> {
            AsyncTestContext.concurrentMapComputeRecursionDetector()
                    .recordComputeStart(view, RECURSION_KEY, self, "caffeine-cross-key");
            try {
                return view.merge(OTHER_KEY, "inner", (oldInner, newInner) -> {
                    AsyncTestContext.concurrentMapComputeRecursionDetector()
                            .recordComputeStart(view, OTHER_KEY, self, "caffeine-cross-key");
                    try {
                        return "nested";
                    } finally {
                        AsyncTestContext.concurrentMapComputeRecursionDetector()
                                .recordComputeEnd(view, OTHER_KEY, self);
                    }
                });
            } finally {
                AsyncTestContext.concurrentMapComputeRecursionDetector()
                        .recordComputeEnd(view, RECURSION_KEY, self);
            }
        });
    }

    /**
     * The same nesting, one map apart, which is the boundary the cross-key rule must respect.
     *
     * <p>This is the row that makes #343 safe to have on by default. A mapping function that
     * fills some other cache is ordinary layered-cache code, and a rule keyed on the thread
     * rather than on the map would report every one of them. The recorded calls are identical in
     * number and nesting to the row above; only the receiver of the inner merge differs, which is
     * exactly the thing that decides whether the contract was broken.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_caffeineTwoMaps_nestedMerge() {
        CorpusRecorder.countBodyExecution();
        ConcurrentMap<String, String> outer = outerCache.asMap();
        ConcurrentMap<String, String> inner = innerCache.asMap();
        Thread self = Thread.currentThread();
        outer.merge(RECURSION_KEY, "outer", (oldOuter, newOuter) -> {
            AsyncTestContext.concurrentMapComputeRecursionDetector()
                    .recordComputeStart(outer, RECURSION_KEY, self, "caffeine-outer");
            try {
                return inner.merge(RECURSION_KEY, "inner", (oldInner, newInner) -> {
                    AsyncTestContext.concurrentMapComputeRecursionDetector()
                            .recordComputeStart(inner, RECURSION_KEY, self, "caffeine-inner");
                    try {
                        return "nested";
                    } finally {
                        AsyncTestContext.concurrentMapComputeRecursionDetector()
                                .recordComputeEnd(inner, RECURSION_KEY, self);
                    }
                });
            } finally {
                AsyncTestContext.concurrentMapComputeRecursionDetector()
                        .recordComputeEnd(outer, RECURSION_KEY, self);
            }
        });
    }

    // --- ConcurrentMapComputeRecursion -------------------------------------------------------

    /**
     * A {@code merge} whose remapping function merges the same key again, which really re-enters.
     *
     * <p>This row exists because the obvious version of it does not work, and the difference is
     * the whole point ({@link Corpus} carries the measurement). A nested
     * {@code computeIfAbsent} on an <em>absent</em> key never reaches the inner mapping function:
     * the bin holds a reservation node and {@code ConcurrentHashMap} throws
     * {@code IllegalStateException("Recursive update")} first, so the second
     * {@code recordComputeStart} the detector needs could only be written by hand at the call
     * site. On a key that is already <em>present</em> the bin holds a real node, the re-entry
     * re-acquires its monitor, and a monitor is reentrant: the nested call runs to completion and
     * the outer return value then overwrites what it stored.
     *
     * <p>So both {@code recordComputeStart} calls here are raised from inside a mapping function
     * that actually executed, which is what the detector's contract asks for and what makes this
     * an observed row rather than a constructed one. Measured before it was written: 240 of 240
     * nested mapping functions ran, with no exception, at exactly this lane's six threads and
     * forty invocations.
     *
     * <p>The key is seeded when the cache is built rather than by the body, because a body that
     * seeded it would take the absent-key path on its first execution and throw.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_caffeineAsMap_recursiveMerge() {
        CorpusRecorder.countBodyExecution();
        ConcurrentMap<String, String> view = recursiveCache.asMap();
        Thread self = Thread.currentThread();
        view.merge(RECURSION_KEY, "outer", (oldOuter, newOuter) -> {
            AsyncTestContext.concurrentMapComputeRecursionDetector()
                    .recordComputeStart(view, RECURSION_KEY, self, "caffeine-recursive");
            try {
                return view.merge(RECURSION_KEY, "inner", (oldInner, newInner) -> {
                    AsyncTestContext.concurrentMapComputeRecursionDetector()
                            .recordComputeStart(view, RECURSION_KEY, self, "caffeine-recursive");
                    try {
                        return "nested";
                    } finally {
                        AsyncTestContext.concurrentMapComputeRecursionDetector()
                                .recordComputeEnd(view, RECURSION_KEY, self);
                    }
                });
            } finally {
                AsyncTestContext.concurrentMapComputeRecursionDetector()
                        .recordComputeEnd(view, RECURSION_KEY, self);
            }
        });
    }

    /**
     * The same call, the same recording, and a remapping function that stays out of the map.
     *
     * <p>Identical in every respect the detector can see except the one it is looking for: one
     * {@code recordComputeStart} per body execution, each closed by its {@code recordComputeEnd},
     * so the slot is never occupied twice. This is what a correct {@code merge} looks like, and
     * it is the direction that catches a detector keyed on the map rather than on the map, key
     * and thread together: six threads merging the same key concurrently is not recursion.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_caffeineAsMap_selfContainedMerge() {
        CorpusRecorder.countBodyExecution();
        ConcurrentMap<String, String> view = selfContainedCache.asMap();
        Thread self = Thread.currentThread();
        view.merge(RECURSION_KEY, "outer", (oldValue, newValue) -> {
            AsyncTestContext.concurrentMapComputeRecursionDetector()
                    .recordComputeStart(view, RECURSION_KEY, self, "caffeine-self-contained");
            try {
                return oldValue.length() > 64 ? newValue : oldValue + '+';
            } finally {
                AsyncTestContext.concurrentMapComputeRecursionDetector()
                        .recordComputeEnd(view, RECURSION_KEY, self);
            }
        });
    }

    /**
     * {@return a fresh HmacSHA256 initialised with the shared key}
     *
     * <p>Both crypto rows build their instances the same way, so the only difference the
     * detector can see between them is how many threads reach one instance.
     */
    private static Mac newMac() {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(HMAC_KEY, "HmacSHA256"));
            return mac;
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 is a required JDK algorithm", e);
        }
    }

    /**
     * Runs a racy operation on an unmodified JDK instance without failing the run.
     *
     * <p>The same trade {@code CorpusEvalTest.unsafeOperation} makes, and for the same reason:
     * corrupting a {@code MessageDigest} or a {@code Mac} from six threads can surface as a
     * thrown exception out of the JDK's own buffer arithmetic rather than as a detector finding,
     * and that outcome is part of what the eval measures. The {@code record*} call is
     * deliberately outside this, so the expectation stays a function of the recorded calls even
     * on a run where the subject threw.
     */
    /**
     * {@return a cache whose recursion key already holds a value}
     *
     * <p>{@code merge} only calls its remapping function when the key is present. An unseeded
     * cache would make the first body execution a plain put, recording nothing, and on
     * {@code computeIfAbsent} the absent-key path is the one that throws instead of re-entering.
     * Seeding at construction removes both, so every body execution takes the same path.
     */
    private static Cache<String, String> seededCache() {
        Cache<String, String> cache = Caffeine.newBuilder().maximumSize(64).build();
        cache.put(RECURSION_KEY, "seed");
        cache.put(OTHER_KEY, "seed");
        return cache;
    }

    // --- SynchronizedCollectionIteration -------------------------------------------------------

    /**
     * Traverses a synchronizing decorator without holding its lock.
     *
     * <p>Every method on {@code SynchronizedCollection} takes the collection's monitor, so each
     * {@code next()} is individually safe and the traversal as a whole is not. That is the
     * exception the class javadoc calls out, and it is the caller's to get right: the class is
     * documented thread-safe and this body is still wrong.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_synchronizedCollection_iteratedWithoutLock() {
        CorpusRecorder.countBodyExecution();
        declareOnce(unlockedDeclared, unlockedCollection, "unlocked-collection");
        AsyncTestContext.synchronizedCollectionIterationDetector()
                .recordIterationStarted(unlockedCollection, Thread.currentThread(), false);
        traverse(unlockedCollection);
    }

    /**
     * The same traversal of an identical decorator, inside {@code synchronized (coll)}.
     *
     * <p>The detector is handed the same calls as the row above and one different bit. Nothing
     * mutates either collection, so neither row can throw; what separates them is only whether
     * the lock was held, which is exactly the model being measured.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_synchronizedCollection_iteratedHoldingLock() {
        CorpusRecorder.countBodyExecution();
        declareOnce(lockedDeclared, lockedCollection, "locked-collection");
        synchronized (lockedCollection) {
            AsyncTestContext.synchronizedCollectionIterationDetector()
                    .recordIterationStarted(lockedCollection, Thread.currentThread(), true);
            traverse(lockedCollection);
        }
    }

    private static Collection<String> syncCollection() {
        return SynchronizedCollection.synchronizedCollection(
                new ArrayList<>(List.of("alpha", "beta", "gamma")));
    }

    private static void declareOnce(AtomicBoolean latch, Collection<String> wrapper, String name) {
        if (latch.compareAndSet(false, true)) {
            AsyncTestContext.synchronizedCollectionIterationDetector()
                    .recordWrapperCreated(wrapper, name);
        }
    }

    /** Reads every element, so the traversal is real work rather than an unused iterator. */
    private static void traverse(Collection<String> collection) {
        int seen = 0;
        for (String value : collection) {
            seen += value.length();
        }
        assertTrue(seen > 0, "the corpus collections are seeded and must never traverse empty");
    }

    // --- SharedIterator --------------------------------------------------------------------

    /**
     * Advances one iterator instance from every thread in the run.
     *
     * <p>The multiset underneath is documented to support concurrent modification, which is the
     * point: it buys the iterator nothing. A cursor is unsynchronized state of its own, and the
     * detector's message says so - the hazard stands "even when that collection is itself a
     * concurrent collection".
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_concurrentHashMultiset_sharedIterator() {
        CorpusRecorder.countBodyExecution();
        AsyncTestContext.sharedIteratorDetector().recordAccess(sharedIterator, "hasNext");
        // Tolerated, because the subject is an iterator being misused on purpose. Six threads
        // interrogating one cursor is exactly what the row exists to record, and guava is under
        // no obligation to survive it: one run threw from inside hasNext() and took the lane
        // down with it. The record above has already happened, so the measurement does not
        // depend on the call returning.
        toleratingCorruption(sharedIterator::hasNext);
    }

    /**
     * The same call on the same collection, with each body taking its own iterator.
     *
     * <p>Every instance is then touched by exactly the one thread that created it, so the
     * detector's per-instance thread count never reaches two. This is the fix, and reporting it
     * would mean reporting every correct traversal of a concurrent collection there is.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_concurrentHashMultiset_iteratorPerThread() {
        CorpusRecorder.countBodyExecution();
        Iterator<String> own = concurrentMultiset.iterator();
        AsyncTestContext.sharedIteratorDetector().recordAccess(own, "hasNext");
        own.hasNext();
    }

    // --- ConcurrentModifications ----------------------------------------------------------------

    /**
     * Every thread mutates a list its own javadoc calls unsynchronized.
     *
     * <p>Tolerated, because that is what the subject is: a linked list mutated from six threads
     * can corrupt its own pointers, and the record has already happened when it does.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_cursorableLinkedList_concurrentAdd() {
        CorpusRecorder.countBodyExecution();
        declareCollectionOnce(cursorableDeclared, cursorableList, "cursorable-list");
        AsyncTestContext.concurrentModificationDetector()
                .recordModification(cursorableList, "cursorable-list", "add");
        toleratingCorruption(() -> cursorableList.add("value"));
    }

    /**
     * The identical mutation on a collection designed for exactly this.
     *
     * <p>This exact subject reported until #395 was fixed: it was one of the two the false
     * positive was measured on. It is the silent half of the pair and the row that keeps that
     * fix honest.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_concurrentMultiset_concurrentAdd() {
        CorpusRecorder.countBodyExecution();
        declareCollectionOnce(concurrentMultisetDeclared, concurrentMultisetForModification,
                "concurrent-multiset");
        AsyncTestContext.concurrentModificationDetector()
                .recordModification(concurrentMultisetForModification, "concurrent-multiset", "add");
        concurrentMultisetForModification.add("value");
    }


    /**
     * The same class as the firing row, mutated by every thread under the collection's monitor.
     *
     * <p>The detector notes the locks held at each recorded mutation and intersects them across
     * the run; a non-empty intersection is a lock that covered every mutation, and it reports
     * nothing. Six threads on a barrier all take the same monitor here, so the intersection holds
     * and the silence is the rule firing rather than the detector failing to look.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_cursorableLinkedList_mutatedUnderItsOwnMonitor() {
        CorpusRecorder.countBodyExecution();
        declareCollectionOnce(guardedCursorableDeclared, guardedCursorableList,
                "guarded-cursorable-list");
        synchronized (guardedCursorableList) {
            AsyncTestContext.concurrentModificationDetector()
                    .recordModification(guardedCursorableList, "guarded-cursorable-list", "add");
            guardedCursorableList.add("value");
        }
    }

    /**
     * Records the same check-then-act as the firing row, against a key private to each thread.
     *
     * <p>The detector groups by (map, key) and reports a site only when more than one thread
     * reached it. Every thread here performs a genuine get-then-put and says so; none of them
     * meets another on a key, so the site count stays at one thread throughout and the detector
     * has a decision to make rather than nothing to look at.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_concurrentReferenceHashMap_checkThenActOnPrivateKeys() {
        CorpusRecorder.countBodyExecution();
        String key = "key-" + Thread.currentThread().threadId();
        AsyncTestContext.nonAtomicConcurrentMapUpdateDetector()
                .recordCheckThenAct(perThreadKeyMap, key, "get-then-put", Thread.currentThread());
        String existing = perThreadKeyMap.get(key);
        perThreadKeyMap.put(key, existing == null ? "first" : existing + "+");
    }

    private static void declareCollectionOnce(AtomicBoolean latch, java.util.Collection<String> c,
                                              String name) {
        if (latch.compareAndSet(false, true)) {
            AsyncTestContext.concurrentModificationDetector().registerCollection(c, name);
        }
    }

    // --- MutableMapKey ----------------------------------------------------------------------

    /**
     * Files a mutable object as a map key and then changes it.
     *
     * <p>The mutation moves the key's hash away from the bucket the map filed it under, so the
     * entry stops being reachable by an equal key. No amount of synchronization repairs that -
     * it is a hash-contract defect, not a race - which is why the row exists on a class whose
     * own javadoc already says it is not thread safe.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_mutableIntKey_mutatedAfterInsertion() {
        CorpusRecorder.countBodyExecution();
        fileKeyOnce(mutatedKeyFiled, mutatedKey, "mutated-key");
        int before = mutatedKey.intValue();
        mutatedKey.increment();
        AsyncTestContext.mutableMapKeyDetector()
                .recordKeyMutation(mutatedKey, "value", before, mutatedKey.intValue());
    }

    /**
     * The same class filed the same way and then left alone: the ordinary, correct use.
     *
     * <p>Mutability is a hazard only when exercised. A detector that reported the type would
     * report every correct use of {@code MutableInt} as a key, which is most of them.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_mutableIntKey_neverMutated() {
        CorpusRecorder.countBodyExecution();
        fileKeyOnce(untouchedKeyFiled, untouchedKey, "untouched-key");
        untouchedKey.intValue();
    }

    private void fileKeyOnce(AtomicBoolean latch, Object key, String name) {
        if (latch.compareAndSet(false, true)) {
            keyedMap.put(key, "value");
            AsyncTestContext.mutableMapKeyDetector().recordKeyInserted(keyedMap, key, name);
        }
    }

    // --- SharedByteBuffer --------------------------------------------------------------------

    /**
     * Six threads rewind and relative-get one shared buffer with nothing held.
     *
     * <p>The detector fires when position-mutating operations reach an instance from more than
     * one thread with an empty candidate lock set, and six threads on a barrier meet both halves
     * by construction. Every body rewinds before its get, so the cursor never strays more than a
     * few in-flight bodies from zero and no interleaving can reach the 256-byte limit: the row
     * measures the sharing, not an underflow ending the run.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_byteBuffer_relativeGetsShared() {
        CorpusRecorder.countBodyExecution();
        AsyncTestContext.sharedByteBufferDetector().recordPositionalAccess(relativeBuffer, "rewind");
        relativeBuffer.rewind();
        AsyncTestContext.sharedByteBufferDetector().recordPositionalAccess(relativeBuffer, "get");
        relativeBuffer.get();
    }

    /**
     * The same sharing, recorded only at absolute indices.
     *
     * <p>Absolute get(int) neither reads nor moves position, limit or mark, so the detector's
     * model counts these accesses as context and has nothing to report however many threads make
     * them. The input is identical to the loud row's in every respect but the overload, which
     * leaves the operation-kind distinction as the only thing deciding the outcome.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_byteBuffer_absoluteGetsShared() {
        CorpusRecorder.countBodyExecution();
        AsyncTestContext.sharedByteBufferDetector().recordAbsoluteAccess(absoluteBuffer, "get(int)");
        absoluteBuffer.get(7);
    }

    // --- FileChannelPositionRace -------------------------------------------------------------

    /**
     * Every thread reads the shared channel through the overload that advances its cursor.
     *
     * <p>The detector fires once implicit-position operations reach one channel from more than
     * one thread. Reads rather than writes, so nothing depends on what the interleaving did to
     * the file: 240 reads of 8 bytes stay inside the 4096-byte file, and a read that starts at
     * an offset another thread's read moved is exactly the hazard being recorded.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_fileChannel_implicitReadsShared() throws IOException {
        CorpusRecorder.countBodyExecution();
        AsyncTestContext.fileChannelPositionRaceDetector()
                .recordImplicitPositionAccess(implicitChannel, "read");
        implicitChannel.read(ByteBuffer.allocate(8));
    }

    /**
     * The same shared channel usage, through the positional overload.
     *
     * <p>read(ByteBuffer, position) takes an explicit offset and never consults the implicit
     * cursor, which is why it is the fix the detector's own message recommends. The detector
     * still tracks the instance, so its silence is a classification of the recorded calls
     * rather than an instance it never saw.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_fileChannel_positionalReadsShared() throws IOException {
        CorpusRecorder.countBodyExecution();
        AsyncTestContext.fileChannelPositionRaceDetector()
                .recordPositionalAccess(positionalChannel, "read");
        positionalChannel.read(ByteBuffer.allocate(8), 0L);
    }

    // --- WeakHashMapShared -------------------------------------------------------------------

    /**
     * One WeakHashMap, six threads, nothing held.
     *
     * <p>The detector fires when a WeakHashMap or IdentityHashMap is recorded from more than one
     * thread with no lock covering every access. The key is a compile-time constant, so its
     * referent is never cleared and the GC-driven expunge stays a no-op: the row measures the
     * sharing the javadoc forbids, not reference-queue behaviour the scheduler may or may not
     * trigger.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_weakHashMap_sharedAcrossThreads() {
        CorpusRecorder.countBodyExecution();
        AsyncTestContext.weakHashMapSharedDetector()
                .recordAccess(sharedWeakMap, "shared-weakmap", Thread.currentThread());
        toleratingCorruption(() -> {
            sharedWeakMap.put(WEAK_KEY, "value");
            sharedWeakMap.get(WEAK_KEY);
        });
    }

    /**
     * The same map and the same six threads, inside synchronized (guardedWeakMap).
     *
     * <p>That is the external synchronization WeakHashMap's javadoc asks for, and the record
     * happens while the monitor is held, which is when the guard probe answers truthfully. A
     * finding here would tell someone who fixed their race that the fix is as broken as the
     * bug, which is the direction that stops people using the detector at all.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_weakHashMap_guardedByItsOwnMonitor() {
        CorpusRecorder.countBodyExecution();
        synchronized (guardedWeakMap) {
            AsyncTestContext.weakHashMapSharedDetector()
                    .recordAccess(guardedWeakMap, "guarded-weakmap", Thread.currentThread());
            guardedWeakMap.put(WEAK_KEY, "value");
            guardedWeakMap.get(WEAK_KEY);
        }
    }

    private static void toleratingCorruption(Runnable operation) {
        try {
            operation.run();
        } catch (RuntimeException thrown) {
            CorpusRecorder.recordCrash(thrown);
        }
    }
}
