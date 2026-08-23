package se.deversity.asynctest.corpus;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The corpus: third-party classes whose own javadoc states a thread-safety contract.
 *
 * <p>Every row quotes that sentence and names the file and line in the library's sources jar, so
 * the ground truth can be checked without trusting this module. Line numbers are for the exact
 * versions the pom resolves; {@code CorpusEvalTest} fails if a test method has no row here, so a
 * subject cannot be exercised without a documented contract behind it.
 */
final class Corpus {

    private static final String LANG3 = "commons-lang3:3.20.0";
    private static final String COLLECTIONS4 = "commons-collections4:4.5.0";
    private static final String GUAVA = "guava:33.4.8-jre";

    private static final List<Subject> SUBJECTS = List.of(

            // --- Documented NOT thread-safe: sharing one instance across threads is a real defect.

            new Subject("mutableInt_incrementAndGet", LANG3,
                    "org.apache.commons.lang3.mutable.MutableInt", Contract.NOT_THREAD_SAFE,
                    "immediately after the increment operation. This method is not thread safe.",
                    "org/apache/commons/lang3/mutable/MutableInt.java:286"),

            new Subject("mutableLong_incrementAndGet", LANG3,
                    "org.apache.commons.lang3.mutable.MutableLong", Contract.NOT_THREAD_SAFE,
                    "immediately after the increment operation. This method is not thread safe.",
                    "org/apache/commons/lang3/mutable/MutableLong.java:286"),

            new Subject("stopWatch_splitAndGet", LANG3,
                    "org.apache.commons.lang3.time.StopWatch", Contract.NOT_THREAD_SAFE,
                    "This class is not thread-safe.",
                    "org/apache/commons/lang3/time/StopWatch.java:67"),

            new Subject("lruMap_putAndGet", COLLECTIONS4,
                    "org.apache.commons.collections4.map.LRUMap", Contract.NOT_THREAD_SAFE,
                    "Note that LRUMap is not synchronized and is not thread-safe.",
                    "org/apache/commons/collections4/map/LRUMap.java:55"),

            new Subject("flat3Map_putAndGet", COLLECTIONS4,
                    "org.apache.commons.collections4.map.Flat3Map", Contract.NOT_THREAD_SAFE,
                    "Note that Flat3Map is not synchronized and is not thread-safe.",
                    "org/apache/commons/collections4/map/Flat3Map.java:72"),

            new Subject("listOrderedMap_putAndGet", COLLECTIONS4,
                    "org.apache.commons.collections4.map.ListOrderedMap", Contract.NOT_THREAD_SAFE,
                    "Note that ListOrderedMap is not synchronized and is not thread-safe.",
                    "org/apache/commons/collections4/map/ListOrderedMap.java:57"),

            new Subject("passiveExpiringMap_putAndGet", COLLECTIONS4,
                    "org.apache.commons.collections4.map.PassiveExpiringMap", Contract.NOT_THREAD_SAFE,
                    "Note that PassiveExpiringMap is not synchronized and is not thread-safe.",
                    "org/apache/commons/collections4/map/PassiveExpiringMap.java:51"),

            new Subject("arrayListMultimap_put", GUAVA,
                    "com.google.common.collect.ArrayListMultimap", Contract.NOT_THREAD_SAFE,
                    "This class is not threadsafe when any concurrent operations update the multimap.",
                    "com/google/common/collect/ArrayListMultimap.java:52"),

            new Subject("evictingQueue_addAndPoll", GUAVA,
                    "com.google.common.collect.EvictingQueue", Contract.NOT_THREAD_SAFE,
                    "This class is not thread-safe, and does not accept null elements.",
                    "com/google/common/collect/EvictingQueue.java:42"),

            // --- Documented thread-safe: concurrent use is what the class is for, so a finding
            // --- above the noise floor is a false positive.

            new Subject("fastDateFormat_format", LANG3,
                    "org.apache.commons.lang3.time.FastDateFormat", Contract.THREAD_SAFE,
                    "FastDateFormat is a fast and thread-safe version of SimpleDateFormat.",
                    "org/apache/commons/lang3/time/FastDateFormat.java:33"),

            new Subject("atomicSafeInitializer_get", LANG3,
                    "org.apache.commons.lang3.concurrent.AtomicSafeInitializer", Contract.THREAD_SAFE,
                    "this class is based on atomic variables, so it can create an object under "
                            + "concurrent access without synchronization.",
                    "org/apache/commons/lang3/concurrent/AtomicSafeInitializer.java:31"),

            new Subject("lazyInitializer_get", LANG3,
                    "org.apache.commons.lang3.concurrent.LazyInitializer", Contract.THREAD_SAFE,
                    "The class already implements all necessary synchronization.",
                    "org/apache/commons/lang3/concurrent/LazyInitializer.java:32"),

            new Subject("synchronizedBag_addAndCount", COLLECTIONS4,
                    "org.apache.commons.collections4.bag.SynchronizedBag", Contract.THREAD_SAFE,
                    "Decorates another Bag to synchronize its behavior for a multithreaded environment.",
                    "org/apache/commons/collections4/bag/SynchronizedBag.java:25"),

            new Subject("rateLimiter_tryAcquire", GUAVA,
                    "com.google.common.util.concurrent.RateLimiter", Contract.THREAD_SAFE,
                    "RateLimiter is safe for concurrent use.",
                    "com/google/common/util/concurrent/RateLimiter.java:42"),

            new Subject("eventBus_post", GUAVA,
                    "com.google.common.eventbus.EventBus", Contract.THREAD_SAFE,
                    "This class is safe for concurrent use.",
                    "com/google/common/eventbus/EventBus.java:145"),

            new Subject("bloomFilter_putAndMightContain", GUAVA,
                    "com.google.common.hash.BloomFilter", Contract.THREAD_SAFE,
                    "As of Guava 23.0, this class is thread-safe and lock-free.",
                    "com/google/common/hash/BloomFilter.java:63"),

            new Subject("atomicLongMap_incrementAndGet", GUAVA,
                    "com.google.common.util.concurrent.AtomicLongMap", Contract.THREAD_SAFE,
                    "Instances of this class may be used by multiple threads concurrently.",
                    "com/google/common/util/concurrent/AtomicLongMap.java:46"),

            new Subject("concurrentHashMultiset_add", GUAVA,
                    "com.google.common.collect.ConcurrentHashMultiset", Contract.THREAD_SAFE,
                    "A multiset that supports concurrent modifications and that provides atomic "
                            + "versions of most Multiset operations.",
                    "com/google/common/collect/ConcurrentHashMultiset.java:51"),

            new Subject("memoizedSupplier_get", GUAVA,
                    "com.google.common.base.Suppliers", Contract.THREAD_SAFE,
                    "The returned supplier is thread-safe.",
                    "com/google/common/base/Suppliers.java:100")
    );

    private static final Map<String, Subject> BY_METHOD = SUBJECTS.stream()
            .collect(Collectors.toUnmodifiableMap(Subject::testMethod, Function.identity()));

    private Corpus() {
    }

    static List<Subject> subjects() {
        return SUBJECTS;
    }

    static Subject byTestMethod(String testMethod) {
        return BY_METHOD.get(testMethod);
    }

    static long count(Contract contract) {
        return SUBJECTS.stream().filter(subject -> subject.contract() == contract).count();
    }
}
