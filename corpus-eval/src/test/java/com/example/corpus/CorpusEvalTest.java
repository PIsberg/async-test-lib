package com.example.corpus;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.base.Stopwatch;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.MinMaxPriorityQueue;
import com.google.common.io.PatternFilenameFilter;
import com.google.common.math.StatsAccumulator;
import org.apache.commons.collections4.comparators.FixedOrderComparator;
import org.apache.commons.collections4.map.CaseInsensitiveMap;
import org.apache.commons.collections4.map.HashedMap;
import org.apache.commons.collections4.map.LazyMap;
import org.apache.commons.collections4.map.LinkedMap;
import org.apache.commons.collections4.map.MultiKeyMap;
import com.google.common.base.Suppliers;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ConcurrentHashMultiset;
import com.google.common.collect.EvictingQueue;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import com.google.common.io.FileBackedOutputStream;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.AtomicLongMap;
import com.google.common.util.concurrent.RateLimiter;
import org.apache.commons.collections4.bag.HashBag;
import org.apache.commons.collections4.bag.SynchronizedBag;
import org.apache.commons.collections4.map.Flat3Map;
import org.apache.commons.collections4.map.LRUMap;
import org.apache.commons.collections4.map.ListOrderedMap;
import org.apache.commons.collections4.map.PassiveExpiringMap;
import org.apache.commons.lang3.concurrent.AtomicSafeInitializer;
import org.apache.commons.lang3.concurrent.ConcurrentException;
import org.apache.commons.lang3.concurrent.LazyInitializer;
import org.apache.commons.lang3.mutable.MutableInt;
import org.apache.commons.lang3.mutable.MutableLong;
import org.apache.commons.lang3.time.FastDateFormat;
import org.apache.commons.lang3.time.StopWatch;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.ExtendWith;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;
import org.springframework.util.ConcurrentReferenceHashMap;
import se.deversity.asynctest.AsyncTest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Runs every {@link Corpus} subject under {@code @AsyncTest} and records what the detectors say.
 *
 * <p>Each subject is one shared instance, exercised by {@link #THREADS} threads for
 * {@link #INVOCATIONS} rounds. For a documented-not-thread-safe subject that sharing is the defect
 * a user would have written; for a documented-thread-safe one it is the usage the class exists for,
 * so a finding there is noise. {@code failOn} stays at its default of NONE: this module measures,
 * it does not gate on what the detectors happen to see.
 */
@ExtendWith(SubjectTracking.class)
class CorpusEvalTest {

    static final int THREADS = 6;
    static final int INVOCATIONS = 40;

    // --- documented NOT thread-safe -------------------------------------------------------

    private final MutableInt mutableInt = new MutableInt();
    private final MutableLong mutableLong = new MutableLong();
    private final StopWatch stopWatch = StopWatch.createStarted();
    private final LRUMap<String, String> lruMap = new LRUMap<>(8);
    private final Flat3Map<String, String> flat3Map = new Flat3Map<>();
    private final ListOrderedMap<String, String> listOrderedMap =
            ListOrderedMap.listOrderedMap(new HashMap<>());
    private final PassiveExpiringMap<String, String> passiveExpiringMap =
            new PassiveExpiringMap<>(60_000L);
    private final ArrayListMultimap<String, String> multimap = ArrayListMultimap.create();
    private final EvictingQueue<String> evictingQueue = EvictingQueue.create(16);

    // --- documented NOT thread-safe, second wave: state in arrays, nodes and primitives

    private final Stopwatch stopwatch = Stopwatch.createStarted();
    private final StatsAccumulator statsAccumulator = new StatsAccumulator();
    private final HashMultimap<String, String> hashMultimap = HashMultimap.create();
    private final LinkedListMultimap<String, String> linkedListMultimap = LinkedListMultimap.create();
    private final MinMaxPriorityQueue<Integer> minMaxQueue = MinMaxPriorityQueue.maximumSize(32).create();
    private final HashedMap<String, String> hashedMap = new HashedMap<>();
    private final LinkedMap<String, String> linkedMap = new LinkedMap<>();
    private final MultiKeyMap<String, String> multiKeyMap = new MultiKeyMap<>();
    private final CaseInsensitiveMap<String, String> caseInsensitiveMap = new CaseInsensitiveMap<>();
    private final Map<String, String> lazyMap = LazyMap.lazyMap(new HashMap<>(), key -> "value");

    // --- documented thread-safe, second wave: immutable and post-setup-locked subjects

    private static final Joiner JOINER = Joiner.on(',').skipNulls();
    private static final Splitter SPLITTER = Splitter.on(',').trimResults();
    private final PatternFilenameFilter filenameFilter = new PatternFilenameFilter("[a-z]+\\.txt");
    private final FixedOrderComparator<String> fixedOrderComparator = lockedComparator();

    // --- documented thread-safe, third wave: synchronized methods guarding @GuardedBy("this") fields

    /** Threshold far above what one body writes, so the stream stays in memory and on this JVM. */
    private final FileBackedOutputStream fileBackedOutputStream = new FileBackedOutputStream(1 << 20);

    /**
     * Builds the comparator and completes its setup, which is what its contract requires before
     * concurrent use: the first comparison locks it, and a lock attempt after that throws.
     */
    private static FixedOrderComparator<String> lockedComparator() {
        FixedOrderComparator<String> comparator = new FixedOrderComparator<>("a", "b", "c");
        comparator.compare("a", "b");
        return comparator;
    }

    // --- documented thread-safe -----------------------------------------------------------

    private final FastDateFormat fastDateFormat = FastDateFormat.getInstance("yyyy-MM-dd HH:mm:ss");
    private final AtomicSafeInitializer<Object> atomicSafeInitializer = new AtomicSafeInitializer<>() {
        @Override
        protected Object initialize() {
            return new Object();
        }
    };
    private final LazyInitializer<Object> lazyInitializer = new LazyInitializer<>() {
        @Override
        protected Object initialize() {
            return new Object();
        }
    };
    private final SynchronizedBag<String> synchronizedBag = SynchronizedBag.synchronizedBag(new HashBag<>());
    private final RateLimiter rateLimiter = RateLimiter.create(1_000_000.0);
    private final EventBus eventBus = new EventBus();
    private final AtomicInteger eventsSeen = new AtomicInteger();
    private final BloomFilter<CharSequence> bloomFilter =
            BloomFilter.create(Funnels.stringFunnel(StandardCharsets.UTF_8), 10_000);
    private final AtomicLongMap<String> atomicLongMap = AtomicLongMap.create();

    /**
     * A guava cache loaded through its {@code CacheLoader}, which is the interesting path.
     *
     * <p>A miss makes one thread compute while the others wait on the same entry, so this is
     * where a cache looks most like a race to anything watching. The javadoc promises it is not
     * one. The loader is deliberately trivial: the subject is the cache's own synchronization,
     * not whatever the loader does.
     */
    private final LoadingCache<String, String> guavaLoadingCache = CacheBuilder.newBuilder()
            .maximumSize(64)
            .build(CacheLoader.from(key -> key + "-loaded"));
    private final ConcurrentHashMultiset<String> concurrentMultiset = ConcurrentHashMultiset.create();
    private final Supplier<Object> memoized = Suppliers.memoize(Object::new);

    // --- fourth wave (#302): four libraries outside the Apache/Guava axis -------------------

    /** The one payload every JSON subject reads or writes, kept small so weaving cost stays flat. */
    private static final Map<String, String> PAYLOAD = Map.of("key", "value");
    private static final String JSON = "{\"key\":\"value\"}";

    /** Configured here and never again, which is exactly what its contract asks for. */
    private final ObjectMapper configuredMapper =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** A second mapper, reconfigured while other threads write through it: the documented defect. */
    private final ObjectMapper reconfiguredMapper = new ObjectMapper();

    private final ObjectReader objectReader = new ObjectMapper().readerFor(Map.class);
    private final ObjectWriter objectWriter = new ObjectMapper().writerFor(Map.class);

    private final Cache<String, String> caffeineCache = Caffeine.newBuilder().maximumSize(64).build();
    private final Cache<String, String> caffeineAsMapCache = Caffeine.newBuilder().maximumSize(64).build();

    /**
     * Bounded and cache-free on purpose. The default allocator keeps a {@code PoolThreadCache}
     * per thread, and {@code @AsyncTest} runs one virtual thread per task, so the default would
     * build one cache per round per thread and measure the JVM's memory rather than the
     * allocator's thread-safety. Two heap arenas, no direct arenas, no caches.
     */
    private final ByteBufAllocator pooledAllocator =
            new PooledByteBufAllocator(false, 2, 0, 8192, 9, 0, 0, false);

    private final ConcurrentReferenceHashMap<String, String> referenceMap =
            new ConcurrentReferenceHashMap<>();

    /** Subscriber for the EventBus subject; its own state is atomic, so the bus is the subject. */
    final class CountingSubscriber {
        @Subscribe
        public void onEvent(String event) {
            eventsSeen.incrementAndGet();
        }
    }

    {
        eventBus.register(new CountingSubscriber());
    }

    @BeforeAll
    static void installRecorder() {
        CorpusRecorder.install();
    }

    @AfterAll
    static void reportAndGate() {
        CorpusRecorder.uninstall();
        CorpusLane lane = CorpusLane.current();
        Path report = CorpusReport.write(
                CorpusRecorder.findings(), CorpusRecorder.crashes(), THREADS, INVOCATIONS, lane);
        System.out.println("Corpus report written to " + report.toAbsolutePath());
        System.out.println(CorpusReport.exposure(CorpusRecorder.findings(), lane));
        System.out.println(CorpusReport.summary(CorpusRecorder.findings(), CorpusRecorder.crashes(), lane));
        CorpusGates.check(CorpusRecorder.findings(), CorpusRecorder.crashes(), lane);
    }

    /**
     * Runs an operation whose subject documents itself as not thread-safe. Corruption there can
     * surface as a thrown exception rather than as a detector finding, and that outcome is part of
     * what the eval measures, so it is recorded instead of failing the run.
     */
    private static void unsafeOperation(Runnable operation) {
        CorpusRecorder.countBodyExecution();
        try {
            operation.run();
        } catch (RuntimeException thrown) {
            CorpusRecorder.recordCrash(thrown);
        }
    }

    /** Runs an operation on a subject documented as safe for concurrent use. */
    private static void safeOperation(Runnable operation) {
        CorpusRecorder.countBodyExecution();
        operation.run();
    }

    // --- subjects documented as NOT thread-safe -------------------------------------------

    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void mutableInt_incrementAndGet() {
        unsafeOperation(mutableInt::incrementAndGet);
    }

    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void mutableLong_incrementAndGet() {
        unsafeOperation(mutableLong::incrementAndGet);
    }

    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void stopWatch_splitAndGet() {
        unsafeOperation(() -> {
            stopWatch.split();
            stopWatch.getSplitTime();
            stopWatch.unsplit();
        });
    }

    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void lruMap_putAndGet() {
        unsafeOperation(() -> {
            lruMap.put("key", "value");
            lruMap.get("key");
        });
    }

    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void flat3Map_putAndGet() {
        unsafeOperation(() -> {
            flat3Map.put("key", "value");
            flat3Map.get("key");
        });
    }

    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void listOrderedMap_putAndGet() {
        unsafeOperation(() -> {
            listOrderedMap.put("key", "value");
            listOrderedMap.get("key");
        });
    }

    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void passiveExpiringMap_putAndGet() {
        unsafeOperation(() -> {
            passiveExpiringMap.put("key", "value");
            passiveExpiringMap.get("key");
        });
    }

    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void arrayListMultimap_put() {
        unsafeOperation(() -> {
            multimap.put("key", "value");
            multimap.get("key");
        });
    }

    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void evictingQueue_addAndPoll() {
        unsafeOperation(() -> {
            evictingQueue.add("element");
            evictingQueue.poll();
        });
    }

    // --- subjects documented as thread-safe ------------------------------------------------

    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void fastDateFormat_format() {
        safeOperation(() -> fastDateFormat.format(new Date(1_700_000_000_000L)));
    }

    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void atomicSafeInitializer_get() {
        safeOperation(() -> {
            try {
                atomicSafeInitializer.get();
            } catch (ConcurrentException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void lazyInitializer_get() {
        safeOperation(() -> {
            try {
                lazyInitializer.get();
            } catch (ConcurrentException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void synchronizedBag_addAndCount() {
        safeOperation(() -> {
            synchronizedBag.add("element");
            synchronizedBag.getCount("element");
        });
    }

    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void rateLimiter_tryAcquire() {
        safeOperation(() -> rateLimiter.tryAcquire(1, 0, TimeUnit.MILLISECONDS));
    }

    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void eventBus_post() {
        safeOperation(() -> eventBus.post("event"));
    }

    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void bloomFilter_putAndMightContain() {
        safeOperation(() -> {
            bloomFilter.put("element");
            bloomFilter.mightContain("element");
        });
    }

    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void atomicLongMap_incrementAndGet() {
        safeOperation(() -> atomicLongMap.incrementAndGet("key"));
    }

    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void guavaLoadingCache_get() {
        safeOperation(() -> guavaLoadingCache.getUnchecked("key"));
    }

    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void concurrentHashMultiset_add() {
        safeOperation(() -> {
            concurrentMultiset.add("element");
            concurrentMultiset.count("element");
        });
    }

    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void memoizedSupplier_get() {
        safeOperation(memoized::get);
    }

    // --- subjects documented as NOT thread-safe, second wave --------------------------------

    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void guavaStopwatch_startStop() {
        unsafeOperation(() -> {
            if (stopwatch.isRunning()) {
                stopwatch.stop();
            } else {
                stopwatch.start();
            }
        });
    }

    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void statsAccumulator_add() {
        unsafeOperation(() -> statsAccumulator.add(1.5));
    }

    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void hashMultimap_put() {
        unsafeOperation(() -> {
            hashMultimap.put("key", "value");
            hashMultimap.get("key");
        });
    }

    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void linkedListMultimap_put() {
        unsafeOperation(() -> {
            linkedListMultimap.put("key", "value");
            linkedListMultimap.get("key");
        });
    }

    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void minMaxPriorityQueue_addAndPoll() {
        unsafeOperation(() -> {
            minMaxQueue.add(7);
            minMaxQueue.poll();
        });
    }

    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void hashedMap_putAndGet() {
        unsafeOperation(() -> {
            hashedMap.put("key", "value");
            hashedMap.get("key");
        });
    }

    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void linkedMap_putAndGet() {
        unsafeOperation(() -> {
            linkedMap.put("key", "value");
            linkedMap.get("key");
        });
    }

    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void multiKeyMap_putAndGet() {
        unsafeOperation(() -> {
            multiKeyMap.put("first", "second", "value");
            multiKeyMap.get("first", "second");
        });
    }

    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void caseInsensitiveMap_putAndGet() {
        unsafeOperation(() -> {
            caseInsensitiveMap.put("Key", "value");
            caseInsensitiveMap.get("kEY");
        });
    }

    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void lazyMap_get() {
        unsafeOperation(() -> lazyMap.get("key"));
    }

    // --- subjects documented as thread-safe, second wave ------------------------------------

    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void joiner_join() {
        safeOperation(() -> JOINER.join("a", "b", "c"));
    }

    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void splitter_splitToList() {
        safeOperation(() -> SPLITTER.splitToList("a, b, c"));
    }

    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void patternFilenameFilter_accept() {
        safeOperation(() -> filenameFilter.accept(new java.io.File("."), "notes.txt"));
    }

    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void fixedOrderComparator_compare() {
        safeOperation(() -> fixedOrderComparator.compare("a", "c"));
    }

    // --- subjects documented as thread-safe, third wave ------------------------------------

    /**
     * Writes then resets, so every body both reads and writes the {@code @GuardedBy("this")}
     * fields ({@code out}, {@code memory}, {@code file}) through {@code synchronized} methods.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void fileBackedOutputStream_writeAndReset() {
        safeOperation(() -> {
            try {
                fileBackedOutputStream.write(new byte[] {1, 2, 3});
                fileBackedOutputStream.reset();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    // --- fourth wave: documented NOT thread-safe --------------------------------------------

    /**
     * Reconfigures a mapper other threads are writing through, which its own javadoc calls out as
     * the case that makes an {@code ObjectMapper} unsafe. The safe half of the same contract is
     * {@link #objectMapper_configuredThenShared()}, on a different instance.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void objectMapper_reconfigureWhileWriting() {
        unsafeOperation(() -> {
            try {
                reconfiguredMapper.setDateFormat(new SimpleDateFormat("yyyy-MM-dd"));
                reconfiguredMapper.writeValueAsString(PAYLOAD);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    // --- fourth wave: documented thread-safe -------------------------------------------------

    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void objectMapper_configuredThenShared() {
        safeOperation(() -> {
            try {
                configuredMapper.writeValueAsString(PAYLOAD);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void objectReader_readValue() {
        safeOperation(() -> {
            try {
                objectReader.readValue(JSON);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void objectWriter_writeValueAsString() {
        safeOperation(() -> {
            try {
                objectWriter.writeValueAsString(PAYLOAD);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /** The instance is Caffeine's {@code BoundedLocalCache}, reached through {@code Cache}. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void caffeineCache_getAndPut() {
        safeOperation(() -> {
            caffeineCache.put("key", "value");
            caffeineCache.get("key", key -> "computed");
        });
    }

    /** The {@code asMap()} view, whose javadoc promises the computation runs atomically. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void caffeineAsMap_computeIfAbsent() {
        safeOperation(() -> caffeineAsMapCache.asMap().computeIfAbsent("key", key -> "computed"));
    }

    /** The instance is a {@code PooledByteBufAllocator}, reached through {@code ByteBufAllocator}. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void pooledByteBufAllocator_bufferAndRelease() {
        safeOperation(() -> {
            ByteBuf buffer = pooledAllocator.heapBuffer(64);
            try {
                buffer.writeInt(7);
            } finally {
                buffer.release();
            }
        });
    }

    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void concurrentReferenceHashMap_putAndGet() {
        safeOperation(() -> {
            referenceMap.put("key", "value");
            referenceMap.get("key");
        });
    }
}
