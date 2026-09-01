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
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.nio.CharBuffer;
import java.nio.charset.CharsetEncoder;
import java.util.Timer;
import java.util.TimerTask;
import java.util.SimpleTimeZone;
import java.util.TimeZone;
import java.util.WeakHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import javax.crypto.SecretKeyFactory;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
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

    /** The capacity both blocking-queue rows register, and the number of offers they make. */
    private static final int QUEUE_CAPACITY = 5;

    /** The algorithm label both KDF rows pass, so only the guarding differs. */
    private static final String KDF_ALGORITHM = "PBKDF2WithHmacSHA256";

    /** One CRC32 for the whole run: the loud checksum row. */
    private static final CRC32 SHARED_CHECKSUM = new CRC32();

    /** A CRC32 per thread, so no instance is ever recorded from two. */
    private static final ThreadLocal<CRC32> CONFINED_CHECKSUM =
            ThreadLocal.withInitial(CRC32::new);

    /** One Deflater for the whole run: the loud deflater row. */
    private static final Deflater SHARED_DEFLATER = new Deflater();

    /** A Deflater per thread, ended in {@code reportAndGate} with the rest of the fixtures. */
    private static final ThreadLocal<Deflater> CONFINED_DEFLATER =
            ThreadLocal.withInitial(CorpusRecordingLaneTest::newTrackedDeflater);

    /** Every confined Deflater ever handed out, so they can all be ended. */
    private static final List<Deflater> CONFINED_DEFLATERS = new CopyOnWriteArrayList<>();

    /**
     * One mutable zone for the whole run: the loud time-zone row.
     *
     * <p>A {@code SimpleTimeZone} built here rather than {@code TimeZone.getTimeZone(...)},
     * because the row's claim is about one identity reaching several threads and the factory
     * method's clone-or-cache behaviour is not something the row should be resting on.
     */
    private static final SimpleTimeZone SHARED_TIME_ZONE =
            new SimpleTimeZone(0, "corpus-shared-zone");

    /** A zone per thread, distinct by construction. */
    private static final ThreadLocal<TimeZone> CONFINED_TIME_ZONE = ThreadLocal.withInitial(
            () -> new SimpleTimeZone(0, "corpus-zone-" + Thread.currentThread().threadId()));

    /** The one factory both XML rows build from; sharing it is what the javadoc permits. */
    private static final DocumentBuilderFactory DOCUMENT_BUILDER_FACTORY =
            DocumentBuilderFactory.newInstance();

    /** A builder per thread: the documented remedy, and the silent row. */
    private static final ThreadLocal<DocumentBuilder> CONFINED_DOCUMENT_BUILDER =
            ThreadLocal.withInitial(CorpusRecordingLaneTest::newDocumentBuilder);

    /** One builder for the whole run: the loud XML row. */
    private static DocumentBuilder sharedDocumentBuilder;

    /** One derivation object for the whole run, shared by both KDF rows. */
    private static SecretKeyFactory sharedKeyFactory;

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

    /** Instances of this class are not safe for use by multiple concurrent threads: the shared row. */
    private static final CharsetEncoder SHARED_ENCODER = StandardCharsets.UTF_8.newEncoder();

    /** An encoder per thread, built from the same charset: the confined twin. */
    private static final ThreadLocal<CharsetEncoder> CONFINED_ENCODER =
            ThreadLocal.withInitial(StandardCharsets.UTF_8::newEncoder);

    /** The declared-owned pool the loud row never shuts down; reportAndGate closes it unrecorded. */
    private static ExecutorService leakedPool;

    /** One declaration for the run: the shape a consumer writes, though the record call is idempotent. */
    private final AtomicBoolean leakedPoolDeclared = new AtomicBoolean();

    /** Documented thread-safe; the fragility under test is its single task-execution thread. */
    private static Timer failingTimer;

    /** The clean twin, cancelled in reportAndGate. */
    private static Timer cleanTimer;

    /** Each timer row schedules exactly one real task for the run. */
    private final AtomicBoolean failingTimerArmed = new AtomicBoolean();

    /** The clean row's own one-shot latch. */
    private final AtomicBoolean cleanTimerArmed = new AtomicBoolean();

    /** The pool the Future rows submit to; never declared to ExecutorShutdown, closed unrecorded. */
    private static ExecutorService futuresPool;

    /** Strong references, so no ignored Future's identity key is reused within the run. */
    private static final List<Future<?>> IGNORED_FUTURES =
            Collections.synchronizedList(new ArrayList<>());

    /** The monitor both notify rows declare against; only one of them holds it. */
    private static final Object NOTIFY_MONITOR = new Object();

    /** The illegal notifyAll is really attempted once, so the JVM confirms the row's premise. */
    private final AtomicBoolean illegalNotifyProven = new AtomicBoolean();

    /**
     * What the real, unheld {@code notifyAll()} did - the loud notify row's premise, measured.
     *
     * <p>The row's rationale says the monitor is genuinely not held, and the JVM is the only
     * authority on that. {@link #theIllegalNotifyReallyThrew()} refuses the run if the call did
     * not throw, so the claim cannot outlive the behaviour it rests on.
     */
    private static final java.util.concurrent.atomic.AtomicReference<String> ILLEGAL_NOTIFY_OUTCOME =
            new java.util.concurrent.atomic.AtomicReference<>("never attempted");

    /** The stream the loud row leaks: one real file descriptor, closed unrecorded at the end. */
    private static InputStream leakedStream;

    /** The file both stream rows read; created in installRecorder, deleted in reportAndGate. */
    private static Path streamFile;

    /** One recorded open for the run: 240 leaked descriptors would exhaust the runner. */
    private final AtomicBoolean leakedStreamDeclared = new AtomicBoolean();

    /** A string literal: interned per JVM, so any unrelated code locking this text shares it. */
    private static final String INTERNED_LOCK = "corpus-shared-lock-name";

    /** The private final Object both silent lock rows use, which is the documented idiom. */
    private static final Object PRIVATE_LOCK = new Object();

    /** Integer.valueOf caches small values, so this instance is shared JVM-wide like the literal. */
    private static final Integer BOXED_LOCK = Integer.valueOf(42);

    /** The counter the get-then-set row races on; each call atomic, the pair not. */
    private final java.util.concurrent.atomic.AtomicInteger lostUpdateCounter =
            new java.util.concurrent.atomic.AtomicInteger();

    /** The twin the correct row updates with compareAndSet. */
    private final java.util.concurrent.atomic.AtomicInteger casCounter =
            new java.util.concurrent.atomic.AtomicInteger();

    /** The monitor the unguarded wait row names; distinct from the looped row's. */
    private static final Object UNLOOPED_MONITOR = new Object();

    /** The monitor whose wait is declared to sit inside its condition loop. */
    private static final Object LOOPED_MONITOR = new Object();

    /** The monitor whose wait is recorded as untimed. */
    private static final Object UNTIMED_MONITOR = new Object();

    /** The twin whose wait carries a bound and is followed by a notify. */
    private static final Object TIMED_MONITOR = new Object();

    /** One StampedLock per optimistic-read row; the pair differs in the validation result. */
    private static final java.util.concurrent.locks.StampedLock UNVALIDATED_STAMPED_LOCK =
            new java.util.concurrent.locks.StampedLock();

    private static final java.util.concurrent.locks.StampedLock VALIDATED_STAMPED_LOCK =
            new java.util.concurrent.locks.StampedLock();

    /** The lock whose read is upgraded without releasing: the deadlock the class cannot resolve. */
    private static final java.util.concurrent.locks.ReentrantReadWriteLock UPGRADED_LOCK =
            new java.util.concurrent.locks.ReentrantReadWriteLock();

    /** The twin that releases the read before attempting the write. */
    private static final java.util.concurrent.locks.ReentrantReadWriteLock RELEASED_THEN_WRITTEN_LOCK =
            new java.util.concurrent.locks.ReentrantReadWriteLock();

    /** One lambda instance for the run, executed by every thread: the shared-state row. */
    private static final Runnable SHARED_LAMBDA = () -> { };

    /**
     * A lambda per thread, so no instance is ever executed by a second one.
     *
     * <p>It has to capture something. A non-capturing lambda is a JVM-wide singleton - the
     * metafactory hands out one instance for the whole process - so
     * {@code withInitial(() -> () -> { })} gives every thread the same object, and the detector
     * keys on identity, so it reported this row as shared and was right to. Measured on JDK 26:
     * the non-capturing form yields 1 distinct instance across 4 threads, the capturing form 4.
     */
    private static final ThreadLocal<Runnable> CONFINED_LAMBDA = ThreadLocal.withInitial(() -> {
        int[] captured = new int[1];
        return () -> captured[0]++;
    });

    /** Held strongly for the run, so the weak reference below can never come back empty. */
    private static final Object STRONG_REFERENT = new Object();

    /** The reference whose read is recorded as having found its referent. */
    private static final java.lang.ref.WeakReference<Object> LIVE_REFERENCE =
            new java.lang.ref.WeakReference<>(STRONG_REFERENT);

    /** The reference the loud row records as already cleared. */
    private static final java.lang.ref.WeakReference<Object> CLEARED_REFERENCE =
            new java.lang.ref.WeakReference<>(new Object());

    /** One array whose elements every thread writes: volatile publishes the reference only. */
    private static final int[] SHARED_ARRAY = new int[8];

    /** An array per thread, so no element is ever reached by a second one. */
    private static final ThreadLocal<int[]> CONFINED_ARRAY = ThreadLocal.withInitial(() -> new int[8]);

    /** One registration per array for the run; registerArray resets nothing, but a consumer declares once. */
    private final AtomicBoolean sharedArrayRegistered = new AtomicBoolean();

    /** Declared with no bound: the queue whose backpressure is heap growth. */
    private static final java.util.concurrent.BlockingQueue<String> UNBOUNDED_QUEUE =
            new java.util.concurrent.LinkedBlockingQueue<>();

    /** The twin with a capacity, which blocks the producer instead of growing. */
    private static final java.util.concurrent.BlockingQueue<String> BOUNDED_QUEUE =
            new java.util.concurrent.ArrayBlockingQueue<>(16);

    /** A copy-on-write list under the workload it is worst at: every operation a write. */
    private static final List<String> WRITE_HEAVY_COW = new CopyOnWriteArrayList<>();

    /** The same class under the mix it was built for, many reads to one write. */
    private static final List<String> READ_HEAVY_COW = new CopyOnWriteArrayList<>();

    /** One seeding write for the run, so the read-heavy row stays read-heavy. */
    private final AtomicBoolean readHeavyCowSeeded = new AtomicBoolean();

    /** Set and never removed: on a pooled thread the value outlives every task. */
    private static final ThreadLocal<String> LEAKED_THREAD_LOCAL = new ThreadLocal<>();

    /** The twin with the remove() a correct finally block performs. */
    private static final ThreadLocal<String> CLEANED_THREAD_LOCAL = new ThreadLocal<>();

    /** The monitor the exposure row both locks on and hands out. */
    private static final Object EXPOSED_LOCK = new Object();

    /** What the silent exposure row publishes instead: a value, not the monitor. */
    private static final Object PUBLISHED_VALUE = new Object();

    /** The barrier the loud row records as broken; six parties, never awaited for real. */
    private static final java.util.concurrent.CyclicBarrier BROKEN_BARRIER =
            new java.util.concurrent.CyclicBarrier(THREADS);

    /** The twin recorded through a whole arrive-await-complete cycle. */
    private static final java.util.concurrent.CyclicBarrier COMPLETED_BARRIER =
            new java.util.concurrent.CyclicBarrier(THREADS);

    /** The lock whose tryLock is recorded as timed out. */
    private static final java.util.concurrent.locks.ReentrantLock TIMED_OUT_LOCK =
            new java.util.concurrent.locks.ReentrantLock();

    /** The twin acquired and released cleanly by every thread. */
    private static final java.util.concurrent.locks.ReentrantLock CLEAN_LOCK =
            new java.util.concurrent.locks.ReentrantLock();

    /** The phaser recorded as terminated: every later arrival stops synchronizing. */
    private static final java.util.concurrent.Phaser TERMINATED_PHASER =
            new java.util.concurrent.Phaser(1);

    /** The twin recorded advancing through a phase. */
    private static final java.util.concurrent.Phaser ADVANCING_PHASER =
            new java.util.concurrent.Phaser(1);

    /** The rendezvous whose completion carries no payload. */
    private static final java.util.concurrent.Exchanger<String> EMPTY_EXCHANGER =
            new java.util.concurrent.Exchanger<>();

    /** The twin that exchanges something real. */
    private static final java.util.concurrent.Exchanger<String> PAYLOAD_EXCHANGER =
            new java.util.concurrent.Exchanger<>();

    /** The lock the two condition rows take their conditions from. */
    private static final java.util.concurrent.locks.ReentrantLock CONDITION_LOCK =
            new java.util.concurrent.locks.ReentrantLock();

    /** Awaited with nothing ever signalling it. */
    private static final java.util.concurrent.locks.Condition UNSIGNALLED_CONDITION =
            CONDITION_LOCK.newCondition();

    /** The twin with a recorded signal behind every await. */
    private static final java.util.concurrent.locks.Condition SIGNALLED_CONDITION =
            CONDITION_LOCK.newCondition();

    /** The VarHandle stand-ins: the detector only needs non-null handle and receiver objects. */
    private static final Object PLAIN_HANDLE = new Object();

    private static final Object PLAIN_RECEIVER = new Object();

    private static final Object ATOMIC_HANDLE = new Object();

    private static final Object ATOMIC_RECEIVER = new Object();

    /** Counter behind {@link #perInvocation(String)}. */
    private static final java.util.concurrent.atomic.AtomicLong UNIQUE_KEYS =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * {@return a name unique to one body execution}
     *
     * <p>The value-lifecycle detectors accumulate over the whole run, so a silent row keyed on a
     * shared name would let one body's calls satisfy another's - a set from one invocation would
     * answer for the read in the next. Per-thread is not enough either, because a thread runs the
     * body forty times.
     *
     * @param prefix what the name describes
     */
    private static String perInvocation(String prefix) {
        return prefix + '-' + UNIQUE_KEYS.incrementAndGet();
    }

    /**
     * Releases both parked threads below.
     *
     * <p>Counted down at the very top of {@link #reportAndGate()}, before any assertion, so a
     * failing gate can never leave the non-daemon thread parked and the JVM unable to exit.
     */
    private static final CountDownLatch PARKED_THREADS_RELEASE = new CountDownLatch(1);

    /**
     * Alive and daemon: the thread the leak row records a start for and never an end.
     *
     * <p>{@code ThreadLeakDetector.analyzeLeaks()} only reports a tracked thread that is still
     * {@code isAlive()} at analysis, so this row needs a genuinely running thread rather than an
     * unstarted one. Daemon, so it cannot hold the JVM open even if the release is missed.
     */
    private static Thread parkedDaemon;

    /**
     * Alive and NOT daemon: the one kind of thread that keeps the JVM from exiting.
     *
     * <p>That is exactly what the daemon-hygiene row has to demonstrate, and exactly why the
     * release runs first in {@code reportAndGate}.
     */
    private static Thread parkedNonDaemon;

    /** Produces raw threads: default name, not daemon, no handler. */
    private static final java.util.concurrent.ThreadFactory RAW_FACTORY = Thread::new;

    /** Produces what a production factory does: named, daemon, handler installed. */
    private static final java.util.concurrent.ThreadFactory CONFIGURED_FACTORY = runnable -> {
        Thread configured = new Thread(runnable, "corpus-configured-worker");
        configured.setDaemon(true);
        configured.setUncaughtExceptionHandler((t, e) -> { });
        return configured;
    };

    /** Set on threads the body declares as pooled: the inheritance hazard. */
    private static final InheritableThreadLocal<String> POOLED_ITL = new InheritableThreadLocal<>();

    /** The twin, used under a name private to each thread and never declared pooled. */
    private static final InheritableThreadLocal<String> CONFINED_ITL = new InheritableThreadLocal<>();

    /** Read in a later task than the one that set it: the contamination row's subject. */
    private static final ThreadLocal<String> CONTAMINATING_TL = new ThreadLocal<>();

    /** The twin, read inside its own task and absent in the next. */
    private static final ThreadLocal<String> SCOPED_TL = new ThreadLocal<>();

    /** The lambda whose captured counter is read-modify-written with nothing held. */
    private static final Runnable UNGUARDED_LAMBDA = () -> { };

    /** The twin, whose read-modify-writes all name the guard the caller held. */
    private static final Runnable GUARDED_LAMBDA = () -> { };

    /** The lock the guarded lambda row holds and declares. */
    private static final Object LAMBDA_GUARD = new Object();

    /** A record whose component is mutable: final reference, mutable target. */
    record MutableBox(List<String> items) { }

    /** A record whose components are all immutable, which is deeply immutable. */
    record ImmutablePoint(int x, int y) { }

    /**
     * Shared across threads with a plain, unsynchronized list reachable through the accessor.
     *
     * <p>A plain {@code ArrayList} on purpose. The detector sorts a component into three bands,
     * not two: immutable is silent, a {@code java.util.concurrent} collection is also silent -
     * "mutable on purpose, and safely", a deliberate and correct exemption - and only an
     * unsynchronized mutable component fires. The first attempt at this row used a
     * {@code CopyOnWriteArrayList} and stayed silent, which was the row being wrong rather than
     * the detector. Nothing here mutates the list; the hazard is that the reference escapes.
     */
    private static final MutableBox LEAKY_RECORD = new MutableBox(new ArrayList<>());

    /** Shared just as widely, with nothing mutable to reach. */
    private static final ImmutablePoint SAFE_RECORD = new ImmutablePoint(1, 2);

    /** One generator for the run: SplittableRandom is documented as not thread-safe. */
    private static final java.util.SplittableRandom SHARED_SPLITTABLE =
            new java.util.SplittableRandom(42);

    /** A generator per thread, which is what split() exists for. */
    private static final ThreadLocal<java.util.SplittableRandom> CONFINED_SPLITTABLE =
            ThreadLocal.withInitial(SHARED_SPLITTABLE::split);

    /** The object whose construction the loud safety row leaves open for the run. */
    private static final Object UNDER_CONSTRUCTION = new Object();

    /** The twin whose construction is recorded as finished before anybody reads it. */
    private static final Object FULLY_CONSTRUCTED = new Object();

    /** One declaration each, because recordConstructionStart is a lifecycle, not a per-body event. */
    private final AtomicBoolean constructionOpened = new AtomicBoolean();

    private final AtomicBoolean constructionClosed = new AtomicBoolean();

    /** Sized to a thousand parties and given six: a barrier that can never trip. */
    private static final java.util.concurrent.CyclicBarrier UNREACHABLE_BARRIER =
            new java.util.concurrent.CyclicBarrier(THREADS);

    /** Sized to the parties that actually arrive. */
    private static final java.util.concurrent.CyclicBarrier REACHABLE_BARRIER =
            new java.util.concurrent.CyclicBarrier(THREADS);

    /** A pool of one with a queue of one: the sizing that rejects. */
    private static final Object TINY_POOL = new Object();

    /** A pool sized for the work. */
    private static final Object AMPLE_POOL = new Object();

    /** The read-write lock the loud row keeps reader-heavy. */
    private static final Object READER_HEAVY_LOCK = new Object();

    /** The twin whose reads and writes stay in balance. */
    private static final Object BALANCED_LOCK = new Object();

    /** The monitor the loud contention row reports contended on most attempts. */
    private static final Object CONTENDED_MONITOR = new Object();

    /** The twin taken cleanly every time. */
    private static final Object UNCONTENDED_MONITOR = new Object();

    /** Written by every thread with nothing held: the textbook data race. */
    private static final Object RACED_TARGET = new Object();

    /** The same writes made inside synchronized on the object itself. */
    private static final Object GUARDED_TARGET = new Object();

    /** One initialisation for the whole run, which is what the lazy-init idiom guarantees. */
    private final AtomicBoolean lazyFieldInitialised = new AtomicBoolean();

    /**
     * Four unstarted virtual threads, shared by the virtual-thread rows.
     *
     * <p>The lane's own workers are platform threads, so these rows have to supply the virtual
     * ones. Unstarted is enough: the four detectors read {@code isVirtual()}, {@code threadId()}
     * and {@code getName()}, all of which an unstarted instance answers, and starting them would
     * add scheduling to rows whose outcomes are supposed to be structural.
     */
    private static final List<Thread> VIRTUAL_THREADS = List.of(
            Thread.ofVirtual().name("corpus-vt-1").unstarted(() -> { }),
            Thread.ofVirtual().name("corpus-vt-2").unstarted(() -> { }),
            Thread.ofVirtual().name("corpus-vt-3").unstarted(() -> { }),
            Thread.ofVirtual().name("corpus-vt-4").unstarted(() -> { }));

    /** A pool over a virtual-thread factory: pooling what costs nothing to create. */
    private static ExecutorService pooledVirtualExecutor;

    /** The identical pool over the default platform factory. */
    private static ExecutorService pooledPlatformExecutor;

    /** The segment every thread writes the same eight bytes of. */
    private static final Object OVERLAPPING_SEGMENT = new Object();

    /** The same sharing at offsets that cannot overlap. */
    private static final Object DISJOINT_SEGMENT = new Object();

    /** A confined arena opened once and then accessed from other threads. */
    private static final Object ESCAPED_ARENA = new Object();

    /** The segment allocated in that arena. */
    private static final Object ESCAPED_SEGMENT = new Object();

    /** One declaration for the run: an arena is opened once, not once per body. */
    private final AtomicBoolean escapedArenaOpened = new AtomicBoolean();

    /** One shared cached value, so the degradation row's twin sees one instance not many. */
    private static final Object ONE_CACHED_INSTANCE = new Object();

    /** One Random for the run: thread-safe, and contended by every worker. */
    private static final java.util.Random SHARED_RANDOM = new java.util.Random(7);

    /** A Random per thread, which is what removes the contention. */
    private static final ThreadLocal<java.util.Random> CONFINED_RANDOM =
            ThreadLocal.withInitial(() -> new java.util.Random(7));

    /** Single-threaded scheduler whose task is reported as overrunning. */
    private static java.util.concurrent.ScheduledExecutorService slowScheduler;

    /** The twin whose task is reported as finishing in milliseconds. */
    private static java.util.concurrent.ScheduledExecutorService promptScheduler;

    /** The pool both fork-join rows name; only the second one joins. */
    private static final java.util.concurrent.ForkJoinPool FORK_JOIN_POOL =
            java.util.concurrent.ForkJoinPool.commonPool();

    /** Ten thousand iterations is the detector's spin threshold; recorded once for the run. */
    private final AtomicBoolean spinRecorded = new AtomicBoolean();

    /** Every recorded compare-and-set on this one fails: the contended atomic. */
    private static final java.util.concurrent.atomic.AtomicLong CONTENDED_ATOMIC =
            new java.util.concurrent.atomic.AtomicLong();

    /** The twin whose attempts all succeed. */
    private static final java.util.concurrent.atomic.AtomicLong UNCONTENDED_ATOMIC =
            new java.util.concurrent.atomic.AtomicLong();

    /** A pool of one, whose task waits on a sibling it can never let run. */
    private static final Object DEADLOCKING_POOL = new Object();

    /**
     * The twin, sized above the whole run rather than above one body.
     *
     * <p>{@code ExecutorDeadlockDetector.waitingOnSibling} and its counterpart in
     * {@code FutureBlockingDetector} only ever grow - nothing decrements them when the wait ends
     * - so the silent row has to declare a pool larger than {@code THREADS * INVOCATIONS}, or it
     * would eventually out-count its own capacity and report for a reason unrelated to the model.
     */
    private static final Object ROOMY_POOL = new Object();

    /** The same shape for the future-blocking pair. */
    private static final Object BLOCKED_POOL = new Object();

    private static final Object ROOMY_BLOCKED_POOL = new Object();

    /** The subscriber the loud Flow row signals after completing it. */
    private static final Object COMPLETED_SUBSCRIBER = new Object();

    /** What the silent executor rows declare: above the whole run, not above one body. */
    private static final int MORE_THREADS_THAN_THE_RUN = THREADS * INVOCATIONS * 10;

    /** The lock whose write stamps are never released. */
    private static final java.util.concurrent.locks.StampedLock LEAKED_STAMPED_LOCK =
            new java.util.concurrent.locks.StampedLock();

    /** The twin whose every stamp comes back. */
    private static final java.util.concurrent.locks.StampedLock RELEASED_STAMPED_LOCK =
            new java.util.concurrent.locks.StampedLock();

    /** One CSPRNG for the run: thread-safe, and contended by every worker. */
    private static final java.security.SecureRandom SHARED_SECURE_RANDOM =
            new java.security.SecureRandom();

    /** One per thread, which is what removes the contention. */
    private static final ThreadLocal<java.security.SecureRandom> CONFINED_SECURE_RANDOM =
            ThreadLocal.withInitial(java.security.SecureRandom::new);

    @BeforeAll
    static void installRecorder() throws IOException, SQLException, NoSuchAlgorithmException {
        CorpusRecorder.install();

        sharedDocumentBuilder = newDocumentBuilder();
        try {
            sharedKeyFactory = SecretKeyFactory.getInstance(KDF_ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(KDF_ALGORITHM + " is required of every JRE", e);
        }

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

        leakedPool = Executors.newFixedThreadPool(1);
        futuresPool = Executors.newFixedThreadPool(2);
        failingTimer = new Timer("corpus-failing-timer", true);
        cleanTimer = new Timer("corpus-clean-timer", true);

        // Both rows need a thread that is genuinely alive at analysis: the leak detector only
        // reports a tracked thread that is still isAlive(), and a non-daemon thread is the one
        // kind that holds the JVM open. They park until reportAndGate releases them.
        parkedDaemon = new Thread(CorpusRecordingLaneTest::awaitRelease, "corpus-parked-daemon");
        parkedDaemon.setDaemon(true);
        parkedDaemon.start();
        parkedNonDaemon = new Thread(CorpusRecordingLaneTest::awaitRelease, "corpus-parked-user");
        parkedNonDaemon.setDaemon(false);
        parkedNonDaemon.start();

        pooledVirtualExecutor = new java.util.concurrent.ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, new java.util.concurrent.LinkedBlockingQueue<>(),
                Thread.ofVirtual().factory());
        pooledPlatformExecutor = new java.util.concurrent.ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, new java.util.concurrent.LinkedBlockingQueue<>(),
                Executors.defaultThreadFactory());

        slowScheduler = Executors.newSingleThreadScheduledExecutor();
        promptScheduler = Executors.newSingleThreadScheduledExecutor();

        streamFile = Files.createTempFile("corpus-stream", ".bin");
        Files.write(streamFile, new byte[256]);
        leakedStream = Files.newInputStream(streamFile);
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
        // First, before any assertion. The measurement is over, and a gate that fails below must
        // not be able to leave the non-daemon thread parked with the JVM unable to exit.
        PARKED_THREADS_RELEASE.countDown();
        thePooledRowsPremiseHeld();
        theIllegalNotifyReallyThrew();
        pool.close();
        hoistedPool.close();
        implicitChannel.close();
        positionalChannel.close();
        // Real cleanup, deliberately unrecorded: the loud executor row's claim is that no
        // shutdown was RECORDED during the run, and the measurement is over by the time this
        // runs. Closing it here keeps the fork's thread count honest without touching the row.
        leakedPool.shutdownNow();
        futuresPool.shutdownNow();
        pooledVirtualExecutor.shutdownNow();
        pooledPlatformExecutor.shutdownNow();
        slowScheduler.shutdownNow();
        promptScheduler.shutdownNow();
        cleanTimer.cancel();
        failingTimer.cancel();
        leakedStream.close();
        Files.deleteIfExists(streamFile);
        Files.deleteIfExists(channelFile);
        SHARED_DEFLATER.end();
        CONFINED_DEFLATERS.forEach(Deflater::end);
        CorpusLane lane = CorpusLane.current();
        Path report = CorpusReport.writeRecording(
                CorpusRecorder.findings(), THREADS, INVOCATIONS, lane);
        System.out.println("Corpus recording-lane report written to " + report.toAbsolutePath());
        System.out.println(CorpusReport.recordingSummary(CorpusRecorder.findings(), lane));
        CorpusGates.checkPairLane(
                CorpusRecorder.findings(), lane, CorpusRecordingLaneTest.class);
    }

    /**
     * {@return a DocumentBuilder from the one shared factory}
     *
     * <p>The factory is shared deliberately. Its javadoc says it is not guaranteed to be thread
     * safe and that an application should use one builder per thread; sharing the factory to make
     * per-thread builders is the documented shape, and the silent row would be measuring
     * something else if it built a factory of its own too.
     */
    /**
     * {@return a Deflater the run will remember to end}
     *
     * <p>A Deflater holds a native stream that only {@code end()} releases. The corpus ships a
     * resource-leak detector, so leaving its own fixtures unreleased would be a poor look as well
     * as a leak.
     */
    private static Deflater newTrackedDeflater() {
        Deflater deflater = new Deflater();
        CONFINED_DEFLATERS.add(deflater);
        return deflater;
    }

    private static DocumentBuilder newDocumentBuilder() {
        try {
            return DOCUMENT_BUILDER_FACTORY.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("the default parser configuration must be usable", e);
        }
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

    /**
     * Refuses a pass the loud notify row would not have earned.
     *
     * <p>Its 240 findings all rest on one premise: that the recording thread genuinely does not
     * hold {@code NOTIFY_MONITOR}. The JVM is the authority on that, and this asks it - the row
     * really calls {@code notifyAll()} outside the monitor once, and {@code Object}'s javadoc
     * says that must throw {@code IllegalMonitorStateException}. Without this, a row that had
     * quietly become synchronized would still report, and every finding would be describing
     * something other than what the rationale claims.
     */
    private static void theIllegalNotifyReallyThrew() {
        assertEquals("IllegalMonitorStateException", ILLEGAL_NOTIFY_OUTCOME.get(),
                "the loud notify row claims the monitor is not held, and notifyAll outside a "
                        + "monitor must throw IllegalMonitorStateException. The JVM said: "
                        + ILLEGAL_NOTIFY_OUTCOME.get());
    }

    // --- LatchMisuse and BlockingQueue -------------------------------------------------------

    /**
     * Registers a latch of one and counts it down twice.
     *
     * <p>Both subjects in this section are created inside the body rather than held in a field.
     * One {@code AsyncTestContext} serves every execution of every round, so a registration made
     * once would collect 240 executions' worth of counts and report on arithmetic that has
     * nothing to do with what the row is claiming.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_latch_countedDownPastItsCount() {
        CountDownLatch latch = new CountDownLatch(1);
        AsyncTestContext.latchMisuseDetector().registerLatch(latch, "corpus-latch", 1);
        AsyncTestContext.latchMisuseDetector().recordCountDown(latch);
        AsyncTestContext.latchMisuseDetector().recordCountDown(latch);
        AsyncTestContext.latchMisuseDetector().recordAwait(latch);
    }

    /** The same registration and await, counted down exactly once. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_latch_countedDownExactly() {
        CountDownLatch latch = new CountDownLatch(1);
        AsyncTestContext.latchMisuseDetector().registerLatch(latch, "corpus-latch", 1);
        AsyncTestContext.latchMisuseDetector().recordCountDown(latch);
        AsyncTestContext.latchMisuseDetector().recordAwait(latch);
    }

    /**
     * Fills a queue to its registered capacity and never drains it.
     *
     * <p>The detector reads the live {@code size()} at each recorded call, so the peak is a
     * function of the interleaving unless nothing removes anything. Nothing here does, which makes
     * the peak monotone and the outcome the same however the threads were scheduled.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_blockingQueue_filledToCapacity() {
        BlockingQueue<String> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        AsyncTestContext.blockingQueueDetector()
                .registerQueue(queue, "corpus-queue", QUEUE_CAPACITY);
        for (int i = 0; i < QUEUE_CAPACITY; i++) {
            boolean accepted = queue.offer("item");
            AsyncTestContext.blockingQueueDetector()
                    .recordOffer(queue, "corpus-queue", accepted);
        }
    }

    /** The same capacity, with every offer followed by a poll, so the peak stays at one. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_blockingQueue_drainedAsItFilled() {
        BlockingQueue<String> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        AsyncTestContext.blockingQueueDetector()
                .registerQueue(queue, "corpus-queue", QUEUE_CAPACITY);
        for (int i = 0; i < QUEUE_CAPACITY; i++) {
            boolean accepted = queue.offer("item");
            AsyncTestContext.blockingQueueDetector()
                    .recordOffer(queue, "corpus-queue", accepted);
            boolean taken = queue.poll() != null;
            AsyncTestContext.blockingQueueDetector().recordPoll(queue, "corpus-queue", taken);
        }
    }

    // --- The rest of the shared-instance family ----------------------------------------------

    /** Every thread records against the one CRC32, holding nothing. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_checksum_sharedAcrossThreads() {
        SHARED_CHECKSUM.update(PAYLOAD_BYTES);
        AsyncTestContext.sharedChecksumDetector()
                .recordAccess(SHARED_CHECKSUM, "update", Thread.currentThread());
    }

    /** A CRC32 per thread, through the same recorded call. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_checksum_oneInstancePerThread() {
        CRC32 mine = CONFINED_CHECKSUM.get();
        mine.update(PAYLOAD_BYTES);
        AsyncTestContext.sharedChecksumDetector()
                .recordAccess(mine, "update", Thread.currentThread());
    }

    /** Every thread records against the one Deflater. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_deflater_sharedAcrossThreads() {
        AsyncTestContext.sharedDeflaterDetector()
                .recordAccess(SHARED_DEFLATER, "corpus-deflater", Thread.currentThread());
    }

    /** A Deflater per thread, through the same overload. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_deflater_oneInstancePerThread() {
        AsyncTestContext.sharedDeflaterDetector()
                .recordAccess(CONFINED_DEFLATER.get(), "corpus-deflater", Thread.currentThread());
    }

    /**
     * Every thread records against the one derivation object, unguarded.
     *
     * <p>A {@code SecretKeyFactory} stands in for {@code javax.crypto.KDF}, which the detector's
     * own javadoc quotes but which does not exist before JDK 24 - and this corpus compiles and
     * runs on 21. The detector's parameter is {@code Object} for exactly that reason, so the
     * substitution is the one its author anticipated rather than a way around a type.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_kdf_sharedAcrossThreads() {
        AsyncTestContext.sharedKdfDetector()
                .recordAccess(sharedKeyFactory, KDF_ALGORITHM, "deriveKey",
                        Thread.currentThread());
    }

    /** The same one object, with every record made inside its own monitor. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_kdf_guardedByItsOwnMonitor() {
        synchronized (sharedKeyFactory) {
            AsyncTestContext.sharedKdfDetector()
                    .recordAccess(sharedKeyFactory, KDF_ALGORITHM, "deriveKey",
                            Thread.currentThread());
        }
    }

    /** Every thread mutates and records the one zone. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_timeZone_mutatedByEveryThread() {
        SHARED_TIME_ZONE.setRawOffset(3_600_000);
        AsyncTestContext.sharedTimeZoneDetector()
                .recordMutation(SHARED_TIME_ZONE, "setRawOffset", Thread.currentThread());
    }

    /** Each thread mutates and records its own zone. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_timeZone_oneInstancePerThread() {
        TimeZone mine = CONFINED_TIME_ZONE.get();
        mine.setRawOffset(3_600_000);
        AsyncTestContext.sharedTimeZoneDetector()
                .recordMutation(mine, "setRawOffset", Thread.currentThread());
    }

    /** Every thread records against the one DocumentBuilder. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_xmlParser_sharedAcrossThreads() {
        sharedDocumentBuilder.reset();
        AsyncTestContext.sharedXmlParserDetector()
                .recordAccess(sharedDocumentBuilder, "DocumentBuilder", Thread.currentThread());
    }

    /**
     * A builder per thread, which is what the factory javadoc tells you to do.
     *
     * <p>The factory stays shared, which the same javadoc permits: it is the builder that is not
     * thread-safe. Sharing the factory and confining the builder must read as correct, or the
     * detector is flagging the API rather than its misuse.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_xmlParser_oneInstancePerThread() {
        DocumentBuilder mine = CONFINED_DOCUMENT_BUILDER.get();
        mine.reset();
        AsyncTestContext.sharedXmlParserDetector()
                .recordAccess(mine, "DocumentBuilder", Thread.currentThread());
    }

    // --- StaticInitDeadlock ------------------------------------------------------------------

    /** The two classes the class-init rows name; identities only, never actually initialised. */
    private static final class Alpha {
    }

    private static final class Beta {
    }

    /**
     * Half the threads hold Alpha and ask for Beta, the other half the reverse.
     *
     * <p>{@code findCycles} walks waiter to holder to waiter and reports when the walk comes back
     * round. Two threads on opposite sides close it, and a self-edge is skipped, so the split by
     * thread id is what makes the cycle rather than the volume of calls.
     *
     * <p>The recorded path is used rather than the live sampler on purpose. The sampler wants two
     * threads genuinely stuck inside a {@code <clinit>}, and a class whose initialiser never
     * returns can never be initialised again for the life of the classloader - the corpus would be
     * poisoning itself to observe one finding.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_classInit_twoThreadsWaitingOnEachOther() {
        Thread me = Thread.currentThread();
        boolean alphaSide = me.threadId() % 2 == 0;
        AsyncTestContext.staticInitDeadlockDetector()
                .recordInitStart(alphaSide ? Alpha.class : Beta.class, me);
        AsyncTestContext.staticInitDeadlockDetector()
                .recordInitRequest(alphaSide ? Beta.class : Alpha.class, me);
    }

    /** The same two calls, with each thread finishing the initialiser it started. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_classInit_eachInitialiserCompleted() {
        Thread me = Thread.currentThread();
        boolean alphaSide = me.threadId() % 2 == 0;
        Class<?> mine = alphaSide ? Alpha.class : Beta.class;
        AsyncTestContext.staticInitDeadlockDetector().recordInitStart(mine, me);
        AsyncTestContext.staticInitDeadlockDetector()
                .recordInitRequest(alphaSide ? Beta.class : Alpha.class, me);
        AsyncTestContext.staticInitDeadlockDetector().recordInitEnd(mine, me);
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

    // --- SharedCharsetCoder ------------------------------------------------------------------

    /**
     * One UTF-8 encoder, six threads, nothing held.
     *
     * <p>The detector fires when one coder is recorded from more than one thread with an empty
     * candidate lock set; six threads on a barrier meet both halves by construction. The bodies
     * really drive the state machine - reset() then encode() - and CharsetEncoder's javadoc
     * says instances are not safe for use by multiple concurrent threads, so an
     * IllegalStateException out of an interleaved transition is the subject corrupting, which
     * the lane tolerates and counts rather than fails on.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_charsetEncoder_sharedAcrossThreads() {
        CorpusRecorder.countBodyExecution();
        AsyncTestContext.sharedCharsetCoderDetector()
                .recordAccess(SHARED_ENCODER, "encode", Thread.currentThread());
        toleratingCorruption(() -> {
            SHARED_ENCODER.reset();
            SHARED_ENCODER.encode(CharBuffer.wrap("corpus"), ByteBuffer.allocate(16), true);
        });
    }

    /**
     * An encoder per thread, which is what the detector's own first fix looks like recorded.
     *
     * <p>Same charset, same calls, same number of recordings; no instance is ever recorded from
     * a second thread, so the rule's first clause is never met. This is the direction that
     * catches a detector keyed on the coder class rather than the instance, which would report
     * six correct threads as a race.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_charsetEncoder_encoderPerThread() {
        CorpusRecorder.countBodyExecution();
        CharsetEncoder mine = CONFINED_ENCODER.get();
        AsyncTestContext.sharedCharsetCoderDetector()
                .recordAccess(mine, "encode", Thread.currentThread());
        mine.reset();
        mine.encode(CharBuffer.wrap("corpus"), ByteBuffer.allocate(16), true);
    }

    // --- ExecutorShutdown --------------------------------------------------------------------

    /**
     * One declared-owned pool, really submitted to by every body, never shut down.
     *
     * <p>The detector's model is declared ownership: recordExecutorCreated means this scope
     * owns the close. Tasks are recorded and submitted for real, no shutdown is ever recorded,
     * and the finding follows from those flags alone - no interleaving can change what was
     * never called. reportAndGate closes the pool after the measurement, unrecorded, so the
     * fork does not carry the leak the row exists to demonstrate.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_executor_neverShutDown() {
        CorpusRecorder.countBodyExecution();
        if (leakedPoolDeclared.compareAndSet(false, true)) {
            AsyncTestContext.executorShutdownMonitor()
                    .recordExecutorCreated(leakedPool, "leaked-pool");
        }
        AsyncTestContext.executorShutdownMonitor().recordTaskSubmitted(leakedPool);
        leakedPool.submit(() -> { });
    }

    /**
     * A fresh pool per body execution, run through the whole protocol the detector prescribes.
     *
     * <p>Create, declare, submit, shutdown, awaitTermination - each recorded call for call, so
     * every tracked instance ends with both lifecycle flags set and the detector's silence is
     * the model clearing a completed lifecycle. Per body rather than one shared pool for the
     * same reason as the released-ByteBuf row: a shared instance's flags would be whatever the
     * last writer left, and the row must not depend on which write that was.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_executor_shutdownAndAwaited() throws InterruptedException {
        CorpusRecorder.countBodyExecution();
        ExecutorService mine = Executors.newFixedThreadPool(1);
        AsyncTestContext.executorShutdownMonitor().recordExecutorCreated(mine, "closed-pool");
        AsyncTestContext.executorShutdownMonitor().recordTaskSubmitted(mine);
        mine.submit(() -> { });
        mine.shutdown();
        AsyncTestContext.executorShutdownMonitor().recordShutdownCalled(mine, false);
        mine.awaitTermination(5, TimeUnit.SECONDS);
        AsyncTestContext.executorShutdownMonitor().recordAwaitTerminationCalled(mine);
    }

    // --- Timer -------------------------------------------------------------------------------

    /**
     * A real TimerTask records its uncaught exception, then throws it.
     *
     * <p>The exception really terminates the timer's single task-execution thread, which is the
     * failure Timer's own javadoc calls terminating unexpectedly: every remaining task is
     * cancelled and nothing is reported. The detector's thread-death claim follows from the one
     * recorded exception, and the arming body awaits the task before returning, so the record
     * precedes analysis by construction rather than by schedule. The detector reference is
     * captured on the worker thread because the timer's own thread has no AsyncTestContext.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_timer_taskExceptionKillsThread() throws InterruptedException {
        CorpusRecorder.countBodyExecution();
        if (failingTimerArmed.compareAndSet(false, true)) {
            var monitor = AsyncTestContext.timerMonitor();
            monitor.registerTimer(failingTimer, "failing-timer");
            monitor.recordTaskSchedule(failingTimer, "failing-timer", "boom");
            CountDownLatch recorded = new CountDownLatch(1);
            failingTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    monitor.recordTaskException(failingTimer, "failing-timer", "boom",
                            new IllegalStateException("corpus-timer-boom"));
                    recorded.countDown();
                    throw new IllegalStateException("corpus-timer-boom");
                }
            }, 0);
            assertTrue(recorded.await(10, TimeUnit.SECONDS),
                    "the throwing task must record its exception before the arming body returns, "
                            + "or the MUST_FIRE claim would depend on the timer thread's schedule");
        }
    }

    /**
     * The same schedule-and-complete lifecycle on a second timer, with nothing thrown.
     *
     * <p>recordTaskRun is deliberately not called: the run-to-complete distance is judged
     * against a 100 ms wall-clock threshold, and a MUST_STAY_SILENT row must not be breakable
     * by a GC pause between two adjacent calls. Thread death is the only claim the detector can
     * make from what is recorded here, so the silence is structural.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_timer_tasksCompleteWithoutException() throws InterruptedException {
        CorpusRecorder.countBodyExecution();
        if (cleanTimerArmed.compareAndSet(false, true)) {
            var monitor = AsyncTestContext.timerMonitor();
            monitor.registerTimer(cleanTimer, "clean-timer");
            monitor.recordTaskSchedule(cleanTimer, "clean-timer", "tick");
            CountDownLatch completed = new CountDownLatch(1);
            cleanTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    monitor.recordTaskComplete(cleanTimer, "clean-timer", "tick");
                    completed.countDown();
                }
            }, 0);
            assertTrue(completed.await(10, TimeUnit.SECONDS),
                    "the clean task must have completed and recorded before the arming body "
                            + "returns, or the silent row would be silent for lack of input");
        }
    }

    // --- FutureIgnored -----------------------------------------------------------------------

    /**
     * Every body submits a real task and records the Future; no body ever inspects one.
     *
     * <p>The detector keeps one boolean per submitted Future, and the finding follows from the
     * call that never happens, so no interleaving can remove it. The futures are held strongly
     * for the run: the detector keys instances by identity hash, and a collected Future could
     * hand its key to a later one.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_future_submittedAndNeverInspected() {
        CorpusRecorder.countBodyExecution();
        Future<?> ignored = futuresPool.submit(() -> { });
        AsyncTestContext.futureIgnoredDetector()
                .recordSubmit(ignored, "ignored-task", Thread.currentThread());
        IGNORED_FUTURES.add(ignored);
    }

    /**
     * The same submissions, each followed by a recorded inspection and a real get().
     *
     * <p>Retrieval is the fix the detector's own message prescribes. Each Future ends inspected
     * before the body returns, so every record the detector holds at analysis says the caller
     * looked, and a finding here would report every correctly awaited task in existence.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_future_inspectedAfterSubmit() throws Exception {
        CorpusRecorder.countBodyExecution();
        Future<?> awaited = futuresPool.submit(() -> { });
        AsyncTestContext.futureIgnoredDetector()
                .recordSubmit(awaited, "inspected-task", Thread.currentThread());
        AsyncTestContext.futureIgnoredDetector()
                .recordInspect(awaited, Thread.currentThread());
        awaited.get();
    }

    // --- NotifyWithoutMonitor ----------------------------------------------------------------

    /**
     * Declares a notifyAll on a monitor this thread does not hold.
     *
     * <p>The detector samples {@code Thread.holdsLock} as the attempt is recorded, so the finding
     * follows from where the call sits rather than from any interleaving. The row does not stop
     * at declaring it: once for the run it really calls {@code notifyAll()} outside the monitor
     * and records the {@code IllegalMonitorStateException} the JVM throws, so the premise behind
     * all 240 findings is verified by the platform rather than asserted by this comment. Once
     * rather than every body, because 240 identical crashes would bury the report.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_notify_withoutHoldingTheMonitor() {
        CorpusRecorder.countBodyExecution();
        AsyncTestContext.notifyWithoutMonitorDetector()
                .recordNotifyAttempt(NOTIFY_MONITOR, "unheld-monitor");
        if (illegalNotifyProven.compareAndSet(false, true)) {
            try {
                NOTIFY_MONITOR.notifyAll();
                ILLEGAL_NOTIFY_OUTCOME.set("returned normally");
            } catch (IllegalMonitorStateException thrownByTheJvm) {
                ILLEGAL_NOTIFY_OUTCOME.set("IllegalMonitorStateException");
                CorpusRecorder.recordCrash(thrownByTheJvm);
            }
        }
    }

    /**
     * The same declaration on the same monitor, made inside {@code synchronized}.
     *
     * <p>Identical evidence apart from the one thing the detector's probe reads, and the
     * {@code notifyAll()} that follows is real: the JVM accepts it, which is what makes the
     * silence correct rather than lucky. A detector that reported here would fire on almost
     * every legal wait/notify in existence.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_notify_holdingTheMonitor() {
        CorpusRecorder.countBodyExecution();
        synchronized (NOTIFY_MONITOR) {
            AsyncTestContext.notifyWithoutMonitorDetector()
                    .recordNotifyAttempt(NOTIFY_MONITOR, "held-monitor");
            NOTIFY_MONITOR.notifyAll();
        }
    }

    // --- InterruptSwallowing -----------------------------------------------------------------

    /**
     * Catches a real {@code InterruptedException} and leaves the flag cleared.
     *
     * <p>Self-interrupt then sleep, so the exception is deterministic rather than timed: the
     * flag is already set when {@code sleep} is entered, and the JDK clears it on throw. Leaving
     * it cleared is the swallow, and every layer above loses the cancellation signal.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_interruptedException_swallowed() {
        CorpusRecorder.countBodyExecution();
        Thread.currentThread().interrupt();
        try {
            Thread.sleep(1);
        } catch (InterruptedException e) {
            AsyncTestContext.interruptSwallowingDetector()
                    .recordCatch(Thread.currentThread(), "CorpusRecordingLaneTest.swallowed", false);
        }
    }

    /**
     * The identical catch, with the flag restored before the record.
     *
     * <p>{@code Thread.currentThread().interrupt()} is the fix the detector's own message
     * prescribes, and the record says so. The flag is then cleared before the body returns:
     * it is the fix under test, not something to hand to the runner's barrier, and clearing it
     * after the record cannot change what the detector already saw.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_interruptedException_flagRestored() {
        CorpusRecorder.countBodyExecution();
        Thread.currentThread().interrupt();
        try {
            Thread.sleep(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            AsyncTestContext.interruptSwallowingDetector()
                    .recordCatch(Thread.currentThread(), "CorpusRecordingLaneTest.restored", true);
        }
        Thread.interrupted();
    }

    // --- StreamClosing -----------------------------------------------------------------------

    /**
     * One real file-backed stream, recorded open for the run and never recorded closed.
     *
     * <p>Still open when the run is analysed, which is the leaked descriptor the detector
     * exists for, and the outcome follows from the close that never happens. One instance
     * rather than one per body: the leak is the point, and 240 of them would exhaust the runner
     * rather than demonstrate anything. {@code reportAndGate} closes it unrecorded, after the
     * measurement.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_inputStream_openedAndNeverClosed() {
        CorpusRecorder.countBodyExecution();
        if (leakedStreamDeclared.compareAndSet(false, true)) {
            AsyncTestContext.streamClosingDetector()
                    .recordStreamOpened(leakedStream, "leaked-stream");
        }
    }

    /**
     * A fresh stream per body, closed and recorded closed by the thread that opened it.
     *
     * <p>That clears both rules this detector applies: nothing is left open, and no stream is
     * closed by a thread other than its opener. Per body rather than shared, so at most one
     * descriptor per worker is ever live and the concurrent-open ceiling is never approached.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_inputStream_closedInTheOpeningThread() throws IOException {
        CorpusRecorder.countBodyExecution();
        InputStream mine = Files.newInputStream(streamFile);
        AsyncTestContext.streamClosingDetector().recordStreamOpened(mine, "closed-stream");
        mine.read();
        mine.close();
        AsyncTestContext.streamClosingDetector().recordStreamClosed(mine, "closed-stream");
    }

    // --- The blocking-inside-a-guard family -------------------------------------------------
    //
    // Three detectors, one shape: a blocking call is unremarkable on its own and a hazard while
    // something is held. Each pair records the identical calls and moves the block outside the
    // region, so what separates them is position in the sequence and nothing else.

    /**
     * A blocking wait recorded while a monitor is held.
     *
     * <p>The blocked thread keeps a monitor nobody else can take, which is the lockout. The
     * outcome follows from the order of the three recorded calls, so no interleaving can change
     * it.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_blockingCall_insideAMonitor() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.nestedMonitorLockoutDetector();
        Object monitor = new Object();
        synchronized (monitor) {
            detector.recordMonitorAcquired(monitor);
            detector.recordBlockingOperationAttempted("Future.get");
            detector.recordMonitorReleased(monitor);
        }
    }

    /** The identical calls with the release moved before the block: the fix, and ordinary code. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_blockingCall_afterReleasingTheMonitor() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.nestedMonitorLockoutDetector();
        Object monitor = new Object();
        synchronized (monitor) {
            detector.recordMonitorAcquired(monitor);
        }
        detector.recordMonitorReleased(monitor);
        detector.recordBlockingOperationAttempted("Future.get");
    }

    /**
     * A blocking call recorded between ForkJoinTask entry and exit.
     *
     * <p>A pool worker parked on anything but its own join starves the pool it belongs to, which
     * is the reason {@code ForkJoinPool.managedBlock} exists.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_blockingCall_insideAForkJoinTask() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.forkJoinTaskBlockingDetector();
        Thread self = Thread.currentThread();
        detector.recordForkJoinTaskEntered(self);
        detector.recordBlockingCallAttempted(self, "Future.get");
        detector.recordForkJoinTaskExited(self);
    }

    /** The same calls with the block after the exit: blocking on a plain thread is not a defect. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_blockingCall_afterLeavingTheForkJoinTask() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.forkJoinTaskBlockingDetector();
        Thread self = Thread.currentThread();
        detector.recordForkJoinTaskEntered(self);
        detector.recordForkJoinTaskExited(self);
        detector.recordBlockingCallAttempted(self, "Future.get");
    }

    /**
     * A join recorded inside a completion callback.
     *
     * <p>It blocks the thread that is supposed to be running continuations, so every other stage
     * sharing that thread waits behind it. The class is thread-safe and the caller is wrong.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_blockingCall_insideACompletableFutureCallback() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.cfBlockingCallbackDetector();
        Thread self = Thread.currentThread();
        detector.recordEnterCallback("thenApply", self);
        detector.recordBlockingCall(self, "join");
        detector.recordExitCallback(self);
    }

    /** The identical join recorded after the callback returned, which is where waiting belongs. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_blockingCall_afterTheCallbackReturned() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.cfBlockingCallbackDetector();
        Thread self = Thread.currentThread();
        detector.recordEnterCallback("thenApply", self);
        detector.recordExitCallback(self);
        detector.recordBlockingCall(self, "join");
    }

    // --- The lock-object family --------------------------------------------------------------

    /**
     * The monitor is a string literal, and literals are interned for the whole JVM.
     *
     * <p>Unrelated code locking the same text shares this lock with neither side able to see the
     * other. {@code String} is immutable and thread-safe; using one as a monitor is the defect.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_synchronized_onAnInternedLiteral() {
        CorpusRecorder.countBodyExecution();
        synchronized (INTERNED_LOCK) {
            AsyncTestContext.synchronizedOnLiteralDetector()
                    .recordMonitorAcquired(INTERNED_LOCK, Thread.currentThread(), "corpus-literal");
        }
    }

    /** The same acquisition on a private final Object: the documented idiom, unreachable by name. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_synchronized_onAPrivateLockObject() {
        CorpusRecorder.countBodyExecution();
        synchronized (PRIVATE_LOCK) {
            AsyncTestContext.synchronizedOnLiteralDetector()
                    .recordMonitorAcquired(PRIVATE_LOCK, Thread.currentThread(), "corpus-private");
        }
    }

    /**
     * The monitor is a boxed {@code Integer}, and {@code Integer.valueOf} caches small values.
     *
     * <p>Two unrelated places boxing the same number get the same object, so the sharing is
     * invisible at the call site - which is exactly what makes it worth reporting.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_lock_onABoxedInteger() {
        CorpusRecorder.countBodyExecution();
        synchronized (BOXED_LOCK) {
            AsyncTestContext.boxedPrimitiveLockDetector()
                    .recordLockAcquire(BOXED_LOCK, Thread.currentThread(), "corpus-boxed");
        }
    }

    /** The same acquisition on a private Object with no cache behind it. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_lock_onAPrivateObject() {
        CorpusRecorder.countBodyExecution();
        synchronized (PRIVATE_LOCK) {
            AsyncTestContext.boxedPrimitiveLockDetector()
                    .recordLockAcquire(PRIVATE_LOCK, Thread.currentThread(), "corpus-private");
        }
    }

    // --- AtomicNonAtomicUpdate ----------------------------------------------------------------

    /**
     * A get and a set recorded as one read-modify-write from six threads.
     *
     * <p>Each call is atomic and the sequence is not: an update landing between them is
     * overwritten and lost, which is the reason {@code compareAndSet} exists.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_atomicInteger_getThenSet() {
        CorpusRecorder.countBodyExecution();
        Thread self = Thread.currentThread();
        var detector = AsyncTestContext.atomicNonAtomicUpdateDetector();
        detector.recordGet(lostUpdateCounter, "lost-update", self);
        int seen = lostUpdateCounter.get();
        detector.recordSet(lostUpdateCounter, "lost-update", self);
        lostUpdateCounter.set(seen + 1);
    }

    /** The same read followed by a recorded compare-and-set: the primitive the finding names. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_atomicInteger_getThenCompareAndSet() {
        CorpusRecorder.countBodyExecution();
        Thread self = Thread.currentThread();
        var detector = AsyncTestContext.atomicNonAtomicUpdateDetector();
        detector.recordGet(casCounter, "cas-update", self);
        int seen = casCounter.get();
        detector.recordCas(casCounter, "cas-update", self);
        casCounter.compareAndSet(seen, seen + 1);
    }

    // --- SpuriousWakeup -----------------------------------------------------------------------

    /**
     * A wait recorded as not guarded by a condition loop.
     *
     * <p>{@code Object.wait}'s javadoc says a wait may return with no notify at all and that
     * callers must re-check their condition in a loop. A caller treating the return as the
     * condition proceeds on a state that never held, whatever the schedule did.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_wait_withoutALoop() {
        CorpusRecorder.countBodyExecution();
        AsyncTestContext.spuriousWakeupHazardDetector()
                .recordWait(UNLOOPED_MONITOR, "unlooped", false, Thread.currentThread());
    }

    /** The same wait declared as sitting inside its condition loop: the shape the javadoc prints. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_wait_insideAConditionLoop() {
        CorpusRecorder.countBodyExecution();
        AsyncTestContext.spuriousWakeupHazardDetector()
                .recordWait(LOOPED_MONITOR, "looped", true, Thread.currentThread());
    }

    // --- MdcContextLeak -----------------------------------------------------------------------

    /**
     * The task ends holding a diagnostic key it did not start with.
     *
     * <p>On a pooled thread that key is inherited by whatever task runs next, which is how one
     * request's id ends up on another request's log lines.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_mdc_keyLeftBehindAtTaskEnd() {
        CorpusRecorder.countBodyExecution();
        Thread self = Thread.currentThread();
        var detector = AsyncTestContext.mdcContextLeakDetector();
        detector.recordTaskStart(self, Map.of());
        detector.recordTaskEnd(self, Map.of("requestId", "corpus-" + self.threadId()));
    }

    /** The same task ending with exactly the context it began with: the finally-block guarantee. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_mdc_contextClearedBeforeTaskEnd() {
        CorpusRecorder.countBodyExecution();
        Thread self = Thread.currentThread();
        var detector = AsyncTestContext.mdcContextLeakDetector();
        Map<String, String> onEntry = Map.of("requestId", "corpus-" + self.threadId());
        detector.recordTaskStart(self, onEntry);
        detector.recordTaskEnd(self, onEntry);
    }

    // --- The wait/notify protocol family ------------------------------------------------------

    /** An untimed wait: the thread parks until somebody else chooses to release it. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_wait_withNoTimeout() {
        CorpusRecorder.countBodyExecution();
        AsyncTestContext.waitTimeoutDetector()
                .recordInfiniteWait(UNTIMED_MONITOR, "untimed", Thread.currentThread().getName());
    }

    /** The same wait with a bound and a notify behind it: the version that recovers on its own. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_wait_withATimeoutAndANotify() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.waitTimeoutDetector();
        detector.recordTimedWait(TIMED_MONITOR, "timed", Thread.currentThread().getName(), 50L);
        detector.recordNotifyAll(TIMED_MONITOR, "timed");
    }

    /**
     * A notify on a condition nobody recorded waiting for.
     *
     * <p>A signal sent before its waiter arrives is not queued anywhere; it is lost, and the
     * waiter that arrives next blocks for a notification that has already happened.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_notify_withNobodyWaiting() {
        CorpusRecorder.countBodyExecution();
        AsyncTestContext.missedSignalDetector().recordNotify("orphan-condition");
    }

    /** The same notify with a recorded wait before it and a wakeup after it: the whole handshake. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_notify_afterAWaiterArrived() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.missedSignalDetector();
        detector.recordWait("paired-condition");
        detector.recordNotify("paired-condition");
        detector.recordWakeup("paired-condition");
    }

    /**
     * An optimistic read whose validation comes back false.
     *
     * <p>{@code StampedLock}'s optimistic mode is documented as valid only once {@code validate}
     * confirms the stamp, so a read used after a failed validation saw a value mid-write.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_optimisticRead_usedWithoutValidating() {
        CorpusRecorder.countBodyExecution();
        Thread self = Thread.currentThread();
        var detector = AsyncTestContext.optimisticReadValidationDetector();
        long stamp = UNVALIDATED_STAMPED_LOCK.tryOptimisticRead();
        detector.recordOptimisticReadStarted(UNVALIDATED_STAMPED_LOCK, stamp, self);
        detector.recordDataAccessed(UNVALIDATED_STAMPED_LOCK, stamp, self, "balance");
        detector.recordValidateCalled(UNVALIDATED_STAMPED_LOCK, stamp, false, self);
    }

    /** The identical three calls with a validation that succeeds: the documented protocol. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_optimisticRead_validatedBeforeUse() {
        CorpusRecorder.countBodyExecution();
        Thread self = Thread.currentThread();
        var detector = AsyncTestContext.optimisticReadValidationDetector();
        long stamp = VALIDATED_STAMPED_LOCK.tryOptimisticRead();
        detector.recordOptimisticReadStarted(VALIDATED_STAMPED_LOCK, stamp, self);
        detector.recordDataAccessed(VALIDATED_STAMPED_LOCK, stamp, self, "balance");
        detector.recordValidateCalled(VALIDATED_STAMPED_LOCK, stamp, true, self);
    }

    // --- LockUpgradeDeadlock -------------------------------------------------------------------

    /**
     * A write lock attempted while this thread still holds the read lock.
     *
     * <p>{@code ReentrantReadWriteLock} does not support upgrading: the write acquisition waits
     * for the readers to leave, and one of those readers is the caller, so nothing can wake it.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_readLock_upgradedWithoutReleasing() {
        CorpusRecorder.countBodyExecution();
        Thread self = Thread.currentThread();
        var detector = AsyncTestContext.lockUpgradeDeadlockDetector();
        detector.recordReadLockAcquired(UPGRADED_LOCK, "upgraded", self);
        detector.recordWriteLockAcquisitionAttempt(UPGRADED_LOCK, "upgraded", self);
    }

    /** The same two acquisitions with the read released between them: the documented way up. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_readLock_releasedBeforeWriting() {
        CorpusRecorder.countBodyExecution();
        Thread self = Thread.currentThread();
        var detector = AsyncTestContext.lockUpgradeDeadlockDetector();
        detector.recordReadLockAcquired(RELEASED_THEN_WRITTEN_LOCK, "released", self);
        detector.recordReadLockReleased(RELEASED_THEN_WRITTEN_LOCK, self);
        detector.recordWriteLockAcquisitionAttempt(RELEASED_THEN_WRITTEN_LOCK, "released", self);
    }

    // --- ScopedValue ---------------------------------------------------------------------------

    /** A read on a thread that never entered a binding: outside the scope the value has none. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_scopedValue_readOutsideItsBinding() {
        CorpusRecorder.countBodyExecution();
        AsyncTestContext.scopedValueMisuseDetector()
                .recordGetCalled("unbound-value", Thread.currentThread());
    }

    /** The same read between a recorded entry and exit, which is the only place it is defined. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_scopedValue_readInsideItsBinding() {
        CorpusRecorder.countBodyExecution();
        Thread self = Thread.currentThread();
        var detector = AsyncTestContext.scopedValueMisuseDetector();
        detector.recordBindingEntered("bound-value", self);
        detector.recordGetCalled("bound-value", self);
        detector.recordBindingExited("bound-value", self);
    }

    // --- StatefulLambda ------------------------------------------------------------------------

    /** One lambda instance executed by six threads, mutating the state it captured. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_lambda_sharedAndMutatingItsCapture() {
        CorpusRecorder.countBodyExecution();
        Thread self = Thread.currentThread();
        var detector = AsyncTestContext.statefulLambdaDetector();
        detector.recordExecution(SHARED_LAMBDA, "shared-lambda", self);
        detector.recordCapturedMutation(SHARED_LAMBDA, "counter", self);
    }

    /** A lambda per thread: stateful is only a hazard once the instance escapes its thread. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_lambda_confinedToItsOwnThread() {
        CorpusRecorder.countBodyExecution();
        Thread self = Thread.currentThread();
        Runnable mine = CONFINED_LAMBDA.get();
        var detector = AsyncTestContext.statefulLambdaDetector();
        detector.recordExecution(mine, "confined-lambda", self);
        detector.recordCapturedMutation(mine, "counter", self);
    }

    // --- SystemPropertyMutation ----------------------------------------------------------------

    /**
     * Six threads write one process-global key.
     *
     * <p>The properties table is synchronized, so nothing corrupts - and that is the point. The
     * race is over which value the rest of the JVM reads, and it reaches every library in it.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_systemProperty_mutatedByEveryThread() {
        CorpusRecorder.countBodyExecution();
        AsyncTestContext.systemPropertyMutationDetector()
                .recordSet("corpus.shared.key", "v", Thread.currentThread());
    }

    /** The same writes to a key private to each thread: a single-threaded mutation, not a race. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_systemProperty_mutatedOnAPrivateKey() {
        CorpusRecorder.countBodyExecution();
        Thread self = Thread.currentThread();
        AsyncTestContext.systemPropertyMutationDetector()
                .recordSet("corpus.own." + self.threadId(), "v", self);
    }

    // --- WeakReferenceRace ---------------------------------------------------------------------

    /** A get recorded as having returned null where the caller expected its referent. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_weakReference_dereferencedAfterClearing() {
        CorpusRecorder.countBodyExecution();
        AsyncTestContext.weakReferenceRaceDetector()
                .recordNullDereference(CLEARED_REFERENCE, "cleared-ref", Thread.currentThread());
    }

    /** The same read of a reference whose referent is held strongly, so it cannot come back empty. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_weakReference_readWithAStrongReferent() {
        CorpusRecorder.countBodyExecution();
        AsyncTestContext.weakReferenceRaceDetector()
                .recordGet(LIVE_REFERENCE, "live-ref", STRONG_REFERENT, Thread.currentThread());
    }

    // --- VolatileArray -------------------------------------------------------------------------

    /**
     * One array whose elements every thread writes.
     *
     * <p>Declaring the field volatile publishes the array reference and says nothing at all about
     * the elements, which is the most-repeated misreading of the keyword and the reason this
     * shape survives review.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_volatileArray_elementsWrittenByEveryThread() {
        CorpusRecorder.countBodyExecution();
        if (sharedArrayRegistered.compareAndSet(false, true)) {
            AsyncTestContext.volatileArrayDetector()
                    .registerArray(SHARED_ARRAY, "shared-array", int.class);
        }
        AsyncTestContext.volatileArrayDetector().recordElementWrite(SHARED_ARRAY, 0, "shared-array");
        SHARED_ARRAY[0]++;
    }

    /** An array per thread, so no element is ever reached by a second one. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_volatileArray_confinedToOneThread() {
        CorpusRecorder.countBodyExecution();
        int[] mine = CONFINED_ARRAY.get();
        var detector = AsyncTestContext.volatileArrayDetector();
        detector.registerArray(mine, "confined-array", int.class);
        detector.recordElementWrite(mine, 0, "confined-array");
        mine[0]++;
    }

    // --- The CompletableFuture lifecycle family -----------------------------------------------

    /** A future that fails with no handler recorded: the exception dies inside it. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_completableFuture_failedWithNoHandler() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.completableFutureExceptionDetector();
        CompletableFuture<String> future = new CompletableFuture<>();
        detector.recordFutureCreated(future, "unhandled");
        future.completeExceptionally(new IllegalStateException("corpus-cf-failure"));
        detector.recordFutureCompleted(future, "unhandled", false);
    }

    /** The identical failure with a handler recorded first, which is what exceptionally is for. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_completableFuture_failureHandled() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.completableFutureExceptionDetector();
        CompletableFuture<String> future = new CompletableFuture<>();
        detector.recordFutureCreated(future, "handled");
        IllegalStateException failure = new IllegalStateException("corpus-cf-failure");
        future.completeExceptionally(failure);
        detector.recordExceptionHandled(future, "handled", failure);
        detector.recordFutureCompleted(future, "handled", true);
    }

    /** A future created and never completed: whatever waits on it waits forever. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_completableFuture_neverCompleted() {
        CorpusRecorder.countBodyExecution();
        CompletableFuture<String> future = new CompletableFuture<>();
        AsyncTestContext.completableFutureCompletionLeakDetector()
                .recordFutureCreated(future, "never-completed");
    }

    /** The same creation completed before the body returns, so no tracked future is left open. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_completableFuture_completedBeforeTheBodyReturned() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.completableFutureCompletionLeakDetector();
        CompletableFuture<String> future = new CompletableFuture<>();
        detector.recordFutureCreated(future, "completed");
        future.complete("value");
        detector.recordFutureCompleted(future, "completed");
    }

    // --- UnboundedQueue -------------------------------------------------------------------------

    /** A queue declared with no bound: backpressure becomes heap growth. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_blockingQueue_createdUnbounded() {
        CorpusRecorder.countBodyExecution();
        AsyncTestContext.unboundedQueueDetector()
                .recordQueueCreation(UNBOUNDED_QUEUE, "unbounded-queue", -1);
    }

    /** The same declaration with a capacity, plus real traffic through it. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_blockingQueue_createdWithACapacity() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.unboundedQueueDetector();
        detector.recordQueueCreation(BOUNDED_QUEUE, "bounded-queue", 16);
        if (BOUNDED_QUEUE.offer("item")) {
            detector.recordEnqueue(BOUNDED_QUEUE);
            BOUNDED_QUEUE.poll();
            detector.recordDequeue(BOUNDED_QUEUE);
        }
    }

    // --- CopyOnWriteCollections -----------------------------------------------------------------

    /** Writes dominating a copy-on-write list: correct, and the wrong data structure. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_copyOnWrite_underAWriteHeavyWorkload() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.copyOnWriteCollectionDetector();
        detector.registerCollection(WRITE_HEAVY_COW, "write-heavy-cow");
        detector.recordWrite(WRITE_HEAVY_COW, "write-heavy-cow");
        WRITE_HEAVY_COW.add("item");
    }

    /** The same class under the mix it was designed for: many reads to one write. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_copyOnWrite_underAReadHeavyWorkload() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.copyOnWriteCollectionDetector();
        detector.registerCollection(READ_HEAVY_COW, "read-heavy-cow");
        if (readHeavyCowSeeded.compareAndSet(false, true)) {
            detector.recordWrite(READ_HEAVY_COW, "read-heavy-cow");
            READ_HEAVY_COW.add("item");
        }
        for (int i = 0; i < 20; i++) {
            detector.recordRead(READ_HEAVY_COW, "read-heavy-cow");
            READ_HEAVY_COW.size();
        }
    }

    // --- ParallelStreams ------------------------------------------------------------------------

    /** A stateful operation in a parallel pipeline, which the stream contract forbids. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_parallelStream_withAStatefulOperation() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.parallelStreamDetector();
        detector.recordParallelStream("stateful-pipeline");
        detector.recordStatefulOperation("stateful-pipeline", "map");
    }

    /** The same pipeline with a stateless operation: what the contract asks for. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_parallelStream_withStatelessOperations() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.parallelStreamDetector();
        detector.recordParallelStream("stateless-pipeline");
        detector.recordStatelessOperation("stateless-pipeline", "map");
    }

    // --- ThreadLocalLeaks -----------------------------------------------------------------------

    /** Initialised and never cleaned: on a pooled thread the value outlives every task. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_threadLocal_initialisedAndNeverCleaned() {
        CorpusRecorder.countBodyExecution();
        AsyncTestContext.threadLocalMonitor().recordThreadLocalInit(LEAKED_THREAD_LOCAL, "leaked-tl");
        LEAKED_THREAD_LOCAL.set("value");
    }

    /** The same initialisation with the remove() every correct use has in its finally block. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_threadLocal_cleanedUpAfterUse() {
        CorpusRecorder.countBodyExecution();
        var monitor = AsyncTestContext.threadLocalMonitor();
        monitor.recordThreadLocalInit(CLEANED_THREAD_LOCAL, "cleaned-tl");
        CLEANED_THREAD_LOCAL.set("value");
        CLEANED_THREAD_LOCAL.remove();
        monitor.recordThreadLocalCleanup(CLEANED_THREAD_LOCAL);
    }

    // --- DoubleCheckedLocking -------------------------------------------------------------------

    /** Both checks, inside synchronized, on a non-volatile field: the broken singleton. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_doubleCheckedLocking_withoutVolatile() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.doubleCheckedLockingDetector();
        detector.registerDCL("brokenSingleton", false, true, true, true);
        detector.recordAccess("brokenSingleton", true, false);
    }

    /** The identical declaration with the field volatile: the fix, correct since Java 5. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_doubleCheckedLocking_withVolatile() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.doubleCheckedLockingDetector();
        detector.registerDCL("correctSingleton", true, true, true, true);
        detector.recordAccess("correctSingleton", true, false);
    }

    // --- SynchronizedNonFinal -------------------------------------------------------------------

    /** A fresh monitor each time, which is what locking on a reassignable field looks like. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_synchronized_onAReassignableLock() {
        CorpusRecorder.countBodyExecution();
        AsyncTestContext.synchronizedNonFinalDetector()
                .recordLockObject(new Object(), "reassignableLock", CorpusRecordingLaneTest.class);
    }

    /** One final lock object for the run: the idiom every guide prints. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_synchronized_onAFinalLock() {
        CorpusRecorder.countBodyExecution();
        AsyncTestContext.synchronizedNonFinalDetector()
                .recordLockObject(PRIVATE_LOCK, "finalLock", CorpusRecordingLaneTest.class);
    }

    // --- FinalFieldMutation ---------------------------------------------------------------------

    /** A final field recorded as written, which voids the freeze the memory model relies on. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_finalField_mutatedReflectively() {
        CorpusRecorder.countBodyExecution();
        AsyncTestContext.finalFieldMutationDetector()
                .recordMutation("CorpusConfig.name", Thread.currentThread());
    }

    /** The same field read by every thread and never written: what final fields are for. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_finalField_onlyRead() {
        CorpusRecorder.countBodyExecution();
        AsyncTestContext.finalFieldMutationDetector()
                .recordRead("CorpusConfig.readOnlyName", Thread.currentThread());
    }

    // --- PublicLockExposure ---------------------------------------------------------------------

    /** The object being synchronized on is the one the API hands out. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_lock_publishedThroughTheApi() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.publicLockExposureDetector();
        detector.recordSynchronizedOnThis(EXPOSED_LOCK, Thread.currentThread(), "CorpusService");
        detector.recordObjectPublished(EXPOSED_LOCK, "getService()");
    }

    /** The same two calls with the published object being a value rather than the monitor. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_lock_keptPrivate() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.publicLockExposureDetector();
        detector.recordSynchronizedOnThis(PRIVATE_LOCK, Thread.currentThread(), "CorpusService");
        detector.recordObjectPublished(PUBLISHED_VALUE, "getValue()");
    }

    // --- The synchronizer family --------------------------------------------------------------
    //
    // Four coordinators, one question: did the protocol complete, or did it end in the state the
    // class documents as terminal? Each pair records a finished cycle against an abandoned one.

    /** A barrier recorded as broken: every later await fails until somebody resets it. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_cyclicBarrier_leftBroken() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.cyclicBarrierDetector();
        detector.registerBarrier(BROKEN_BARRIER, "broken-barrier", THREADS);
        detector.recordBroken(BROKEN_BARRIER);
    }

    /** The same barrier through a whole cycle: arrive, await, complete, never broken. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_cyclicBarrier_completedItsCycle() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.cyclicBarrierDetector();
        detector.registerBarrier(COMPLETED_BARRIER, "completed-barrier", THREADS);
        detector.recordArrival(COMPLETED_BARRIER);
        detector.recordAwait(COMPLETED_BARRIER);
        detector.recordBarrierComplete(COMPLETED_BARRIER);
    }

    /** A tryLock recorded as timed out: somebody held it longer than the caller would wait. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_reentrantLock_acquisitionTimedOut() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.reentrantLockDetector();
        detector.registerLock(TIMED_OUT_LOCK, "timed-out-lock");
        detector.recordLockTimeout(TIMED_OUT_LOCK);
    }

    /** The same lock acquired and released with no timeout: what an uncontended lock looks like. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_reentrantLock_acquiredAndReleased() {
        CorpusRecorder.countBodyExecution();
        String self = Thread.currentThread().getName();
        var detector = AsyncTestContext.reentrantLockDetector();
        detector.registerLock(CLEAN_LOCK, "clean-lock");
        CLEAN_LOCK.lock();
        try {
            detector.recordLockAcquired(CLEAN_LOCK, self);
        } finally {
            CLEAN_LOCK.unlock();
            detector.recordLockReleased(CLEAN_LOCK, self);
        }
    }

    /** A phaser recorded as terminated: later arrivals return a phase instead of synchronizing. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_phaser_terminated() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.phaserDetector();
        detector.registerPhaser(TERMINATED_PHASER, "terminated-phaser", 1);
        detector.recordTermination(TERMINATED_PHASER);
    }

    /** The same phaser recorded arriving, awaiting the advance and completing its phase. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_phaser_advancedThroughItsPhase() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.phaserDetector();
        detector.registerPhaser(ADVANCING_PHASER, "advancing-phaser", 1);
        detector.recordArrive(ADVANCING_PHASER);
        detector.recordArriveAwaitAdvance(ADVANCING_PHASER);
        detector.recordPhaseComplete(ADVANCING_PHASER, 0);
    }

    /** An exchange that completes carrying nothing: a rendezvous that transferred no value. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_exchanger_exchangedNothing() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.exchangerDetector();
        detector.registerExchanger(EMPTY_EXCHANGER, "empty-exchanger");
        detector.recordExchangeComplete(EMPTY_EXCHANGER, "empty-exchanger", null);
    }

    /** The same rendezvous recorded start to finish with a real payload. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_exchanger_exchangedAPayload() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.exchangerDetector();
        detector.registerExchanger(PAYLOAD_EXCHANGER, "payload-exchanger");
        detector.recordExchangeStart(PAYLOAD_EXCHANGER, "payload-exchanger");
        detector.recordExchangeComplete(PAYLOAD_EXCHANGER, "payload-exchanger", "payload");
    }

    /** An await with nothing ever signalling: the Condition form of a lost wakeup. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_condition_awaitedWithNoSignal() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.conditionVariableDetector();
        detector.registerCondition(UNSIGNALLED_CONDITION, "unsignalled");
        detector.recordAwait(UNSIGNALLED_CONDITION, "unsignalled");
        detector.recordAwaitExit(UNSIGNALLED_CONDITION, "unsignalled", false);
    }

    /** The same await with a recorded signal behind it: the whole handshake. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_condition_awaitedAndSignalled() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.conditionVariableDetector();
        detector.registerCondition(SIGNALLED_CONDITION, "signalled");
        detector.recordAwait(SIGNALLED_CONDITION, "signalled");
        detector.recordSignal(SIGNALLED_CONDITION, "signalled", true);
        detector.recordAwaitExit(SIGNALLED_CONDITION, "signalled", false);
    }

    // --- The value-lifecycle family -------------------------------------------------------------

    /** A value that goes A to B and back to A, which a value-only compare-and-set cannot see. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_aba_valueReturnedToItsOriginal() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.abaProblemDetector();
        String slot = perInvocation("aba-restored");
        detector.recordValueChange(slot, "A", "B");
        detector.recordValueChange(slot, "B", "A");
    }

    /** The same two transitions going onwards to C, so nothing is ever restored. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_aba_valueMovedOnwards() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.abaProblemDetector();
        String slot = perInvocation("aba-onwards");
        detector.recordValueChange(slot, "A", "B");
        detector.recordValueChange(slot, "B", "C");
    }

    /** A read of a write-once holder nothing has set. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_stableValue_readBeforeItWasSet() {
        CorpusRecorder.countBodyExecution();
        AsyncTestContext.stableValueMisuseDetector()
                .recordRead(perInvocation("stable-unset"), Thread.currentThread());
    }

    /** The same read with its set recorded first, on a name unique to this invocation. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_stableValue_setBeforeItWasRead() {
        CorpusRecorder.countBodyExecution();
        Thread self = Thread.currentThread();
        var detector = AsyncTestContext.stableValueMisuseDetector();
        String name = perInvocation("stable-set");
        detector.recordSet(name, self);
        detector.recordRead(name, self);
    }

    /** A plain get and a plain set as one read-modify-write: no atomicity, no ordering. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_varHandle_plainGetThenPlainSet() {
        CorpusRecorder.countBodyExecution();
        Thread self = Thread.currentThread();
        var detector = AsyncTestContext.varHandleNonAtomicUpdateDetector();
        detector.recordGet(PLAIN_HANDLE, PLAIN_RECEIVER, "counter",
                se.deversity.asynctest.diagnostics.VarHandleNonAtomicUpdateDetector.Mode.PLAIN, self);
        detector.recordSet(PLAIN_HANDLE, PLAIN_RECEIVER, "counter",
                se.deversity.asynctest.diagnostics.VarHandleNonAtomicUpdateDetector.Mode.PLAIN, self);
    }

    /** The same update expressed as a volatile read and a recorded atomic update. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_varHandle_volatileGetThenAtomicUpdate() {
        CorpusRecorder.countBodyExecution();
        Thread self = Thread.currentThread();
        var detector = AsyncTestContext.varHandleNonAtomicUpdateDetector();
        detector.recordGet(ATOMIC_HANDLE, ATOMIC_RECEIVER, "counter",
                se.deversity.asynctest.diagnostics.VarHandleNonAtomicUpdateDetector.Mode.VOLATILE, self);
        detector.recordAtomicUpdate(ATOMIC_HANDLE, ATOMIC_RECEIVER, "counter", self);
    }

    // --- The thread-lifecycle family ------------------------------------------------------------

    /** Parks until reportAndGate releases it; the body of both parked threads. */
    private static void awaitRelease() {
        try {
            PARKED_THREADS_RELEASE.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * A thread recorded as started whose end is never recorded.
     *
     * <p>The detector reports a tracked thread that is still {@code isAlive()} at analysis, so
     * this uses the parked thread rather than starting 240 of its own. It is a daemon, so even
     * if the release were missed it could not hold the JVM open.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_thread_startedAndNeverJoined() {
        CorpusRecorder.countBodyExecution();
        AsyncTestContext.threadLeakDetector().recordThreadStart(parkedDaemon, "parked-daemon");
    }

    /** The same calls with a join between them, so the thread is gone before analysis. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_thread_startedAndJoined() throws InterruptedException {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.threadLeakDetector();
        Thread worker = new Thread(() -> { }, "corpus-joined-worker");
        detector.recordThreadStart(worker, "joined-worker");
        worker.start();
        worker.join();
        detector.recordThreadEnd(worker);
    }

    /** A thread with no custom handler recorded as dying from an uncaught exception. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_thread_diedWithNoHandler() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.uncaughtExceptionHandlerDetector();
        Thread bare = new Thread(() -> { }, "corpus-unhandled");
        detector.recordThreadStart(bare);
        detector.recordUncaughtException(bare, new IllegalStateException("corpus-thread-death"));
    }

    /** The identical death on a thread that had a handler installed before it started. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_thread_diedWithAHandlerInstalled() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.uncaughtExceptionHandlerDetector();
        Thread handled = new Thread(() -> { }, "corpus-handled");
        handled.setUncaughtExceptionHandler((t, e) -> { });
        detector.recordThreadStart(handled);
        detector.recordUncaughtException(handled, new IllegalStateException("corpus-thread-death"));
    }

    /** A live non-daemon thread: the one kind that keeps the JVM from exiting. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_thread_leftNonDaemonAndAlive() {
        CorpusRecorder.countBodyExecution();
        AsyncTestContext.daemonThreadHygieneDetector().recordThread(parkedNonDaemon, "parked-user");
    }

    /** The same recording of a daemon thread, which the JVM abandons at exit. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_thread_leftAsADaemon() {
        CorpusRecorder.countBodyExecution();
        AsyncTestContext.daemonThreadHygieneDetector().recordThread(parkedDaemon, "parked-daemon");
    }

    /** A factory handing back a default-named, non-daemon, handler-less thread. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_threadFactory_producedARawThread() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.threadFactoryDetector();
        detector.registerFactory(RAW_FACTORY, "raw-factory");
        detector.recordThreadCreated(RAW_FACTORY, "raw-factory", RAW_FACTORY.newThread(() -> { }));
    }

    /** The same factory call producing a named daemon thread with a handler. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_threadFactory_producedAConfiguredThread() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.threadFactoryDetector();
        detector.registerFactory(CONFIGURED_FACTORY, "configured-factory");
        detector.recordThreadCreated(CONFIGURED_FACTORY, "configured-factory",
                CONFIGURED_FACTORY.newThread(() -> { }));
    }

    // --- Per-thread state that outlives its task ------------------------------------------------

    /** An inheritable thread-local set on a thread the body declares as pooled. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_inheritableThreadLocal_setOnAPoolThread() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.inheritableThreadLocalMisuseDetector();
        detector.registerPoolThread(Thread.currentThread());
        detector.recordSet(POOLED_ITL, "request-context", "value");
    }

    /** The same set and get under a name private to each thread, never declared pooled. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_inheritableThreadLocal_confinedToItsOwnName() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.inheritableThreadLocalMisuseDetector();
        String name = "request-context-" + Thread.currentThread().threadId();
        detector.recordSet(CONFINED_ITL, name, "value");
        detector.recordGet(CONFINED_ITL, name);
    }

    /** A value set during one task and still readable in the next task on the same thread. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_threadLocal_readAcrossATaskBoundary() {
        CorpusRecorder.countBodyExecution();
        Thread self = Thread.currentThread();
        var detector = AsyncTestContext.threadLocalContaminationDetector();
        detector.recordNewTask(self, "first-task");
        detector.recordSet(self, CONTAMINATING_TL, "request-context");
        detector.recordNewTask(self, "second-task");
        detector.recordGet(self, CONTAMINATING_TL, "request-context", true);
    }

    /** The same two tasks with the value read inside its own task and absent in the next. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_threadLocal_clearedAtTheTaskBoundary() {
        CorpusRecorder.countBodyExecution();
        Thread self = Thread.currentThread();
        var detector = AsyncTestContext.threadLocalContaminationDetector();
        detector.recordNewTask(self, "first-task");
        detector.recordSet(self, SCOPED_TL, "request-context");
        detector.recordGet(self, SCOPED_TL, "request-context", true);
        detector.recordNewTask(self, "second-task");
        detector.recordGet(self, SCOPED_TL, "request-context", false);
    }

    // --- Three more confinement shapes -----------------------------------------------------------

    /** Six threads read-modify-writing one lambda's captured counter with nothing held. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_lambda_readModifyWriteWithNoGuard() {
        CorpusRecorder.countBodyExecution();
        AsyncTestContext.lambdaLostUpdateDetector()
                .recordReadModifyWrite(UNGUARDED_LAMBDA, "counter", 0, 1, Thread.currentThread());
    }

    /** The identical sequence with the guard the caller held named to the detector. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_lambda_readModifyWriteUnderAGuard() {
        CorpusRecorder.countBodyExecution();
        synchronized (LAMBDA_GUARD) {
            AsyncTestContext.lambdaLostUpdateDetector().recordReadModifyWrite(
                    GUARDED_LAMBDA, "counter", 0, 1, LAMBDA_GUARD, Thread.currentThread());
        }
    }

    /** A record holding a mutable list, shared across threads. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_record_sharedWithAMutableComponent() {
        CorpusRecorder.countBodyExecution();
        AsyncTestContext.recordMutableComponentLeakDetector()
                .recordShared(LEAKY_RECORD, "leaky-record", Thread.currentThread());
    }

    /** The same sharing of a record whose components are all immutable. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_record_sharedWithImmutableComponents() {
        CorpusRecorder.countBodyExecution();
        AsyncTestContext.recordMutableComponentLeakDetector()
                .recordShared(SAFE_RECORD, "safe-record", Thread.currentThread());
    }

    /** One SplittableRandom recorded from six threads: its javadoc says not thread-safe. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_splittableRandom_sharedAcrossThreads() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.sharedSplittableRandomDetector();
        detector.registerGenerator(SHARED_SPLITTABLE, "shared-splittable");
        detector.recordAccess(SHARED_SPLITTABLE, "shared-splittable", "nextInt");
        SHARED_SPLITTABLE.nextInt();
    }

    /** A generator per thread, which is what split() exists for. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_splittableRandom_splitPerThread() {
        CorpusRecorder.countBodyExecution();
        java.util.SplittableRandom mine = CONFINED_SPLITTABLE.get();
        var detector = AsyncTestContext.sharedSplittableRandomDetector();
        detector.registerGenerator(mine, "confined-splittable");
        detector.recordAccess(mine, "confined-splittable", "nextInt");
        mine.nextInt();
    }

    // --- The CompletableFuture protocol family --------------------------------------------------

    /** A chain created and never joined or handled: nothing observes its outcome. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_completableFuture_chainNeverJoined() {
        CorpusRecorder.countBodyExecution();
        CompletableFuture<String> orphan = new CompletableFuture<>();
        AsyncTestContext.cfChainDetector().recordFutureCreated(orphan, "orphan-chain");
    }

    /** The same creation with a chain operation, a handler and a join recorded. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_completableFuture_chainJoined() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.cfChainDetector();
        CompletableFuture<String> head = CompletableFuture.completedFuture("value");
        detector.recordFutureCreated(head, "joined-chain");
        CompletableFuture<String> tail = head.thenApply(v -> v);
        detector.recordChainOperation(head, tail, "thenApply");
        detector.recordHandle(tail);
        tail.join();
        detector.recordFutureJoined(tail, "joined-chain");
    }

    /** A join recorded on a common-pool future, from a thread that pool runs. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_completableFuture_blockedOnItsOwnPool() {
        CorpusRecorder.countBodyExecution();
        Thread self = Thread.currentThread();
        var detector = AsyncTestContext.cfCommonPoolBlockingDetector();
        CompletableFuture<String> onCommonPool = new CompletableFuture<>();
        detector.recordCommonPoolSubmission(onCommonPool, self, "common-pool-task");
        detector.recordBlockingCall(onCommonPool, self, "join");
    }

    /** The identical join against a future that was never submitted to the common pool. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_completableFuture_blockedOnADedicatedPool() {
        CorpusRecorder.countBodyExecution();
        Thread self = Thread.currentThread();
        var detector = AsyncTestContext.cfCommonPoolBlockingDetector();
        CompletableFuture<String> onCommonPool = new CompletableFuture<>();
        CompletableFuture<String> onOwnPool = new CompletableFuture<>();
        detector.recordCommonPoolSubmission(onCommonPool, self, "common-pool-task");
        detector.recordBlockingCall(onOwnPool, self, "join");
    }

    /** Two completion attempts on one future: one wins and one is silently discarded. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_completableFuture_completedTwice() {
        CorpusRecorder.countBodyExecution();
        Thread self = Thread.currentThread();
        var detector = AsyncTestContext.cfCompletionRaceDetector();
        CompletableFuture<String> contested = new CompletableFuture<>();
        detector.recordCompletionAttempt(contested, "contested", "first", true, self);
        detector.recordCompletionAttempt(contested, "contested", "second", false, self);
    }

    /** One attempt per future, each future private to its invocation, so nothing ever loses. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_completableFuture_completedOnce() {
        CorpusRecorder.countBodyExecution();
        CompletableFuture<String> mine = new CompletableFuture<>();
        AsyncTestContext.cfCompletionRaceDetector()
                .recordCompletionAttempt(mine, perInvocation("single-producer"), "value", true,
                        Thread.currentThread());
    }

    /** A cancel asking for interruption, which CompletableFuture documents as having no effect. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_completableFuture_cancelDidNotReachTheWork() {
        CorpusRecorder.countBodyExecution();
        AsyncTestContext.cfCancellationPropagationDetector()
                .recordCancel("orphaned-pipeline", "stage", true, true, Thread.currentThread());
    }

    /** A stage recorded as started and finished before a cancel that asks for no interruption. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_completableFuture_cancelAfterTheWorkFinished() {
        CorpusRecorder.countBodyExecution();
        Thread self = Thread.currentThread();
        var detector = AsyncTestContext.cfCancellationPropagationDetector();
        String pipeline = perInvocation("finished-pipeline");
        detector.recordWorkStarted(pipeline, "stage", self);
        detector.recordWorkCompleted(pipeline, "stage", self);
        detector.recordCancel(pipeline, "stage", false, true, self);
    }

    /** An allOf recorded and never awaited: the constituents' failures go nowhere. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_completableFuture_combinatorNeverAwaited() {
        CorpusRecorder.countBodyExecution();
        CompletableFuture<Void> dropped = CompletableFuture.allOf();
        AsyncTestContext.cfCombinatorMisuseDetector()
                .recordCombinator(dropped, "dropped-allOf", "allOf", 2, Thread.currentThread());
    }

    /** The same combinator with both constituents completed and a recorded await. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_completableFuture_combinatorAwaited() {
        CorpusRecorder.countBodyExecution();
        Thread self = Thread.currentThread();
        var detector = AsyncTestContext.cfCombinatorMisuseDetector();
        CompletableFuture<Void> awaited = CompletableFuture.allOf();
        detector.recordCombinator(awaited, "awaited-allOf", "allOf", 2, self);
        detector.recordConstituentCompleted(awaited, "first", false, self);
        detector.recordConstituentCompleted(awaited, "second", false, self);
        awaited.join();
        detector.recordAwait(awaited, "join", self);
    }

    // --- The structured-concurrency family ------------------------------------------------------

    /** A scope opened and closed with nothing forked into it. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_scope_closedWithoutForking() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.structuredConcurrencyMisuseDetector();
        String scopeId = detector.recordScopeOpened("StructuredTaskScope");
        detector.recordScopeClosed(scopeId);
    }

    /** The same scope with a subtask forked, joined and read before the close. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_scope_forkedJoinedAndRead() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.structuredConcurrencyMisuseDetector();
        String scopeId = detector.recordScopeOpened("StructuredTaskScope");
        detector.recordSubtaskForked(scopeId);
        detector.recordJoinCalled(scopeId);
        detector.recordResultAccessed(scopeId);
        detector.recordScopeClosed(scopeId);
    }

    /** A subtask forked and the scope closed with no join: the work is abandoned mid-flight. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_taskScope_closedWithoutJoining() {
        CorpusRecorder.countBodyExecution();
        Thread self = Thread.currentThread();
        var detector = AsyncTestContext.structuredTaskScopeMisuseDetector();
        String scopeId = perInvocation("unjoined-scope");
        detector.recordScopeOpened(scopeId, self);
        detector.recordFork(scopeId, "subtask", self);
        detector.recordScopeClosed(scopeId, self);
    }

    /** The identical fork with the join and result read in between. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_taskScope_joinedBeforeClosing() {
        CorpusRecorder.countBodyExecution();
        Thread self = Thread.currentThread();
        var detector = AsyncTestContext.structuredTaskScopeMisuseDetector();
        String scopeId = perInvocation("joined-scope");
        detector.recordScopeOpened(scopeId, self);
        detector.recordFork(scopeId, "subtask", self);
        detector.recordJoin(scopeId, self);
        detector.recordResultRead(scopeId, "subtask", self);
        detector.recordScopeClosed(scopeId, self);
    }

    /** One joiner bound to two different scopes: two scopes' outcomes merge into one accumulator. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_scopeJoiner_boundToTwoScopes() {
        CorpusRecorder.countBodyExecution();
        Thread self = Thread.currentThread();
        var detector = AsyncTestContext.scopeJoinerMisuseDetector();
        Object joiner = new Object();
        detector.recordJoinerBound(joiner, "reused-joiner", perInvocation("scope-a"), self);
        detector.recordJoinerBound(joiner, "reused-joiner", perInvocation("scope-b"), self);
    }

    /** A joiner per invocation bound once and taken through its whole callback lifecycle. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_scopeJoiner_boundToOneScope() {
        CorpusRecorder.countBodyExecution();
        Thread self = Thread.currentThread();
        var detector = AsyncTestContext.scopeJoinerMisuseDetector();
        Object joiner = new Object();
        detector.recordJoinerBound(joiner, "own-joiner", perInvocation("scope-own"), self);
        detector.recordFork(joiner, self);
        detector.recordOnCompleteEnter(joiner, self);
        detector.recordAccumulate(joiner, self);
        detector.recordOnCompleteExit(joiner, self, false);
        detector.recordResult(joiner, self);
    }

    /** The configuration asked for and the one in force differ. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_scope_configurationSilentlyIgnored() {
        CorpusRecorder.countBodyExecution();
        Thread self = Thread.currentThread();
        var detector = AsyncTestContext.scopeConfigurationMisuseDetector();
        String scopeId = perInvocation("ignored-config");
        detector.recordScopeOpened(scopeId, "requested-name", 5_000L, null, self);
        detector.recordEffectiveConfiguration(scopeId, "some-other-name", 1_000L);
    }

    /** The same scope whose effective configuration matches what was requested. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_scope_configurationApplied() {
        CorpusRecorder.countBodyExecution();
        Thread self = Thread.currentThread();
        var detector = AsyncTestContext.scopeConfigurationMisuseDetector();
        String scopeId = perInvocation("applied-config");
        String name = perInvocation("scope-name");
        detector.recordScopeOpened(scopeId, name, 5_000L, null, self);
        detector.recordEffectiveConfiguration(scopeId, name, 5_000L);
        detector.recordFork(scopeId);
        detector.recordJoinOutcome(scopeId, false);
        detector.recordScopeClosed(scopeId);
    }

    /** A result handle read after its scope closed. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_scopeResult_readAfterTheScopeClosed() {
        CorpusRecorder.countBodyExecution();
        Thread self = Thread.currentThread();
        var detector = AsyncTestContext.scopeResultEscapeDetector();
        String scopeId = perInvocation("escaped-scope");
        Object handle = new Object();
        detector.recordScopeOpened(scopeId, self);
        detector.recordResultHandle(handle, "subtask-result", scopeId);
        detector.recordScopeClosed(scopeId);
        detector.recordHandleRead(handle, self);
    }

    /** The same handle read after the join and before the close: the window the API defines. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_scopeResult_readBeforeTheScopeClosed() {
        CorpusRecorder.countBodyExecution();
        Thread self = Thread.currentThread();
        var detector = AsyncTestContext.scopeResultEscapeDetector();
        String scopeId = perInvocation("contained-scope");
        Object handle = new Object();
        detector.recordScopeOpened(scopeId, self);
        detector.recordResultHandle(handle, "subtask-result", scopeId);
        detector.recordJoinCompleted(scopeId);
        detector.recordHandleRead(handle, self);
        detector.recordScopeClosed(scopeId);
    }

    // --- The harness-model family ---------------------------------------------------------------

    /** One field identifier recorded with a different value from every thread. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_field_readInconsistentlyAcrossThreads() {
        CorpusRecorder.countBodyExecution();
        AsyncTestContext.visibilityMonitor()
                .recordFieldAccess("shared.counter", Thread.currentThread().threadId());
    }

    /** The same field recorded with one value every thread agrees on. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_field_readConsistentlyAcrossThreads() {
        CorpusRecorder.countBodyExecution();
        AsyncTestContext.visibilityMonitor().recordFieldAccess("confined.value", 42L);
    }

    /** A wait recorded as exiting with no notify: the spurious wakeup the javadoc warns of. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_wait_returnedWithoutANotify() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.wakeupDetector();
        detector.recordWaitEnter(UNSIGNALLED_CONDITION);
        detector.recordWaitExit(UNSIGNALLED_CONDITION, false);
    }

    /** The same wait with a notify recorded between the enter and a notified exit. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_wait_returnedAfterANotify() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.wakeupDetector();
        detector.recordWaitEnter(SIGNALLED_CONDITION);
        detector.recordNotify(SIGNALLED_CONDITION, true);
        detector.recordWaitExit(SIGNALLED_CONDITION, true);
    }

    /** Fields read by other threads while the object's construction is still open. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_object_accessedDuringConstruction() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.constructorSafetyValidator();
        if (constructionOpened.compareAndSet(false, true)) {
            detector.recordConstructionStart(UNDER_CONSTRUCTION);
        }
        detector.recordFieldAccess(UNDER_CONSTRUCTION, "name", System.nanoTime());
    }

    /** The identical reads of an object whose construction was recorded as finished first. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_object_accessedAfterConstruction() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.constructorSafetyValidator();
        if (constructionClosed.compareAndSet(false, true)) {
            detector.recordConstructionStart(FULLY_CONSTRUCTED);
            detector.recordConstructionEnd(FULLY_CONSTRUCTED);
        }
        detector.recordFieldAccess(FULLY_CONSTRUCTED, "name", System.nanoTime());
    }

    /** A synchronizer expecting a thousand parties and receiving six. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_barrier_partiesNeverArrived() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.synchronizerMonitor();
        detector.registerSynchronizer(UNREACHABLE_BARRIER, 1_000);
        detector.recordBarrierArrival(UNREACHABLE_BARRIER);
    }

    /** A synchronizer sized to the parties that arrive, recorded through an advance. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_barrier_partiesArrivedAndAdvanced() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.synchronizerMonitor();
        detector.registerSynchronizer(REACHABLE_BARRIER, 1);
        detector.recordBarrierArrival(REACHABLE_BARRIER);
        detector.recordBarrierAdvance(REACHABLE_BARRIER);
    }

    /** A task recorded as rejected by a pool of one with a queue of one. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_threadPool_rejectedItsWork() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.threadPoolMonitor();
        detector.registerPool(TINY_POOL, "tiny-pool", 1, 1, 1);
        detector.recordTaskSubmitted(TINY_POOL);
        detector.recordTaskRejected(TINY_POOL, "queue full");
    }

    /** A pool sized for the work, recorded through submit, start and completion. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_threadPool_ranItsWorkToCompletion() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.threadPoolMonitor();
        detector.registerPool(AMPLE_POOL, "ample-pool", 8, 64, 4_096);
        detector.recordTaskSubmitted(AMPLE_POOL);
        detector.recordTaskStarted(AMPLE_POOL);
        detector.recordTaskCompleted(AMPLE_POOL, 1L);
    }

    /** Events published to a stage and never accounted for. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_pipelineStage_publishedAndDropped() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.pipelineMonitor();
        detector.registerStage("dropping-stage");
        detector.recordEventPublished("dropping-stage", perInvocation("event"));
    }

    /** The same events published and each recorded as processed. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_pipelineStage_publishedAndProcessed() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.pipelineMonitor();
        detector.registerStage("balanced-stage");
        String eventId = perInvocation("event");
        detector.recordEventPublished("balanced-stage", eventId);
        detector.recordEventProcessed("balanced-stage", eventId);
    }

    /** Readers outnumbering writers by an order of magnitude on one lock. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_readWriteLock_starvedItsWriter() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.readWriteLockMonitor();
        detector.registerLock(READER_HEAVY_LOCK, "reader-heavy");
        for (int i = 0; i < 11; i++) {
            detector.recordReadLockAcquired(READER_HEAVY_LOCK, 0L);
            detector.recordReadLockReleased(READER_HEAVY_LOCK);
        }
        detector.recordWriteLockAcquired(READER_HEAVY_LOCK, 0L);
        detector.recordWriteLockReleased(READER_HEAVY_LOCK);
    }

    /** The same lock with its reads and writes in balance. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_readWriteLock_balancedItsTraffic() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.readWriteLockMonitor();
        detector.registerLock(BALANCED_LOCK, "balanced");
        detector.recordReadLockAcquired(BALANCED_LOCK, 0L);
        detector.recordReadLockReleased(BALANCED_LOCK);
        detector.recordWriteLockAcquired(BALANCED_LOCK, 0L);
        detector.recordWriteLockReleased(BALANCED_LOCK);
    }

    /** One field recorded as initialised by every thread that looked at it. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_lazyInit_initialisedMoreThanOnce() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.lazyInitRaceDetector();
        detector.recordNullCheck("racyInstance", true, false);
        detector.recordInitialization("racyInstance");
    }

    /** The same field null-checked by every thread and initialised exactly once. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_lazyInit_initialisedOnce() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.lazyInitRaceDetector();
        detector.recordNullCheck("safeInstance", false, true);
        if (lazyFieldInitialised.compareAndSet(false, true)) {
            detector.recordInitialization("safeInstance");
        }
    }

    /** A monitor recorded as contended on most of the attempts to take it. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_lock_contendedRepeatedly() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.lockContentionDetector();
        detector.recordAcquireAttempt(CONTENDED_MONITOR, "contended");
        for (int i = 0; i < 5; i++) {
            detector.recordContention(CONTENDED_MONITOR, "contended");
        }
        detector.recordAcquired(CONTENDED_MONITOR, "contended");
        detector.recordReleased(CONTENDED_MONITOR, "contended");
    }

    /** The same attempts acquired and released with no contention recorded at all. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_lock_takenWithoutContention() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.lockContentionDetector();
        detector.recordAcquireAttempt(UNCONTENDED_MONITOR, "uncontended");
        detector.recordAcquired(UNCONTENDED_MONITOR, "uncontended");
        detector.recordReleased(UNCONTENDED_MONITOR, "uncontended");
    }

    /** Six threads writing one object's field with nothing held: the textbook data race. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_field_writtenByEveryThreadUnguarded() {
        CorpusRecorder.countBodyExecution();
        AsyncTestContext.raceConditionDetector().recordFieldWrite(RACED_TARGET, "counter");
    }

    /** The identical writes made inside synchronized on the object itself. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_field_writtenUnderTheObjectsMonitor() {
        CorpusRecorder.countBodyExecution();
        synchronized (GUARDED_TARGET) {
            AsyncTestContext.raceConditionDetector().recordFieldWrite(GUARDED_TARGET, "counter");
        }
    }

    // --- The virtual-thread family ----------------------------------------------------------------

    /** An inheritable thread-local set on a virtual thread and never removed. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_virtualThread_contextNeverRemoved() {
        CorpusRecorder.countBodyExecution();
        AsyncTestContext.virtualThreadContextLeakDetector()
                .recordThreadLocalSet("request-context", VIRTUAL_THREADS.get(0), true);
    }

    /** The same set, not inheritable, with a recorded removal behind it. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_virtualThread_contextRemoved() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.virtualThreadContextLeakDetector();
        Thread vt = VIRTUAL_THREADS.get(1);
        detector.recordThreadLocalSet("scoped-context", vt, false);
        detector.recordThreadLocalRemoved("scoped-context", vt);
    }

    /** More virtual threads queue for a resource of capacity one than it can ever serve. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_virtualThreads_saturatedAScarceResource() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.vthreadResourceSaturationDetector();
        detector.registerResource("scarce-pool", 1);
        for (Thread vt : VIRTUAL_THREADS) {
            detector.recordAcquireStart("scarce-pool", vt);
        }
    }

    /** The same acquisitions against a resource sized well above the demand. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_virtualThreads_withinResourceCapacity() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.vthreadResourceSaturationDetector();
        detector.registerResource("ample-pool", 32);
        for (Thread vt : VIRTUAL_THREADS) {
            detector.recordAcquireStart("ample-pool", vt);
            detector.recordAcquired("ample-pool", vt);
        }
    }

    /** Four virtual threads entering one monitor and none acquiring it. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_virtualThreads_serialisedOnAMonitor() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.vthreadMonitorSerializationDetector();
        for (Thread vt : VIRTUAL_THREADS) {
            detector.recordMonitorEnter(CONTENDED_MONITOR, "serialising-monitor", vt);
        }
    }

    /** The same entries recorded as acquired, on a monitor private to each invocation. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_virtualThreads_acquiredTheMonitor() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.vthreadMonitorSerializationDetector();
        Object monitor = new Object();
        for (int i = 0; i < 2; i++) {
            Thread vt = VIRTUAL_THREADS.get(i);
            detector.recordMonitorEnter(monitor, "acquired-monitor", vt);
            detector.recordMonitorAcquired(monitor, vt);
        }
    }

    /** A distinct cached instance recorded for each virtual thread. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_threadLocalCache_onePerVirtualThread() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.threadLocalCacheDegradationDetector();
        for (Thread vt : VIRTUAL_THREADS) {
            detector.recordCachedValue("per-thread-buffer", new Object(), vt);
        }
    }

    /** The same recordings of one shared instance. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_threadLocalCache_sharedAcrossVirtualThreads() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.threadLocalCacheDegradationDetector();
        for (Thread vt : VIRTUAL_THREADS) {
            detector.recordCachedValue("shared-buffer", ONE_CACHED_INSTANCE, vt);
        }
    }

    /** A fixed pool built over a virtual-thread factory: pooling what costs nothing to create. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_executor_pooledItsVirtualThreads() {
        CorpusRecorder.countBodyExecution();
        AsyncTestContext.virtualThreadPoolingDetector()
                .registerExecutor(pooledVirtualExecutor, "pooled-virtual");
    }

    /** The identical pool over the default platform factory, which is what pooling is for. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_executor_pooledItsPlatformThreads() {
        CorpusRecorder.countBodyExecution();
        AsyncTestContext.virtualThreadPoolingDetector()
                .registerExecutor(pooledPlatformExecutor, "pooled-platform");
    }

    // --- The foreign-memory family ------------------------------------------------------------

    /** Six threads writing the same eight bytes of one segment with no guard named. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_memorySegment_overlappingWrites() {
        CorpusRecorder.countBodyExecution();
        AsyncTestContext.sharedMemorySegmentRaceDetector()
                .recordAccess(OVERLAPPING_SEGMENT, "overlapping-segment", 0L, 8L, true,
                        Thread.currentThread());
    }

    /** The same segment written at offsets that cannot overlap. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_memorySegment_disjointWrites() {
        CorpusRecorder.countBodyExecution();
        Thread self = Thread.currentThread();
        // threadId() itself, not threadId() % THREADS: the modulo can map two distinct workers
        // onto one slot, and the row would then overlap for a reason unrelated to the model it
        // is testing. Thread ids are unique for the life of the JVM, so these cannot collide.
        long slot = self.threadId() * 8L;
        AsyncTestContext.sharedMemorySegmentRaceDetector()
                .recordAccess(DISJOINT_SEGMENT, "disjoint-segment", slot, 8L, true, self);
    }

    /** A segment from a confined arena accessed by threads other than its owner. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_confinedArena_accessedFromAnotherThread() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.confinedArenaThreadEscapeDetector();
        // Declared by every body rather than once, and that is load-bearing. The detector fixes
        // a segment's arena link when it first sees the segment: recordAccess creates the state
        // with no arena, and a recordAllocation arriving afterwards does not backfill it. With a
        // one-shot declaration any worker that reached its access first created an arena-less
        // state, the escape became invisible, and the row was silent for the whole run. Both
        // calls keep the first arena and the first owner, so declaring per body is harmless and
        // removes the ordering race entirely.
        detector.recordArena(ESCAPED_ARENA, "escaped-arena", Thread.currentThread());
        detector.recordAllocation(ESCAPED_SEGMENT, ESCAPED_ARENA, "escaped-segment", 64L);
        detector.recordAccess(ESCAPED_SEGMENT, "escaped-segment", Thread.currentThread(), true);
    }

    /** An arena per invocation, allocated, accessed and closed by the thread that opened it. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_confinedArena_accessedByItsOwner() {
        CorpusRecorder.countBodyExecution();
        Thread self = Thread.currentThread();
        var detector = AsyncTestContext.confinedArenaThreadEscapeDetector();
        Object arena = new Object();
        Object segment = new Object();
        detector.recordArena(arena, "confined-arena", self);
        detector.recordAllocation(segment, arena, "confined-segment", 64L);
        detector.recordAccess(segment, "confined-segment", self, true);
        detector.recordClose(arena, self);
    }

    // --- Three value-lifecycle stragglers -------------------------------------------------------

    /** A gatherer declared parallel with no combiner, integrated from six threads. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_gatherer_parallelWithoutACombiner() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.gathererConcurrencyMisuseDetector();
        detector.registerGatherer("parallel-gatherer", false, true);
        detector.recordIntegrate("parallel-gatherer", Thread.currentThread());
    }

    /** The same integrations against a sequential gatherer that has a combiner. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_gatherer_sequentialWithACombiner() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.gathererConcurrencyMisuseDetector();
        detector.registerGatherer("sequential-gatherer", true, false);
        detector.recordIntegrate("sequential-gatherer", Thread.currentThread());
    }

    /** A lazy constant whose computation finishes with no value. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_lazyConstant_computedToNothing() {
        CorpusRecorder.countBodyExecution();
        Thread self = Thread.currentThread();
        var detector = AsyncTestContext.lazyConstantMisuseDetector();
        String name = perInvocation("empty-constant");
        detector.recordComputeStart(name, self);
        detector.recordComputeEnd(name, self, null);
    }

    /** The same computation ending with a value, on a name unique to this invocation. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_lazyConstant_computedToAValue() {
        CorpusRecorder.countBodyExecution();
        Thread self = Thread.currentThread();
        var detector = AsyncTestContext.lazyConstantMisuseDetector();
        String name = perInvocation("filled-constant");
        detector.recordGet(name, self);
        detector.recordComputeStart(name, self);
        detector.recordComputeEnd(name, self, "value");
    }

    /** A lazily computed entry that finishes with no value, so the key stays absent. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_lazyCollection_entryComputedToNothing() {
        CorpusRecorder.countBodyExecution();
        Thread self = Thread.currentThread();
        var detector = AsyncTestContext.lazyCollectionMisuseDetector();
        String key = perInvocation("empty-entry");
        detector.recordComputeStart("lazy-cache", key, self);
        detector.recordComputeEnd("lazy-cache", key, self, null);
    }

    /** The same computation producing a value, under a key unique to this invocation. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_lazyCollection_entryComputedToAValue() {
        CorpusRecorder.countBodyExecution();
        Thread self = Thread.currentThread();
        var detector = AsyncTestContext.lazyCollectionMisuseDetector();
        String key = perInvocation("filled-entry");
        detector.recordGet("lazy-cache-filled", key, self);
        detector.recordComputeStart("lazy-cache-filled", key, self);
        detector.recordComputeEnd("lazy-cache-filled", key, self, "value");
    }

    // --- The last of the pairable set ------------------------------------------------------------

    /** One Random contended by six threads: thread-safe, and a contention note all the same. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_random_sharedAcrossThreads() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.sharedRandomDetector();
        detector.registerRandom(SHARED_RANDOM, "shared-random");
        detector.recordRandomAccess(SHARED_RANDOM, "shared-random", "nextInt");
        SHARED_RANDOM.nextInt();
    }

    /** A Random per thread, which is what removes the contention the row above reports. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_random_confinedToOneThreadEach() {
        CorpusRecorder.countBodyExecution();
        java.util.Random mine = CONFINED_RANDOM.get();
        var detector = AsyncTestContext.sharedRandomDetector();
        detector.registerRandom(mine, "confined-random");
        detector.recordRandomAccess(mine, "confined-random", "nextInt");
        mine.nextInt();
    }

    /** A task on a single-threaded scheduler recorded as taking five seconds. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_scheduledExecutor_taskOverranItsPeriod() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.scheduledExecutorDetector();
        detector.registerExecutor(slowScheduler, "slow-scheduler", 1);
        detector.recordTaskComplete(slowScheduler, "slow-scheduler", "overrunning", 5_000L);
        // Both halves record the shutdown. analyze() also reports a scheduler that was
        // registered and never shut down, so leaving it out of either row would make the pair
        // separate on two things at once - and would have let the silent twin report for a
        // reason that has nothing to do with how long the task took.
        detector.recordShutdown(slowScheduler);
    }

    /** The same scheduler through schedule, start, a millisecond completion and a shutdown. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_scheduledExecutor_taskFinishedPromptly() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.scheduledExecutorDetector();
        detector.registerExecutor(promptScheduler, "prompt-scheduler", 1);
        detector.recordSchedule(promptScheduler, "prompt-scheduler", "prompt");
        detector.recordTaskStart(promptScheduler, "prompt-scheduler", "prompt");
        detector.recordTaskComplete(promptScheduler, "prompt-scheduler", "prompt", 5L);
        detector.recordShutdown(promptScheduler);
    }

    /** A task recorded as forked and never joined: its result and its exception both go. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_forkJoin_forkedWithoutJoining() {
        CorpusRecorder.countBodyExecution();
        AsyncTestContext.forkJoinPoolDetector()
                .recordForkWithoutJoin("orphaned-pool", perInvocation("orphan-task"));
    }

    /** The same fork with its join recorded behind it. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_forkJoin_forkedAndJoined() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.forkJoinPoolDetector();
        detector.registerPool(FORK_JOIN_POOL, "joined-pool", 4);
        String task = perInvocation("joined-task");
        detector.recordFork(FORK_JOIN_POOL, "joined-pool", task);
        detector.recordJoin(FORK_JOIN_POOL, "joined-pool", task);
        detector.recordTaskTime(FORK_JOIN_POOL, "joined-pool", 1L);
    }

    /**
     * Ten thousand iterations before any yield: the detector's stated spin threshold.
     *
     * <p>Recorded once for the run rather than per body. The threshold is a count on one shared
     * detector, so one body reaching it is what the finding needs, and doing it 240 times would
     * add 2.4 million calls to the lane for no extra evidence.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_spinLoop_ranWithoutYielding() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.busyWaitDetector();
        if (spinRecorded.compareAndSet(false, true)) {
            for (int i = 0; i < 10_000; i++) {
                detector.recordLoopIteration();
            }
            detector.recordYield();
        }
    }

    /** A hundred iterations between yields: the fast path of an ordinary lock. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_spinLoop_yieldedOften() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.busyWaitDetector();
        for (int i = 0; i < 100; i++) {
            detector.recordLoopIteration();
        }
        detector.recordYield();
    }

    /** A request recorded as sent with no response ever recorded for it. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_httpRequest_sentWithNoResponse() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.httpClientDetector();
        Object client = new Object();
        detector.recordClientCreated(client, "unanswered-client");
        detector.recordRequestSent(new Object(), perInvocation("unanswered-request"));
    }

    /** The same request with its response recorded under the same unique name. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_httpRequest_answered() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.httpClientDetector();
        Object client = new Object();
        detector.recordClientCreated(client, "answered-client");
        String name = perInvocation("answered-request");
        detector.recordRequestSent(new Object(), name);
        detector.recordResponseReceived(new Object(), name);
    }

    /** Every recorded compare-and-set fails: work thrown away and retried. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_atomic_casRetriedUnderContention() {
        CorpusRecorder.countBodyExecution();
        // Ten per body, so the run clears the detector's 1000-attempt threshold with room to
        // spare. Both halves of this pair make the same number of attempts: the ratio is the
        // only thing that differs, and a silent twin that fell short of the threshold would be
        // silent for want of traffic rather than because the model decided anything.
        var detector = AsyncTestContext.highContentionAtomicDetector();
        for (int i = 0; i < 10; i++) {
            detector.recordCasAttempt(CONTENDED_ATOMIC, false);
        }
    }

    /** The same attempts, every one succeeding: an uncontended atomic. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_atomic_casSucceededFirstTime() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.highContentionAtomicDetector();
        for (int i = 0; i < 10; i++) {
            detector.recordCasAttempt(UNCONTENDED_ATOMIC, true);
        }
    }

    /** A task on a pool of one waiting for a sibling that pool can never start. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_executor_taskWaitedOnItsSibling() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.executorDeadlockDetector();
        detector.registerExecutor(DEADLOCKING_POOL, "deadlocking-pool", 1);
        // Two submissions to one start, so submitted minus running leaves work queued. The rule
        // is "every worker is waiting on a sibling AND something is still queued": a body that
        // submits and starts exactly one task leaves nothing queued and reports nothing, however
        // many waits it records. Both halves of the pair keep this shape, so only the pool size
        // separates them.
        detector.recordTaskSubmitted(DEADLOCKING_POOL);
        detector.recordTaskSubmitted(DEADLOCKING_POOL);
        detector.recordTaskStarted(DEADLOCKING_POOL);
        detector.recordWaitingOnSibling(DEADLOCKING_POOL);
    }

    /** The identical wait on a pool sized above the whole run. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_executor_taskWaitedWithThreadsToSpare() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.executorDeadlockDetector();
        detector.registerExecutor(ROOMY_POOL, "roomy-pool", MORE_THREADS_THAN_THE_RUN);
        detector.recordTaskSubmitted(ROOMY_POOL);
        detector.recordTaskSubmitted(ROOMY_POOL);
        detector.recordTaskStarted(ROOMY_POOL);
        detector.recordWaitingOnSibling(ROOMY_POOL);
    }

    /** Every thread of a pool of one recorded blocked waiting on a future. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_future_blockedOnAFullPool() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.futureBlockingDetector();
        detector.registerExecutor(BLOCKED_POOL, "blocked-pool", 1);
        detector.recordTaskSubmitted(BLOCKED_POOL);
        detector.recordTaskSubmitted(BLOCKED_POOL);
        detector.recordTaskStarted(BLOCKED_POOL);
        detector.recordBlockingWait(BLOCKED_POOL);
    }

    /** The same blocking wait on a pool sized above the whole run. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_future_blockedWithThreadsToSpare() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.futureBlockingDetector();
        detector.registerExecutor(ROOMY_BLOCKED_POOL, "roomy-blocked-pool",
                MORE_THREADS_THAN_THE_RUN);
        detector.recordTaskSubmitted(ROOMY_BLOCKED_POOL);
        detector.recordTaskSubmitted(ROOMY_BLOCKED_POOL);
        detector.recordTaskStarted(ROOMY_BLOCKED_POOL);
        detector.recordBlockingWait(ROOMY_BLOCKED_POOL);
    }

    /** An onNext delivered after the subscriber was completed: onComplete is terminal. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_flowSubscriber_signalledAfterCompletion() {
        CorpusRecorder.countBodyExecution();
        Thread self = Thread.currentThread();
        var detector = AsyncTestContext.flowPublisherConcurrencyDetector();
        detector.recordSubscribe(COMPLETED_SUBSCRIBER, "completed-subscriber", self);
        detector.recordComplete(COMPLETED_SUBSCRIBER, self);
        detector.recordNextStart(COMPLETED_SUBSCRIBER, self);
        detector.recordNextEnd(COMPLETED_SUBSCRIBER);
    }

    /** A subscriber per invocation taken through the protocol in the order it specifies. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_flowSubscriber_signalledInOrder() {
        CorpusRecorder.countBodyExecution();
        Thread self = Thread.currentThread();
        var detector = AsyncTestContext.flowPublisherConcurrencyDetector();
        Object subscriber = new Object();
        detector.recordSubscribe(subscriber, perInvocation("ordered-subscriber"), self);
        detector.recordRequest(subscriber, 1L);
        detector.recordNextStart(subscriber, self);
        detector.recordNextEnd(subscriber);
        detector.recordComplete(subscriber, self);
    }

    // --- The last three -------------------------------------------------------------------------

    /** A write stamp taken and never released: StampedLock has no owner to recover it. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_stampedLock_stampNeverReleased() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.stampedLockDetector();
        detector.registerLock(LEAKED_STAMPED_LOCK, "leaked-stamp");
        long stamp = UNIQUE_KEYS.incrementAndGet();
        detector.recordWriteLock(LEAKED_STAMPED_LOCK, "leaked-stamp", stamp);
        // Declared, not inferred. analyze() reports only what the body reported: an unmatched
        // recordWriteLock produces nothing on its own, because the detector does not treat a
        // missing unlock as a leak. That is the same caller-declares shape as the interrupt
        // pairs, and it is why this detector reads as a prompt rather than a verdict.
        detector.recordStampNotReleased("leaked-stamp", stamp);
    }

    /** The same acquisition with its unlock recorded against the same stamp. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_stampedLock_stampReleased() {
        CorpusRecorder.countBodyExecution();
        var detector = AsyncTestContext.stampedLockDetector();
        detector.registerLock(RELEASED_STAMPED_LOCK, "released-stamp");
        long stamp = UNIQUE_KEYS.incrementAndGet();
        detector.recordWriteLock(RELEASED_STAMPED_LOCK, "released-stamp", stamp);
        detector.recordUnlock(RELEASED_STAMPED_LOCK, "released-stamp", stamp);
    }

    /** A caught InterruptedException with no restore recorded against it. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_interruptedException_swallowedWholesale() {
        CorpusRecorder.countBodyExecution();
        Thread.currentThread().interrupt();
        try {
            Thread.sleep(1);
        } catch (InterruptedException caught) {
            AsyncTestContext.interruptMonitor().recordInterruptException(caught);
        }
    }

    /** The same catch with the restore recorded behind it, so catches and restores balance. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_interruptedException_restoredAfterCatching() {
        CorpusRecorder.countBodyExecution();
        var monitor = AsyncTestContext.interruptMonitor();
        Thread.currentThread().interrupt();
        try {
            Thread.sleep(1);
        } catch (InterruptedException caught) {
            monitor.recordInterruptException(caught);
            Thread.currentThread().interrupt();
            monitor.recordInterruptRestored();
        }
        // Cleared after the record, for the same reason as the INTERRUPT_SWALLOWING twin: the
        // restored flag is the fix under test, not something to hand to the runner's barrier.
        Thread.interrupted();
    }

    /** One SecureRandom recorded from six threads: a contention note, not a corruption claim. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_secureRandom_sharedAcrossThreads() {
        CorpusRecorder.countBodyExecution();
        AsyncTestContext.sharedSecureRandomDetector()
                .recordAccess(SHARED_SECURE_RANDOM, "shared-csprng", Thread.currentThread());
    }

    /** An instance per thread, which is what removes the contention. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_secureRandom_confinedToOneThreadEach() {
        CorpusRecorder.countBodyExecution();
        AsyncTestContext.sharedSecureRandomDetector()
                .recordAccess(CONFINED_SECURE_RANDOM.get(), "confined-csprng",
                        Thread.currentThread());
    }

    private static void toleratingCorruption(Runnable operation) {
        try {
            operation.run();
        } catch (RuntimeException thrown) {
            CorpusRecorder.recordCrash(thrown);
        }
    }
}
