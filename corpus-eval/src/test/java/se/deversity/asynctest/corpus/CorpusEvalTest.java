package se.deversity.asynctest.corpus;

import com.google.common.base.Suppliers;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ConcurrentHashMultiset;
import com.google.common.collect.EvictingQueue;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
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
import se.deversity.asynctest.AsyncTest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Date;
import java.util.HashMap;
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
    private final ConcurrentHashMultiset<String> concurrentMultiset = ConcurrentHashMultiset.create();
    private final Supplier<Object> memoized = Suppliers.memoize(Object::new);

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
        Path report = CorpusReport.write(
                CorpusRecorder.findings(), CorpusRecorder.crashes(), THREADS, INVOCATIONS);
        System.out.println("Corpus report written to " + report.toAbsolutePath());
        System.out.println(CorpusReport.summary(CorpusRecorder.findings(), CorpusRecorder.crashes()));
        CorpusGates.check(CorpusRecorder.findings(), CorpusRecorder.crashes());
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
}
