package com.example.corpus;

import se.deversity.asynctest.DetectorType;
import se.deversity.asynctest.diagnostics.IssueSeverity;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private static final String JACKSON = "jackson-databind:2.22.2";
    private static final String CAFFEINE = "caffeine:3.2.4";
    private static final String NETTY = "netty-buffer:4.2.17.Final";
    private static final String SPRING = "spring-core:7.0.9";
    private static final String HIKARI = "HikariCP:7.0.2";
    private static final String GROOVY = "groovy:5.1.2";

    /**
     * The platform itself, for subjects that ship with it.
     *
     * <p>Read from {@link Runtime#version()} rather than written down. Every other constant here
     * is a literal that has to be re-pinned when the pom moves, and one of them silently did not;
     * the JDK is the one library whose version this module can simply ask for, so it does.
     */
    private static final String JDK = "jdk:" + Runtime.version().feature();

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

            new Subject("guavaStopwatch_startStop", GUAVA,
                    "com.google.common.base.Stopwatch", Contract.NOT_THREAD_SAFE,
                    "Note: This class is not thread-safe.",
                    "com/google/common/base/Stopwatch.java:80"),

            new Subject("statsAccumulator_add", GUAVA,
                    "com.google.common.math.StatsAccumulator", Contract.NOT_THREAD_SAFE,
                    "This class is not thread safe.",
                    "com/google/common/math/StatsAccumulator.java:32"),

            new Subject("hashMultimap_put", GUAVA,
                    "com.google.common.collect.HashMultimap", Contract.NOT_THREAD_SAFE,
                    "This class is not threadsafe when any concurrent operations update the multimap.",
                    "com/google/common/collect/HashMultimap.java:41"),

            new Subject("linkedListMultimap_put", GUAVA,
                    "com.google.common.collect.LinkedListMultimap", Contract.NOT_THREAD_SAFE,
                    "This class is not threadsafe when any concurrent operations update the multimap.",
                    "com/google/common/collect/LinkedListMultimap.java:88"),

            new Subject("minMaxPriorityQueue_addAndPoll", GUAVA,
                    "com.google.common.collect.MinMaxPriorityQueue", Contract.NOT_THREAD_SAFE,
                    "This class is not thread-safe, and does not accept null elements.",
                    "com/google/common/collect/MinMaxPriorityQueue.java:80"),

            new Subject("hashedMap_putAndGet", COLLECTIONS4,
                    "org.apache.commons.collections4.map.HashedMap", Contract.NOT_THREAD_SAFE,
                    "Note that HashedMap is not synchronized and is not thread-safe.",
                    "org/apache/commons/collections4/map/HashedMap.java:34"),

            new Subject("linkedMap_putAndGet", COLLECTIONS4,
                    "org.apache.commons.collections4.map.LinkedMap", Contract.NOT_THREAD_SAFE,
                    "Note that LinkedMap is not synchronized and is not thread-safe.",
                    "org/apache/commons/collections4/map/LinkedMap.java:60"),

            new Subject("multiKeyMap_putAndGet", COLLECTIONS4,
                    "org.apache.commons.collections4.map.MultiKeyMap", Contract.NOT_THREAD_SAFE,
                    "Note that MultiKeyMap is not synchronized and is not thread-safe.",
                    "org/apache/commons/collections4/map/MultiKeyMap.java:78"),

            new Subject("caseInsensitiveMap_putAndGet", COLLECTIONS4,
                    "org.apache.commons.collections4.map.CaseInsensitiveMap", Contract.NOT_THREAD_SAFE,
                    "Note that CaseInsensitiveMap is not synchronized and is not thread-safe.",
                    "org/apache/commons/collections4/map/CaseInsensitiveMap.java:63"),

            new Subject("lazyMap_get", COLLECTIONS4,
                    "org.apache.commons.collections4.map.LazyMap", Contract.NOT_THREAD_SAFE,
                    "Note that LazyMap is not synchronized and is not thread-safe.",
                    "org/apache/commons/collections4/map/LazyMap.java:56"),

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

            new Subject("sequenceWriter_write", JACKSON,
                    "com.fasterxml.jackson.databind.SequenceWriter", Contract.NOT_THREAD_SAFE,
                    "Instances of SequenceWriter are stateful, and not thread-safe: if used "
                            + "concurrently, external synchronization is necessary.",
                    "com/fasterxml/jackson/databind/SequenceWriter.java:23"),

            new Subject("hashBasedTable_put", GUAVA,
                    "com.google.common.collect.HashBasedTable", Contract.NOT_THREAD_SAFE,
                    "Note that this implementation is not synchronized. If multiple threads access "
                            + "this table concurrently and one of the threads modifies the table, "
                            + "it must be synchronized externally.",
                    "com/google/common/collect/HashBasedTable.java:42"),

            new Subject("guavaLoadingCache_get", GUAVA,
                    "com.google.common.cache.LoadingCache", Contract.THREAD_SAFE,
                    "Implementations of this interface are expected to be thread-safe, and can be "
                            + "safely accessed by multiple concurrent threads.",
                    "com/google/common/cache/LoadingCache.java:31"),

            new Subject("concurrentHashMultiset_add", GUAVA,
                    "com.google.common.collect.ConcurrentHashMultiset", Contract.THREAD_SAFE,
                    "A multiset that supports concurrent modifications and that provides atomic "
                            + "versions of most Multiset operations.",
                    "com/google/common/collect/ConcurrentHashMultiset.java:51"),

            new Subject("memoizedSupplier_get", GUAVA,
                    "com.google.common.base.Suppliers", Contract.THREAD_SAFE,
                    "The returned supplier is thread-safe.",
                    "com/google/common/base/Suppliers.java:100"),

            new Subject("joiner_join", GUAVA,
                    "com.google.common.base.Joiner", Contract.THREAD_SAFE,
                    "This makes joiners thread-safe, and safe to store as static final constants.",
                    "com/google/common/base/Joiner.java:50"),

            new Subject("splitter_splitToList", GUAVA,
                    "com.google.common.base.Splitter", Contract.THREAD_SAFE,
                    "Splitter instances are thread-safe immutable, and are therefore safe to store "
                            + "as static final constants.",
                    "com/google/common/base/Splitter.java:86"),

            new Subject("patternFilenameFilter_accept", GUAVA,
                    "com.google.common.io.PatternFilenameFilter", Contract.THREAD_SAFE,
                    "This class is thread-safe.",
                    "com/google/common/io/PatternFilenameFilter.java:26"),

            new Subject("fixedOrderComparator_compare", COLLECTIONS4,
                    "org.apache.commons.collections4.comparators.FixedOrderComparator", Contract.THREAD_SAFE,
                    "it is thread-safe to perform multiple comparisons after all the setup "
                            + "operations are complete.",
                    "org/apache/commons/collections4/comparators/FixedOrderComparator.java:43"),

            // --- Documented thread-safe, third wave: the synchronized-method idiom. Every field is
            // @GuardedBy("this") and every method is a synchronized method, which compiles to
            // ACC_SYNCHRONIZED and no monitor instruction. The corpus had no such subject until
            // the probe that found it drawing a HIGH finding.

            new Subject("fileBackedOutputStream_writeAndReset", GUAVA,
                    "com.google.common.io.FileBackedOutputStream", Contract.THREAD_SAFE,
                    "This class is thread-safe.",
                    "com/google/common/io/FileBackedOutputStream.java:59"),

            // --- Fourth wave (#302): four libraries outside the Apache/Guava axis the first three
            // --- waves came from, chosen for mechanisms the corpus had never exercised - a
            // --- reconfigurable mapper, a cache with its own eviction machinery, an arena
            // --- allocator and a reference map with lock-striped segments.

            new Subject("objectMapper_reconfigureWhileWriting", JACKSON,
                    "com.fasterxml.jackson.databind.ObjectMapper", Contract.NOT_THREAD_SAFE,
                    "ObjectWriters are thread-safe whereas ObjectMapper itself is only thread-safe "
                            + "when configuring methods (such as this one) are NOT called.",
                    "com/fasterxml/jackson/databind/ObjectMapper.java:2538"),

            new Subject("objectMapper_configuredThenShared", JACKSON,
                    "com.fasterxml.jackson.databind.ObjectMapper", Contract.THREAD_SAFE,
                    "Mapper instances are fully thread-safe provided that ALL configuration of the "
                            + "instance occurs before ANY read or write calls.",
                    "com/fasterxml/jackson/databind/ObjectMapper.java:83"),

            new Subject("objectReader_readValue", JACKSON,
                    "com.fasterxml.jackson.databind.ObjectReader", Contract.THREAD_SAFE,
                    "Uses \"mutant factory\" pattern so that instances are immutable (and thus "
                            + "fully thread-safe with no external synchronization);",
                    "com/fasterxml/jackson/databind/ObjectReader.java:31"),

            new Subject("objectWriter_writeValueAsString", JACKSON,
                    "com.fasterxml.jackson.databind.ObjectWriter", Contract.THREAD_SAFE,
                    "Instances are initially constructed by ObjectMapper and can be reused in "
                            + "completely thread-safe manner with no explicit synchronization",
                    "com/fasterxml/jackson/databind/ObjectWriter.java:31"),

            // The contract for the next three is stated on the type the instance is reached
            // through, which is where these libraries put it. The instance is named in the test
            // method's javadoc; the file and line below are where the sentence lives.

            new Subject("caffeineCache_getAndPut", CAFFEINE,
                    "com.github.benmanes.caffeine.cache.Cache", Contract.THREAD_SAFE,
                    "Implementations of this interface are expected to be thread-safe and can be "
                            + "safely accessed by multiple concurrent threads.",
                    "com/github/benmanes/caffeine/cache/Cache.java:34"),

            new Subject("caffeineAsMap_computeIfAbsent", CAFFEINE,
                    "com.github.benmanes.caffeine.cache.Cache", Contract.THREAD_SAFE,
                    "Returns a view of the entries stored in this cache as a thread-safe map. ... "
                            + "A computation operation, such as ConcurrentMap#compute, performs "
                            + "the entire method invocation atomically",
                    "com/github/benmanes/caffeine/cache/Cache.java:200"),

            new Subject("pooledByteBufAllocator_bufferAndRelease", NETTY,
                    "io.netty.buffer.ByteBufAllocator", Contract.THREAD_SAFE,
                    "Implementations are responsible to allocate buffers. Implementations of this "
                            + "interface are expected to be thread-safe.",
                    "io/netty/buffer/ByteBufAllocator.java:19"),

            new Subject("concurrentReferenceHashMap_putAndGet", SPRING,
                    "org.springframework.util.ConcurrentReferenceHashMap", Contract.THREAD_SAFE,
                    "This implementation follows the same design constraints as ConcurrentHashMap "
                            + "with the exception that null values and null keys are supported.",
                    "org/springframework/util/ConcurrentReferenceHashMap.java:51"),

            // --- Fifth wave: the documented-safe denominator, widened. A zero over 23 subjects
            // --- bounds the false-positive rate near 13% at 95%, and the bound is set by the size
            // --- of the denominator rather than by the run of zeroes. These subjects are chosen
            // --- for shared mutable state behind a real mechanism, never for being trivially
            // --- safe: a stateless utility class would enlarge the denominator without ever
            // --- having been able to draw a finding.
            // ---
            // --- The JDK rows cite a file and a sentence but no line. This module runs on 21, 25
            // --- and 26, the line moves between them and the sentence does not.

            new Subject("concurrentHashMap_putAndGet", JDK,
                    "java.util.concurrent.ConcurrentHashMap", Contract.THREAD_SAFE,
                    "even though all operations are thread-safe, retrieval operations do not "
                            + "entail locking",
                    "java.base/java/util/concurrent/ConcurrentHashMap.java"),

            new Subject("copyOnWriteArrayList_addAndIterate", JDK,
                    "java.util.concurrent.CopyOnWriteArrayList", Contract.THREAD_SAFE,
                    "A thread-safe variant of ArrayList in which all mutative operations are "
                            + "implemented by making a fresh copy of the underlying array.",
                    "java.base/java/util/concurrent/CopyOnWriteArrayList.java"),

            new Subject("stringBuffer_appendAndLength", JDK,
                    "java.lang.StringBuffer", Contract.THREAD_SAFE,
                    "A thread-safe, mutable sequence of characters.",
                    "java.base/java/lang/StringBuffer.java"),

            new Subject("concurrentLinkedQueue_addAndPoll", JDK,
                    "java.util.concurrent.ConcurrentLinkedQueue", Contract.THREAD_SAFE,
                    "An unbounded thread-safe queue based on linked nodes.",
                    "java.base/java/util/concurrent/ConcurrentLinkedQueue.java"),

            new Subject("linkedBlockingQueue_offerAndPoll", JDK,
                    "java.util.concurrent.BlockingQueue", Contract.THREAD_SAFE,
                    "BlockingQueue implementations are thread-safe.",
                    "java.base/java/util/concurrent/BlockingQueue.java"),

            new Subject("hashtable_putAndGet", JDK,
                    "java.util.Hashtable", Contract.THREAD_SAFE,
                    "Unlike the new collection implementations, Hashtable is synchronized.",
                    "java.base/java/util/Hashtable.java"),

            new Subject("concurrentSkipListMap_putAndGet", JDK,
                    "java.util.concurrent.ConcurrentSkipListMap", Contract.THREAD_SAFE,
                    "Insertion, removal, update, and access operations safely execute "
                            + "concurrently by multiple threads.",
                    "java.base/java/util/concurrent/ConcurrentSkipListMap.java"),

            new Subject("synchronizedList_addUnderItsMonitor", JDK,
                    "java.util.Collections", Contract.THREAD_SAFE,
                    "Returns a synchronized (thread-safe) list backed by the specified list.",
                    "java.base/java/util/Collections.java"),

            new Subject("threadLocalRandom_nextInt", JDK,
                    "java.util.concurrent.ThreadLocalRandom", Contract.THREAD_SAFE,
                    "A random number generator (with period 2^64) isolated to the current thread.",
                    "java.base/java/util/concurrent/ThreadLocalRandom.java"),

            new Subject("atomicInteger_incrementAndGet", JDK,
                    "java.util.concurrent.atomic.AtomicInteger", Contract.THREAD_SAFE,
                    "An int value that may be updated atomically.",
                    "java.base/java/util/concurrent/atomic/AtomicInteger.java"),

            // The lang3 concurrent package states its contract once, for the package, which is
            // where commons puts it. Cited as such rather than restated per class.

            new Subject("thresholdCircuitBreaker_incrementAndCheckState", LANG3,
                    "org.apache.commons.lang3.concurrent.ThresholdCircuitBreaker", Contract.THREAD_SAFE,
                    "#Thread safe#",
                    "org/apache/commons/lang3/concurrent/ThresholdCircuitBreaker.java:49"),

            new Subject("eventCountCircuitBreaker_incrementAndCheckState", LANG3,
                    "org.apache.commons.lang3.concurrent.EventCountCircuitBreaker", Contract.THREAD_SAFE,
                    "Provides support classes for multi-threaded programming. ... These classes "
                            + "are thread-safe.",
                    "org/apache/commons/lang3/concurrent/package-info.java:20"),

            new Subject("memoizer_compute", LANG3,
                    "org.apache.commons.lang3.concurrent.Memoizer", Contract.THREAD_SAFE,
                    "Provides support classes for multi-threaded programming. ... These classes "
                            + "are thread-safe.",
                    "org/apache/commons/lang3/concurrent/package-info.java:20"),

            new Subject("constantInitializer_get", LANG3,
                    "org.apache.commons.lang3.concurrent.ConstantInitializer", Contract.THREAD_SAFE,
                    "Provides support classes for multi-threaded programming. ... These classes "
                            + "are thread-safe.",
                    "org/apache/commons/lang3/concurrent/package-info.java:20"),

            new Subject("atomicInitializer_get", LANG3,
                    "org.apache.commons.lang3.concurrent.AtomicInitializer", Contract.THREAD_SAFE,
                    "Provides support classes for multi-threaded programming. ... These classes "
                            + "are thread-safe.",
                    "org/apache/commons/lang3/concurrent/package-info.java:20"),

            new Subject("range_contains", LANG3,
                    "org.apache.commons.lang3.Range", Contract.THREAD_SAFE,
                    "#ThreadSafe# if the objects and comparator are thread-safe.",
                    "org/apache/commons/lang3/Range.java:29"),

            new Subject("staticBucketMap_putAndGet", COLLECTIONS4,
                    "org.apache.commons.collections4.map.StaticBucketMap", Contract.THREAD_SAFE,
                    "A StaticBucketMap is an efficient, thread-safe implementation of "
                            + "java.util.Map that performs well in a highly thread-contentious "
                            + "environment.",
                    "org/apache/commons/collections4/map/StaticBucketMap.java:32"),

            new Subject("commonsReferenceHashMap_putAndGet", COLLECTIONS4,
                    "org.apache.commons.collections4.map.ConcurrentReferenceHashMap", Contract.THREAD_SAFE,
                    "even though all operations are thread-safe, retrieval operations do not "
                            + "entail locking",
                    "org/apache/commons/collections4/map/ConcurrentReferenceHashMap.java:88"),

            new Subject("synchronizedCollection_addAndSize", COLLECTIONS4,
                    "org.apache.commons.collections4.collection.SynchronizedCollection", Contract.THREAD_SAFE,
                    "Decorates another Collection to synchronize its behavior for a "
                            + "multithreaded environment.",
                    "org/apache/commons/collections4/collection/SynchronizedCollection.java:26"),

            new Subject("synchronizedSortedBag_addAndCount", COLLECTIONS4,
                    "org.apache.commons.collections4.bag.SynchronizedSortedBag", Contract.THREAD_SAFE,
                    "Decorates another SortedBag to synchronize its behavior for a multithreaded "
                            + "environment.",
                    "org/apache/commons/collections4/bag/SynchronizedSortedBag.java:25"),

            new Subject("synchronizedMultiSet_addAndCount", COLLECTIONS4,
                    "org.apache.commons.collections4.multiset.SynchronizedMultiSet", Contract.THREAD_SAFE,
                    "Decorates another MultiSet to synchronize its behavior for a multithreaded "
                            + "environment.",
                    "org/apache/commons/collections4/multiset/SynchronizedMultiSet.java:25"),

            new Subject("synchronizedQueue_addAndPoll", COLLECTIONS4,
                    "org.apache.commons.collections4.queue.SynchronizedQueue", Contract.THREAD_SAFE,
                    "Decorates another Queue to synchronize its behavior for a multithreaded "
                            + "environment.",
                    "org/apache/commons/collections4/queue/SynchronizedQueue.java:24"),

            new Subject("strongInterner_intern", GUAVA,
                    "com.google.common.collect.Interners", Contract.THREAD_SAFE,
                    "Returns a new thread-safe interner which retains a strong reference to each "
                            + "instance it has interned.",
                    "com/google/common/collect/Interners.java:99"),

            new Subject("weakInterner_intern", GUAVA,
                    "com.google.common.collect.Interners", Contract.THREAD_SAFE,
                    "Returns a new thread-safe interner which retains a weak reference to each "
                            + "instance it has interned.",
                    "com/google/common/collect/Interners.java:108"),

            new Subject("guavaSynchronizedQueue_addAndPoll", GUAVA,
                    "com.google.common.collect.Queues", Contract.THREAD_SAFE,
                    "Returns a synchronized (thread-safe) queue backed by the specified queue.",
                    "com/google/common/collect/Queues.java:428"),

            new Subject("guavaSynchronizedDeque_addAndPoll", GUAVA,
                    "com.google.common.collect.Queues", Contract.THREAD_SAFE,
                    "Returns a synchronized (thread-safe) deque backed by the specified deque.",
                    "com/google/common/collect/Queues.java:462"),

            new Subject("synchronizedTable_putAndGet", GUAVA,
                    "com.google.common.collect.Tables", Contract.THREAD_SAFE,
                    "Returns a synchronized (thread-safe) table backed by the specified table.",
                    "com/google/common/collect/Tables.java:671"),

            new Subject("concurrentHashSet_addAndContains", GUAVA,
                    "com.google.common.collect.Sets", Contract.THREAD_SAFE,
                    "Creates a thread-safe set backed by a hash map. The set is backed by a "
                            + "ConcurrentHashMap instance, and thus carries the same concurrency "
                            + "guarantees.",
                    "com/google/common/collect/Sets.java:271"),

            new Subject("hashFunction_hashString", GUAVA,
                    "com.google.common.hash.HashFunction", Contract.THREAD_SAFE,
                    "stateless, and therefore thread-safe.",
                    "com/google/common/hash/HashFunction.java:43"),

            new Subject("mapMakerMap_putAndGet", GUAVA,
                    "com.google.common.collect.MapMaker", Contract.THREAD_SAFE,
                    "Builds a thread-safe map.",
                    "com/google/common/collect/MapMaker.java:273"),

            new Subject("synchronizedSupplier_get", GUAVA,
                    "com.google.common.base.Suppliers", Contract.THREAD_SAFE,
                    "Returns a supplier whose get() method synchronizes on delegate before "
                            + "calling it, making it thread-safe.",
                    "com/google/common/base/Suppliers.java:390"),

            new Subject("guavaCache_getAndPut", GUAVA,
                    "com.google.common.cache.Cache", Contract.THREAD_SAFE,
                    "Implementations of this interface are expected to be thread-safe, and can be "
                            + "safely accessed by multiple concurrent threads.",
                    "com/google/common/cache/Cache.java:35"),

            new Subject("asyncCache_getAndJoin", CAFFEINE,
                    "com.github.benmanes.caffeine.cache.AsyncCache", Contract.THREAD_SAFE,
                    "Implementations of this interface are expected to be thread-safe and can be "
                            + "safely accessed by multiple concurrent threads.",
                    "com/github/benmanes/caffeine/cache/AsyncCache.java:35"),

            new Subject("asyncLoadingCache_getAndJoin", CAFFEINE,
                    "com.github.benmanes.caffeine.cache.AsyncLoadingCache", Contract.THREAD_SAFE,
                    "Implementations of this interface are expected to be thread-safe and can be "
                            + "safely accessed by multiple concurrent threads.",
                    "com/github/benmanes/caffeine/cache/AsyncLoadingCache.java:29"),

            new Subject("caffeineLoadingCache_get", CAFFEINE,
                    "com.github.benmanes.caffeine.cache.LoadingCache", Contract.THREAD_SAFE,
                    "Implementations of this interface are expected to be thread-safe and can be "
                            + "safely accessed by multiple concurrent threads.",
                    "com/github/benmanes/caffeine/cache/LoadingCache.java:31"),

            new Subject("unpooledByteBufAllocator_bufferAndRelease", NETTY,
                    "io.netty.buffer.ByteBufAllocator", Contract.THREAD_SAFE,
                    "Implementations are responsible to allocate buffers. Implementations of this "
                            + "interface are expected to be thread-safe.",
                    "io/netty/buffer/ByteBufAllocator.java:19"),

            new Subject("conversionService_convert", SPRING,
                    "org.springframework.core.convert.ConversionService", Contract.THREAD_SAFE,
                    "Call convert(Object, Class) to perform a thread-safe type conversion using "
                            + "this system.",
                    "org/springframework/core/convert/ConversionService.java:23"),

            // --- Sixth wave: the safe side widened from 60 to 100, and the unsafe side from 22
            // --- to 39. A zero over 60 bounds the false-positive rate at 5.0% at 95%; over 100,
            // --- at 3.0%. The same bar as the fifth wave: shared mutable state behind a real
            // --- mechanism, never a stateless utility.
            // ---
            // --- The list was fixed before the first run, from a survey of all eight libraries
            // --- and the JDK, and no row was added or dropped after seeing what it drew. A safe
            // --- subject kept for having stayed quiet would shrink the bound without making the
            // --- claim any stronger.
            // ---
            // --- Documented-safe, guava: synchronized decorators over structures the corpus
            // --- already shares unguarded, atomics, and cache and registry state the fourth
            // --- wave's rows never reached.

            new Subject("synchronizedBiMap_forcePutAndInverse", GUAVA,
                    "com.google.common.collect.Maps", Contract.THREAD_SAFE,
                    "Returns a synchronized (thread-safe) bimap backed by the specified bimap.",
                    "com/google/common/collect/Maps.java:1652"),

            new Subject("guavaSynchronizedNavigableMap_putAndPollFirst", GUAVA,
                    "com.google.common.collect.Maps", Contract.THREAD_SAFE,
                    "Returns a synchronized (thread-safe) navigable map backed by the specified "
                            + "navigable map.",
                    "com/google/common/collect/Maps.java:3644"),

            new Subject("guavaSynchronizedNavigableSet_addAndPollFirst", GUAVA,
                    "com.google.common.collect.Sets", Contract.THREAD_SAFE,
                    "Returns a synchronized (thread-safe) navigable set backed by the specified "
                            + "navigable set.",
                    "com/google/common/collect/Sets.java:1976"),

            new Subject("synchronizedSetMultimap_putAndRemove", GUAVA,
                    "com.google.common.collect.Multimaps", Contract.THREAD_SAFE,
                    "Returns a synchronized (thread-safe) SetMultimap backed by the specified "
                            + "multimap.",
                    "com/google/common/collect/Multimaps.java:895"),

            new Subject("synchronizedListMultimap_putAndRemove", GUAVA,
                    "com.google.common.collect.Multimaps", Contract.THREAD_SAFE,
                    "Returns a synchronized (thread-safe) ListMultimap backed by the specified "
                            + "multimap.",
                    "com/google/common/collect/Multimaps.java:981"),

            new Subject("expiringMemoizedSupplier_get", GUAVA,
                    "com.google.common.base.Suppliers", Contract.THREAD_SAFE,
                    "The returned supplier is thread-safe.",
                    "com/google/common/base/Suppliers.java:227"),

            new Subject("atomicDouble_addAndCompareAndSet", GUAVA,
                    "com.google.common.util.concurrent.AtomicDouble", Contract.THREAD_SAFE,
                    "A double value that may be updated atomically.",
                    "com/google/common/util/concurrent/AtomicDouble.java:33"),

            new Subject("atomicDoubleArray_addAndCompareAndSet", GUAVA,
                    "com.google.common.util.concurrent.AtomicDoubleArray", Contract.THREAD_SAFE,
                    "A double array in which elements may be updated atomically.",
                    "com/google/common/util/concurrent/AtomicDoubleArray.java:33"),

            new Subject("simpleStatsCounter_recordAndSnapshot", GUAVA,
                    "com.google.common.cache.AbstractCache.SimpleStatsCounter", Contract.THREAD_SAFE,
                    "A thread-safe StatsCounter implementation for use by Cache implementors.",
                    "com/google/common/cache/AbstractCache.java:205"),

            new Subject("guavaCacheAsMap_merge", GUAVA,
                    "com.google.common.cache.Cache", Contract.THREAD_SAFE,
                    "Returns a view of the entries stored in this cache as a thread-safe map.",
                    "com/google/common/cache/Cache.java:169"),

            new Subject("eventBus_registerAndUnregister", GUAVA,
                    "com.google.common.eventbus.EventBus", Contract.THREAD_SAFE,
                    "This class is safe for concurrent use.",
                    "com/google/common/eventbus/EventBus.java:145"),

            // --- Documented-safe, commons: lang3's concurrent package, whose classes own
            // --- executors and timers, and its lock visitors, which guard with j.u.c. locks
            // --- rather than monitors.

            new Subject("timedSemaphore_tryAcquire", LANG3,
                    "org.apache.commons.lang3.concurrent.TimedSemaphore", Contract.THREAD_SAFE,
                    "Provides support classes for multi-threaded programming. ... These classes "
                            + "are thread-safe.",
                    "org/apache/commons/lang3/concurrent/package-info.java:20"),

            new Subject("backgroundInitializer_startAndGet", LANG3,
                    "org.apache.commons.lang3.concurrent.BackgroundInitializer", Contract.THREAD_SAFE,
                    "Provides support classes for multi-threaded programming. ... These classes "
                            + "are thread-safe.",
                    "org/apache/commons/lang3/concurrent/package-info.java:20"),

            new Subject("basicThreadFactory_newThread", LANG3,
                    "org.apache.commons.lang3.concurrent.BasicThreadFactory", Contract.THREAD_SAFE,
                    "Provides support classes for multi-threaded programming. ... These classes "
                            + "are thread-safe.",
                    "org/apache/commons/lang3/concurrent/package-info.java:20"),

            new Subject("readWriteLockVisitor_writeAndRead", LANG3,
                    "org.apache.commons.lang3.concurrent.locks.LockingVisitors", Contract.THREAD_SAFE,
                    "Locking may be preferable to synchronization or when an application needs a "
                            + "distinction between read access (multiple threads may have read "
                            + "access concurrently) and write access (only one thread may have "
                            + "write access at any given time).",
                    "org/apache/commons/lang3/concurrent/locks/LockingVisitors.java:36"),

            new Subject("stampedLockVisitor_writeAndRead", LANG3,
                    "org.apache.commons.lang3.concurrent.locks.LockingVisitors", Contract.THREAD_SAFE,
                    "Locking may be preferable to synchronization or when an application needs a "
                            + "distinction between read access (multiple threads may have read "
                            + "access concurrently) and write access (only one thread may have "
                            + "write access at any given time).",
                    "org/apache/commons/lang3/concurrent/locks/LockingVisitors.java:36"),

            new Subject("synchronizedCircularFifoQueue_addAndPoll", COLLECTIONS4,
                    "org.apache.commons.collections4.QueueUtils", Contract.THREAD_SAFE,
                    "Returns a synchronized (thread-safe) queue backed by the given queue.",
                    "org/apache/commons/collections4/QueueUtils.java:71"),

            // --- Documented-safe, JDK: queues and deques behind locks and CAS, the synchronized
            // --- decorators the fifth wave did not take, and the two RNGs whose javadoc promises
            // --- concurrent use. Queue bodies offer before they poll and stay far below
            // --- capacity, so BlockingQueueDetector's thresholds are not what is being tested.

            new Subject("concurrentLinkedDeque_offerFirstAndPollLast", JDK,
                    "java.util.concurrent.ConcurrentLinkedDeque", Contract.THREAD_SAFE,
                    "Concurrent insertion, removal, and access operations execute safely across "
                            + "multiple threads.",
                    "java.base/java/util/concurrent/ConcurrentLinkedDeque.java"),

            new Subject("linkedBlockingDeque_offerAndPollLast", JDK,
                    "java.util.concurrent.BlockingDeque", Contract.THREAD_SAFE,
                    "Like any BlockingQueue, a BlockingDeque is thread safe, does not permit null "
                            + "elements, and may (or may not) be capacity-constrained.",
                    "java.base/java/util/concurrent/BlockingDeque.java"),

            new Subject("arrayBlockingQueue_offerAndPoll", JDK,
                    "java.util.concurrent.BlockingQueue", Contract.THREAD_SAFE,
                    "BlockingQueue implementations are thread-safe. All queuing methods achieve "
                            + "their effects atomically using internal locks or other forms of "
                            + "concurrency control.",
                    "java.base/java/util/concurrent/BlockingQueue.java"),

            new Subject("priorityBlockingQueue_offerAndPoll", JDK,
                    "java.util.concurrent.BlockingQueue", Contract.THREAD_SAFE,
                    "BlockingQueue implementations are thread-safe. All queuing methods achieve "
                            + "their effects atomically using internal locks or other forms of "
                            + "concurrency control.",
                    "java.base/java/util/concurrent/BlockingQueue.java"),

            new Subject("linkedTransferQueue_offerAndPoll", JDK,
                    "java.util.concurrent.BlockingQueue", Contract.THREAD_SAFE,
                    "BlockingQueue implementations are thread-safe. All queuing methods achieve "
                            + "their effects atomically using internal locks or other forms of "
                            + "concurrency control.",
                    "java.base/java/util/concurrent/BlockingQueue.java"),

            new Subject("concurrentSkipListSet_addAndContains", JDK,
                    "java.util.concurrent.ConcurrentSkipListSet", Contract.THREAD_SAFE,
                    "Insertion, removal, and access operations safely execute concurrently by "
                            + "multiple threads.",
                    "java.base/java/util/concurrent/ConcurrentSkipListSet.java"),

            new Subject("copyOnWriteArraySet_addAndIterate", JDK,
                    "java.util.concurrent.CopyOnWriteArraySet", Contract.THREAD_SAFE,
                    "A Set that uses an internal CopyOnWriteArrayList for all of its operations. "
                            + "... It is thread-safe.",
                    "java.base/java/util/concurrent/CopyOnWriteArraySet.java"),

            new Subject("vector_addAndGet", JDK,
                    "java.util.Vector", Contract.THREAD_SAFE,
                    "Unlike the new collection implementations, Vector is synchronized.",
                    "java.base/java/util/Vector.java"),

            new Subject("synchronizedMap_putAndGet", JDK,
                    "java.util.Collections", Contract.THREAD_SAFE,
                    "Returns a synchronized (thread-safe) map backed by the specified map.",
                    "java.base/java/util/Collections.java"),

            new Subject("synchronizedNavigableMap_putAndCeilingKey", JDK,
                    "java.util.Collections", Contract.THREAD_SAFE,
                    "Returns a synchronized (thread-safe) navigable map backed by the specified "
                            + "navigable map.",
                    "java.base/java/util/Collections.java"),

            new Subject("synchronizedSet_addAndContains", JDK,
                    "java.util.Collections", Contract.THREAD_SAFE,
                    "Returns a synchronized (thread-safe) set backed by the specified set.",
                    "java.base/java/util/Collections.java"),

            new Subject("random_nextInt", JDK,
                    "java.util.Random", Contract.THREAD_SAFE,
                    "Instances of java.util.Random are threadsafe.",
                    "java.base/java/util/Random.java"),

            new Subject("secureRandom_nextBytes", JDK,
                    "java.security.SecureRandom", Contract.THREAD_SAFE,
                    "SecureRandom objects are safe for use by multiple concurrent threads.",
                    "java.base/java/security/SecureRandom.java"),

            new Subject("properties_setPropertyAndGetProperty", JDK,
                    "java.util.Properties", Contract.THREAD_SAFE,
                    "This class is thread-safe: multiple threads can share a single Properties "
                            + "object without the need for external synchronization.",
                    "java.base/java/util/Properties.java"),

            new Subject("atomicLong_incrementAndGet", JDK,
                    "java.util.concurrent.atomic.AtomicLong", Contract.THREAD_SAFE,
                    "A long value that may be updated atomically.",
                    "java.base/java/util/concurrent/atomic/AtomicLong.java"),

            new Subject("atomicReference_updateAndGet", JDK,
                    "java.util.concurrent.atomic.AtomicReference", Contract.THREAD_SAFE,
                    "An object reference that may be updated atomically.",
                    "java.base/java/util/concurrent/atomic/AtomicReference.java"),

            // --- Documented-safe, jackson, caffeine and netty: eviction, interning and
            // --- allocation state the fourth and fifth waves' single-key bodies never reach.

            new Subject("jacksonLruMap_putAndEvict", JACKSON,
                    "com.fasterxml.jackson.databind.util.LRUMap", Contract.THREAD_SAFE,
                    "Is thread-safe and does NOT require external synchronization",
                    "com/fasterxml/jackson/databind/util/LRUMap.java:22"),

            new Subject("caffeineStrongInterner_intern", CAFFEINE,
                    "com.github.benmanes.caffeine.cache.Interner", Contract.THREAD_SAFE,
                    "Returns a new thread-safe interner that retains a strong reference to each "
                            + "instance it has interned, thus preventing these instances from "
                            + "being garbage-collected.",
                    "com/github/benmanes/caffeine/cache/Interner.java:61"),

            new Subject("caffeineWeakInterner_intern", CAFFEINE,
                    "com.github.benmanes.caffeine.cache.Interner", Contract.THREAD_SAFE,
                    "Returns a new thread-safe interner that retains a weak reference to each "
                            + "instance it has interned, and so does not prevent these instances "
                            + "from being garbage-collected.",
                    "com/github/benmanes/caffeine/cache/Interner.java:72"),

            new Subject("caffeineBoundedCache_evictUnderPressure", CAFFEINE,
                    "com.github.benmanes.caffeine.cache.Cache", Contract.THREAD_SAFE,
                    "Implementations of this interface are expected to be thread-safe and can be "
                            + "safely accessed by multiple concurrent threads.",
                    "com/github/benmanes/caffeine/cache/Cache.java:34"),

            new Subject("asyncCacheSynchronous_asMapMerge", CAFFEINE,
                    "com.github.benmanes.caffeine.cache.AsyncCache", Contract.THREAD_SAFE,
                    "a thread-safe synchronous view of this cache",
                    "com/github/benmanes/caffeine/cache/AsyncCache.java:208"),

            new Subject("concurrentStatsCounter_recordAndSnapshot", CAFFEINE,
                    "com.github.benmanes.caffeine.cache.stats.ConcurrentStatsCounter",
                    Contract.THREAD_SAFE,
                    "A thread-safe StatsCounter implementation for use by Cache implementors.",
                    "com/github/benmanes/caffeine/cache/stats/ConcurrentStatsCounter.java:28"),

            new Subject("adaptiveByteBufAllocator_bufferAndRelease", NETTY,
                    "io.netty.buffer.ByteBufAllocator", Contract.THREAD_SAFE,
                    "Implementations are responsible to allocate buffers. Implementations of this "
                            + "interface are expected to be thread-safe.",
                    "io/netty/buffer/ByteBufAllocator.java:19"),

            // --- Documented NOT thread-safe, JDK. Six of these are the first lane-one subjects
            // --- whose misuse an agent-fed detector other than AtomicityValidator and
            // --- SharedCollectionDetector models: StringBuilder, SimpleDateFormat (directly and
            // --- through DateFormat), Matcher, DecimalFormat and Formatter.

            new Subject("stringBuilder_appendAndLength", JDK,
                    "java.lang.StringBuilder", Contract.NOT_THREAD_SAFE,
                    "Instances of StringBuilder are not safe for use by multiple threads.",
                    "java.base/java/lang/StringBuilder.java"),

            new Subject("simpleDateFormat_format", JDK,
                    "java.text.SimpleDateFormat", Contract.NOT_THREAD_SAFE,
                    "Date formats are not synchronized. It is recommended to create separate "
                            + "format instances for each thread. If multiple threads access a "
                            + "format concurrently, it must be synchronized externally.",
                    "java.base/java/text/SimpleDateFormat.java"),

            new Subject("dateFormat_formatThroughSupertype", JDK,
                    "java.text.DateFormat", Contract.NOT_THREAD_SAFE,
                    "Date formats are not synchronized. It is recommended to create separate "
                            + "format instances for each thread. If multiple threads access a "
                            + "format concurrently, it must be synchronized externally.",
                    "java.base/java/text/DateFormat.java"),

            new Subject("matcher_resetFindAndGroup", JDK,
                    "java.util.regex.Matcher", Contract.NOT_THREAD_SAFE,
                    "Instances of this class are not safe for use by multiple concurrent threads.",
                    "java.base/java/util/regex/Matcher.java"),

            new Subject("decimalFormat_format", JDK,
                    "java.text.DecimalFormat", Contract.NOT_THREAD_SAFE,
                    "Decimal formats are generally not synchronized. It is recommended to create "
                            + "separate format instances for each thread. If multiple threads "
                            + "access a format concurrently, it must be synchronized externally.",
                    "java.base/java/text/DecimalFormat.java"),

            new Subject("formatter_format", JDK,
                    "java.util.Formatter", Contract.NOT_THREAD_SAFE,
                    "Formatters are not necessarily safe for multithreaded access. Thread safety "
                            + "is optional and is the responsibility of users of methods in this "
                            + "class.",
                    "java.base/java/util/Formatter.java"),

            new Subject("arrayDeque_offerAndPoll", JDK,
                    "java.util.ArrayDeque", Contract.NOT_THREAD_SAFE,
                    "They are not thread-safe; in the absence of external synchronization, they "
                            + "do not support concurrent access by multiple threads.",
                    "java.base/java/util/ArrayDeque.java"),

            new Subject("priorityQueue_offerAndPoll", JDK,
                    "java.util.PriorityQueue", Contract.NOT_THREAD_SAFE,
                    "Note that this implementation is not synchronized. Multiple threads should "
                            + "not access a PriorityQueue instance concurrently if any of the "
                            + "threads modifies the queue.",
                    "java.base/java/util/PriorityQueue.java"),

            // --- Documented NOT thread-safe, libraries: sorted and linked multimaps and tables,
            // --- builders, a stopwatch and a token buffer, each an unguarded shape the corpus
            // --- did not yet hold.

            new Subject("treeMultimap_putAndRemove", GUAVA,
                    "com.google.common.collect.TreeMultimap", Contract.NOT_THREAD_SAFE,
                    "This class is not threadsafe when any concurrent operations update the "
                            + "multimap.",
                    "com/google/common/collect/TreeMultimap.java:64"),

            new Subject("linkedHashMultimap_putAndRemove", GUAVA,
                    "com.google.common.collect.LinkedHashMultimap", Contract.NOT_THREAD_SAFE,
                    "This class is not threadsafe when any concurrent operations update the "
                            + "multimap.",
                    "com/google/common/collect/LinkedHashMultimap.java:70"),

            new Subject("treeBasedTable_putAndRemove", GUAVA,
                    "com.google.common.collect.TreeBasedTable", Contract.NOT_THREAD_SAFE,
                    "Note that this implementation is not synchronized. If multiple threads "
                            + "access this table concurrently and one of the threads modifies the "
                            + "table, it must be synchronized externally.",
                    "com/google/common/collect/TreeBasedTable.java:63"),

            new Subject("pairedStatsAccumulator_add", GUAVA,
                    "com.google.common.math.PairedStatsAccumulator", Contract.NOT_THREAD_SAFE,
                    "This class is not thread safe.",
                    "com/google/common/math/PairedStatsAccumulator.java:28"),

            new Subject("hashSetValuedHashMap_put", COLLECTIONS4,
                    "org.apache.commons.collections4.multimap.HashSetValuedHashMap",
                    Contract.NOT_THREAD_SAFE,
                    "Note that HashSetValuedHashMap is not synchronized and is not thread-safe.",
                    "org/apache/commons/collections4/multimap/HashSetValuedHashMap.java:34"),

            new Subject("hashCodeBuilder_appendAndHash", LANG3,
                    "org.apache.commons.lang3.builder.HashCodeBuilder", Contract.NOT_THREAD_SAFE,
                    "These classes are not thread-safe.",
                    "org/apache/commons/lang3/builder/package-info.java:20"),

            new Subject("springStopWatch_startStop", SPRING,
                    "org.springframework.util.StopWatch", Contract.NOT_THREAD_SAFE,
                    "Note that this object is not designed to be thread-safe and does not use "
                            + "synchronization.",
                    "org/springframework/util/StopWatch.java:34"),

            new Subject("linkedMultiValueMap_addAndRemove", SPRING,
                    "org.springframework.util.LinkedMultiValueMap", Contract.NOT_THREAD_SAFE,
                    "This Map implementation is generally not thread-safe. It is primarily "
                            + "designed for data structures exposed from request objects, for use "
                            + "in a single thread only.",
                    "org/springframework/util/LinkedMultiValueMap.java:29"),

            new Subject("tokenBuffer_writeNumber", JACKSON,
                    "com.fasterxml.jackson.databind.util.TokenBuffer", Contract.NOT_THREAD_SAFE,
                    "Note: instances are not synchronized, that is, they are not thread-safe if "
                            + "there are concurrent appends to the underlying buffer.",
                    "com/fasterxml/jackson/databind/util/TokenBuffer.java:243")
    );

    /**
     * The agent-pair lane's subjects: JDK types, with bodies that record nothing.
     *
     * <p>These detectors are fed by a call site the agent substitutes, so there is no
     * {@code record*} call that could stand in for the feed and no way to reach them from the
     * recording lane. Each pair is therefore written the way the bug is actually written: one
     * instance in a static field that every thread calls, against one instance per thread. The
     * two bodies are otherwise the same code, which is what makes the silent half evidence -
     * every substitution the firing half goes through, the confined half goes through too, and
     * the detector still has to tell them apart.
     */
    private static final List<RecordingSubject> AGENT_SUBJECTS = List.of(

            // --- The shared-instance family. Seven JDK types that keep mutable state across a
            //     call, are documented as unsafe to share, and are expensive enough to build that
            //     caching one in a field is the normal thing to do. That is the whole bug: the
            //     field outlives the confinement its author assumed.

            new RecordingSubject("agent_simpleDateFormat_oneInstanceForEveryThread", JDK,
                    "java.text.SimpleDateFormat",
                    DetectorType.SIMPLE_DATE_FORMAT, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "format() writes into the instance's own Calendar before reading it back, so "
                            + "two threads in one instance interleave a write with a read. The "
                            + "class javadoc says to synchronize or give each thread its own",
                    IssueSeverity.HIGH),

            new RecordingSubject("agent_simpleDateFormat_oneInstancePerThread", JDK,
                    "java.text.SimpleDateFormat",
                    DetectorType.SIMPLE_DATE_FORMAT, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the ThreadLocal supplier is the fix the javadoc names. The call site the "
                            + "agent substitutes is the same one, so a finding here would mean "
                            + "the detector reports the type rather than the sharing"),

            new RecordingSubject("agent_matcher_oneInstanceForEveryThread", JDK,
                    "java.util.regex.Matcher",
                    DetectorType.SHARED_MATCHER, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "a Matcher carries the append position and the group bounds of the last "
                            + "match, so find() on a shared one leaves group() reading another "
                            + "thread's result. Pattern is thread-safe and Matcher is not",
                    IssueSeverity.HIGH),

            new RecordingSubject("agent_matcher_oneInstancePerThread", JDK,
                    "java.util.regex.Matcher",
                    DetectorType.SHARED_MATCHER, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "each thread calls matcher() on the shared Pattern, which is the documented "
                            + "way to use one. Sharing the Pattern and not the Matcher must read "
                            + "as correct or the detector is flagging the regex package"),

            new RecordingSubject("agent_messageDigest_oneInstanceForEveryThread", JDK,
                    "java.security.MessageDigest",
                    DetectorType.SHARED_MESSAGE_DIGEST, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "update() accumulates into the instance and digest() drains it, so two "
                            + "threads sharing one produce a hash over an interleaving of both "
                            + "inputs. This one is silent in production: the digest is wrong, "
                            + "not absent",
                    IssueSeverity.HIGH),

            new RecordingSubject("agent_messageDigest_oneInstancePerThread", JDK,
                    "java.security.MessageDigest",
                    DetectorType.SHARED_MESSAGE_DIGEST, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "getInstance() per thread is the documented pattern and the JCA is built "
                            + "for it. The threads still all call update() and digest(), so the "
                            + "substituted call sites see the same traffic as the firing row"),

            new RecordingSubject("agent_calendar_oneInstanceForEveryThread", JDK,
                    "java.util.Calendar",
                    DetectorType.CALENDAR, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "get() computes the whole field set from the instance's time on first call "
                            + "and caches it, so a set() from another thread invalidates a read "
                            + "already in flight. Calendar's own javadoc states no thread-safety "
                            + "contract, so the ground truth here is that field cache, not a "
                            + "quoted sentence",
                    IssueSeverity.HIGH),

            new RecordingSubject("agent_calendar_oneInstancePerThread", JDK,
                    "java.util.Calendar",
                    DetectorType.CALENDAR, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "getInstance() returns a fresh Calendar, so the confined body does the same "
                            + "get/set traffic against state no other thread can see"),

            new RecordingSubject("agent_stringBuilder_oneInstanceForEveryThread", JDK,
                    "java.lang.StringBuilder",
                    DetectorType.STRING_BUILDER, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "append() reads count, writes the array and then writes count back, "
                            + "unsynchronized by design - StringBuffer exists because "
                            + "StringBuilder dropped the locking. A shared one loses appends or "
                            + "throws from the array copy",
                    IssueSeverity.HIGH),

            new RecordingSubject("agent_stringBuilder_oneInstancePerThread", JDK,
                    "java.lang.StringBuilder",
                    DetectorType.STRING_BUILDER, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "a StringBuilder local to the body is the overwhelmingly common use, and the "
                            + "one the compiler itself emits for string concatenation. A finding "
                            + "here would fire on most Java ever written"),

            new RecordingSubject("agent_decimalFormat_oneInstanceForEveryThread", JDK,
                    "java.text.DecimalFormat",
                    DetectorType.SHARED_DECIMAL_FORMAT, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "DecimalFormat inherits NumberFormat's mutable digit list and formats "
                            + "through it, so a shared instance interleaves two numbers into one "
                            + "buffer. NumberFormat's javadoc states formats are not "
                            + "synchronized",
                    IssueSeverity.HIGH),

            new RecordingSubject("agent_decimalFormat_oneInstancePerThread", JDK,
                    "java.text.DecimalFormat",
                    DetectorType.SHARED_DECIMAL_FORMAT, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the ThreadLocal twin is the documented remedy, and the pattern string is "
                            + "identical, so nothing but the sharing separates the two rows"),

            new RecordingSubject("agent_formatter_oneInstanceForEveryThread", JDK,
                    "java.util.Formatter",
                    DetectorType.SHARED_FORMATTER, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "a Formatter appends into the Appendable it was constructed over and keeps "
                            + "the last IOException, so sharing one interleaves output as well "
                            + "as error state. Its javadoc requires external synchronization",
                    IssueSeverity.HIGH),

            new RecordingSubject("agent_formatter_oneInstancePerThread", JDK,
                    "java.util.Formatter",
                    DetectorType.SHARED_FORMATTER, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "each thread formats through its own Formatter over its own StringBuilder, "
                            + "which is what String.format does internally on every call"),

            // --- The coordination and lock families. These types are thread-safe and sharing
            //     them is the point, so nothing here turns on which object is the receiver. What
            //     the detector reports is protocol: a permit that never came back, an await whose
            //     false return was discarded, two locks nested both ways round. The correct twin
            //     is the same protocol, completed.

            new RecordingSubject("agent_semaphore_permitNeverReturned", JDK,
                    "java.util.concurrent.Semaphore",
                    DetectorType.SEMAPHORE, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "the body acquires and never releases, so acquireCount exceeds releaseCount. "
                            + "A leaked permit is the semaphore bug that does not announce "
                            + "itself: the pool just gets smaller until it is empty",
                    IssueSeverity.HIGH),

            new RecordingSubject("agent_semaphore_permitReturnedInFinally", JDK,
                    "java.util.concurrent.Semaphore",
                    DetectorType.SEMAPHORE, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "acquire-try-finally-release is the documented shape and leaves the two "
                            + "counts equal. Both rows go through the same substituted call "
                            + "sites, so a finding here would mean the detector counts calls "
                            + "rather than balance"),

            new RecordingSubject("agent_countDownLatch_awaitTimedOut", JDK,
                    "java.util.concurrent.CountDownLatch",
                    DetectorType.COUNTDOWN_LATCH, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "await(1, MILLISECONDS) on a latch nothing counts down must return false, and "
                            + "the discarded false is the finding. The count is one and no thread "
                            + "in the run can reach it, so the timeout is structural rather than "
                            + "a race the scheduler might win",
                    IssueSeverity.CRITICAL),

            new RecordingSubject("agent_countDownLatch_awaitSawItsCount", JDK,
                    "java.util.concurrent.CountDownLatch",
                    DetectorType.COUNTDOWN_LATCH, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same thread counts the latch down before awaiting it, so the timed await "
                            + "returns true with no timing assumption at all. Same await(long, "
                            + "TimeUnit) call site, opposite outcome"),

            // --- The two coordination detectors that were classified AGENT-fed and could not
            //     report there. Every record path on both resolved through a registry only the
            //     public register* methods populated, and no hook called one, so both iterated an
            //     empty map whatever the woven call sites delivered (#436). The hooks now register
            //     on first observation, inferring the latch's starting count and the queue's bound
            //     from the object itself, and these four rows are what says so: written as
            //     agent-lane rows, they cannot reach a register* call even if they wanted to.
            //
            //     Both subjects are created inside the body, like the semaphore above. Each body
            //     execution therefore gets its own, so a row's arithmetic is over its own calls
            //     and cannot depend on how the 240 executions interleaved.

            new RecordingSubject("agent_latchMisuse_countedDownPastItsCount", JDK,
                    "java.util.concurrent.CountDownLatch",
                    DetectorType.LATCH_MISUSE, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "a latch of one counted down twice is the extraCountDowns condition exactly. "
                            + "Nothing declares the count here: it has to be read off getCount() "
                            + "before the first countDown, which is the inference this row "
                            + "measures. A latch counted past zero released waiters its author "
                            + "believed were still gated",
                    IssueSeverity.CRITICAL),

            new RecordingSubject("agent_latchMisuse_countedDownExactly", JDK,
                    "java.util.concurrent.CountDownLatch",
                    DetectorType.LATCH_MISUSE, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same latch and the same two call sites, counted down once. Neither "
                            + "condition holds: one is not above one, and one is not below one. "
                            + "An inferred count that came out too small would fire here, which "
                            + "is what makes this row the evidence and not the ceremony"),

            new RecordingSubject("agent_blockingQueue_filledToCapacity", JDK,
                    "java.util.concurrent.ArrayBlockingQueue",
                    DetectorType.BLOCKING_QUEUE, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "a queue of two put and offered to before anything is polled peaks at two of "
                            + "two, which is the 90% saturation threshold. The bound is nowhere in "
                            + "the body: remainingCapacity() + size() is where it comes from. One "
                            + "thread's own queue, so the peak is arithmetic rather than a race",
                    IssueSeverity.MEDIUM),

            new RecordingSubject("agent_blockingQueue_drainedAsItFilled", JDK,
                    "java.util.concurrent.ArrayBlockingQueue",
                    DetectorType.BLOCKING_QUEUE, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same three call sites on the same bound, with a poll between the put and "
                            + "the offer, so the peak never leaves one. A queue that keeps up with "
                            + "its producer is the ordinary case and must not read as saturated"),

            new RecordingSubject("agent_blockingQueue_rejectionDiscarded", JDK,
                    "java.util.concurrent.ArrayBlockingQueue",
                    DetectorType.BLOCKING_QUEUE, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "two puts fill a queue of two, then an offer as a statement: the false it "
                            + "returns is popped before anything can read it, and that element "
                            + "is gone with nothing in the program knowing. The weaver reads the "
                            + "POP after the call and hands the popped boolean to the detector "
                            + "(#454); the finding is the drop, not the rejection",
                    IssueSeverity.MEDIUM),

            new RecordingSubject("agent_blockingQueue_discardedOfferAccepted", JDK,
                    "java.util.concurrent.ArrayBlockingQueue",
                    DetectorType.BLOCKING_QUEUE, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same puts and the same offer-as-a-statement, with a poll after each put, "
                            + "so the offer is accepted and the popped boolean is true. Offering "
                            + "into a queue with room and not looking is the commonest shape in "
                            + "production; a lookahead that reported every popped result would "
                            + "fire here, and the peak never leaves one so saturation cannot "
                            + "carry it either"),

            new RecordingSubject("agent_sleep_whileHoldingTheMonitor", JDK,
                    "java.lang.Thread",
                    DetectorType.SLEEP_IN_LOCK, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "sleeping inside a synchronized method holds the monitor for the whole "
                            + "duration, so every other thread waits on a lock whose holder is "
                            + "doing nothing. Whether a sleep is a bug depends entirely on that, "
                            + "which is why the substitution carries the monitor with it",
                    IssueSeverity.MEDIUM),

            new RecordingSubject("agent_sleep_holdingNothing", JDK,
                    "java.lang.Thread",
                    DetectorType.SLEEP_IN_LOCK, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same Thread.sleep(1) with no monitor held. A sleep is not a finding, and "
                            + "a detector that reported this one would fire on every backoff loop "
                            + "and every poll interval ever written"),

            new RecordingSubject("agent_jctoolsHandOff_offererWritesAfterTheOffer", NETTY,
                    "io.netty.util.internal.shaded.org.jctools.queues.MpscArrayQueue",
                    DetectorType.ATOMICITY_VIOLATIONS, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "the real JCTools MpscArrayQueue, netty's shaded copy, handing an object from "
                            + "its offerer to whoever polls it. The offerer writes to it again "
                            + "after the offer, in the generation the taker now owns, so two "
                            + "threads write one field with nothing in common. The woven "
                            + "MessagePassingQueue.offer and poll are what name the two owners "
                            + "(#664, #692)",
                    IssueSeverity.HIGH),

            new RecordingSubject("agent_jctoolsHandOff_offererLetsGo", NETTY,
                    "io.netty.util.internal.shaded.org.jctools.queues.MpscArrayQueue",
                    DetectorType.ATOMICITY_VIOLATIONS, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same offer and the same poll, and the offerer does not touch the object "
                            + "again. Two threads still write the one field, the offerer before "
                            + "the offer and the taker after the poll, which is a hand-off"),

            new RecordingSubject("agent_wait_behindAnIf", JDK,
                    "java.lang.Object",
                    DetectorType.MISSED_SIGNAL, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "every thread signals and then waits behind an if. The first notifyAll of a "
                            + "round finds nobody waiting and the last wait of the round receives "
                            + "none, so it needed a signal that was already gone. Both calls are "
                            + "seen by the woven hooks under the monitor they hold (#694)",
                    IssueSeverity.CRITICAL),

            new RecordingSubject("agent_wait_insideAPredicateLoop", JDK,
                    "java.lang.Object",
                    DetectorType.MISSED_SIGNAL, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same notifyAll and the same timed wait, inside while (!handedOff). The "
                            + "wait runs out after a lost notify exactly as its twin's does; the "
                            + "backward jump around it is what the weaver marks, and a loop "
                            + "re-tests the state a lost notify announced"),

            new RecordingSubject("agent_sleepStamped_whileHoldingTheWriteStamp", JDK,
                    "java.util.concurrent.locks.StampedLock",
                    DetectorType.SLEEP_IN_LOCK, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "a one-millisecond sleep with a write stamp held, so every reader and writer "
                            + "of the lock waits on a thread doing nothing. StampedLock records no "
                            + "owner; the finding rests on the thread's own lockset entry and the "
                            + "lock being write-locked, which is the shape #543 made reportable",
                    IssueSeverity.MEDIUM),

            new RecordingSubject("agent_sleepStamped_afterReleasingTheWriteStamp", JDK,
                    "java.util.concurrent.locks.StampedLock",
                    DetectorType.SLEEP_IN_LOCK, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same stamp and the same sleep, with the unlockWrite before the sleep. "
                            + "The lockset entry is gone and the lock is free, so a finding here "
                            + "would mean a released stamp still reads as held"),

            new RecordingSubject("agent_lockOrder_nestedBothWays", JDK,
                    "java.util.concurrent.locks.ReentrantLock",
                    DetectorType.LOCK_ORDER, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "the body nests B inside A and then A inside B, which puts both edges in the "
                            + "pooled graph and closes a two-cycle. That is the deadlock, "
                            + "recorded without having to suffer one",
                    IssueSeverity.CRITICAL),

            new RecordingSubject("agent_lockOrder_nestedOneWay", JDK,
                    "java.util.concurrent.locks.ReentrantLock",
                    DetectorType.LOCK_ORDER, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same two locks, always A then B. A consistent global order is the "
                            + "textbook remedy and yields the single edge A to B, so a finding "
                            + "here would mean nesting itself reads as a defect"),

            new RecordingSubject("agent_lock_acquiredAndNeverReleased", JDK,
                    "java.util.concurrent.locks.ReentrantLock",
                    DetectorType.LOCK_LEAKS, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "lock() with no unlock() leaves acquireCount above releaseCount and the lock "
                            + "held at analysis. This is the bug an early return or a thrown "
                            + "exception writes for you when the unlock is not in a finally",
                    IssueSeverity.CRITICAL),

            new RecordingSubject("agent_lock_releasedInFinally", JDK,
                    "java.util.concurrent.locks.ReentrantLock",
                    DetectorType.LOCK_LEAKS, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "lock-try-finally-unlock, which is what the ReentrantLock javadoc's own "
                            + "example shows. Balanced counts, and nothing held when the run ends"),

            new RecordingSubject("agent_tryLock_unlockedAfterFailing", JDK,
                    "java.util.concurrent.locks.StampedLock",
                    DetectorType.TRY_LOCK_MISUSE, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "the body unlocks after a tryLock that returned false, releasing a lock this "
                            + "call never took. StampedLock is not reentrant, so a write lock the "
                            + "thread already holds refuses its own tryLock every time - the "
                            + "failure is a property of the type, not of who else is running",
                    IssueSeverity.HIGH),

            new RecordingSubject("agent_tryLock_unlockedOnlyWhenAcquired", JDK,
                    "java.util.concurrent.locks.StampedLock",
                    DetectorType.TRY_LOCK_MISUSE, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same lock, unlock and tryLock call sites, with the unlock inside the "
                            + "branch the tryLock guards. That is the whole rule, and the row "
                            + "matters because the detector keys on the thread's last outcome for "
                            + "the lock and could convict a later honest unlock instead"),

            // --- Through library bytecode. The pairs above call the JDK type from the test, so
            //     the substituted call site is one this module wrote. These call a public method
            //     of Guava, Jackson or HikariCP, and the JDK call that feeds the detector is an
            //     instruction inside that library's own class file. The body is the same bug and
            //     the same fix; what changes is that nobody here compiled the call the agent had
            //     to find. LibraryReach counts what this reaches, and says why the rest cannot be.

            new RecordingSubject("agent_guavaHasher_oneHasherForEveryThread", GUAVA,
                    "com.google.common.hash.Hasher",
                    DetectorType.SHARED_MESSAGE_DIGEST, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "HashFunction.newHasher() is documented to return \"an initialized, stateful "
                            + "Hasher\", and the SHA-256 one keeps that state in a cloned "
                            + "MessageDigest. Every thread puts into the one hasher, so every "
                            + "thread's update lands on the same digest from inside Guava",
                    IssueSeverity.HIGH),

            new RecordingSubject("agent_guavaHasher_oneHasherPerHash", GUAVA,
                    "com.google.common.hash.Hasher",
                    DetectorType.SHARED_MESSAGE_DIGEST, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "a fresh hasher per hash, which clones a fresh digest. The same "
                            + "MessageDigestHasher.update call site is reached on every execution, "
                            + "so a finding here would mean the detector reports Guava's call "
                            + "site rather than a digest two threads touched"),

            new RecordingSubject("agent_jacksonStdDateFormat_oneFormatForEveryThread", JACKSON,
                    "com.fasterxml.jackson.databind.util.StdDateFormat",
                    DetectorType.CALENDAR, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "StdDateFormat caches a cloned Calendar in a field on first use and reads "
                            + "every date field back out of it; its own javadoc says the blueprint "
                            + "Calendar \"Cannot be used as is, due to thread-safety issues\". One "
                            + "shared instance puts every thread on that one Calendar",
                    IssueSeverity.HIGH),

            new RecordingSubject("agent_jacksonStdDateFormat_oneFormatPerCall", JACKSON,
                    "com.fasterxml.jackson.databind.util.StdDateFormat",
                    DetectorType.CALENDAR, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "an instance per call caches its own Calendar, which no other thread can "
                            + "reach. Same _format method, same Calendar.get call sites"),

            new RecordingSubject("agent_jacksonSignature_oneBuilderForEveryThread", JACKSON,
                    "com.fasterxml.jackson.databind.JavaType",
                    DetectorType.STRING_BUILDER, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "a resolved JavaType is shared freely - TypeFactory caches them - but the "
                            + "StringBuilder getGenericSignature appends into is the caller's, and "
                            + "every thread passes the same one. The appends are in "
                            + "TypeBase._classSignature, not in this module",
                    IssueSeverity.HIGH),

            new RecordingSubject("agent_jacksonSignature_oneBuilderPerCall", JACKSON,
                    "com.fasterxml.jackson.databind.JavaType",
                    DetectorType.STRING_BUILDER, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same shared JavaType and the same signature, into a builder the call "
                            + "made. Sharing the type is correct; only the builder was ever the bug"),

            // --- Through a wider static type (#542): these libraries hold the JDK object as a
            //     DateFormat, a NumberFormat they parse with, or an Appendable, which the weaver
            //     did not match until the wider entries existed.

            new RecordingSubject("agent_jacksonRfc1123Parse_oneFormatForEveryThread", JACKSON,
                    "com.fasterxml.jackson.databind.util.StdDateFormat",
                    DetectorType.SIMPLE_DATE_FORMAT, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "StdDateFormat parses RFC 1123 text with a SimpleDateFormat it clones into a "
                            + "field typed DateFormat, and its javadoc says the blueprint formats "
                            + "\"cannot be used as is, due to thread-safety issues\". One shared "
                            + "instance puts every thread in that one SimpleDateFormat's parse",
                    IssueSeverity.HIGH),

            new RecordingSubject("agent_jacksonRfc1123Parse_oneFormatPerCall", JACKSON,
                    "com.fasterxml.jackson.databind.util.StdDateFormat",
                    DetectorType.SIMPLE_DATE_FORMAT, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "an instance per call clones its own SimpleDateFormat. Same DateFormat.parse "
                            + "call site in Jackson, reached on every execution"),

            new RecordingSubject("agent_springParseNumber_oneFormatForEveryThread", SPRING,
                    "org.springframework.util.NumberUtils",
                    DetectorType.SHARED_DECIMAL_FORMAT, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "NumberUtils is a stateless utility, but parseNumber(String, Class, "
                            + "NumberFormat) parses with the format the caller hands it, and every "
                            + "thread hands it the same DecimalFormat. The parse is Spring's",
                    IssueSeverity.HIGH),

            new RecordingSubject("agent_springParseNumber_oneFormatPerCall", SPRING,
                    "org.springframework.util.NumberUtils",
                    DetectorType.SHARED_DECIMAL_FORMAT, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same Spring call with a DecimalFormat built for it, so no two threads "
                            + "parse with one instance"),

            new RecordingSubject("agent_guavaJoinerAppendTo_oneBuilderForEveryThread", GUAVA,
                    "com.google.common.base.Joiner",
                    DetectorType.STRING_BUILDER, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "Joiner documents itself as thread-safe and is, but appendTo(StringBuilder, "
                            + "Iterable) writes into the caller's builder through Appendable, and "
                            + "every thread passes the same one",
                    IssueSeverity.HIGH),

            new RecordingSubject("agent_guavaJoinerAppendTo_oneBuilderPerCall", GUAVA,
                    "com.google.common.base.Joiner",
                    DetectorType.STRING_BUILDER, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same shared Joiner and the same parts, into a builder the call made"),

            new RecordingSubject("agent_lang3FormattableAppend_oneFormatterForEveryThread", LANG3,
                    "org.apache.commons.lang3.text.FormattableUtils",
                    DetectorType.SHARED_FORMATTER, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "FormattableUtils is a stateless helper, but append(seq, formatter, ...) pads the "
                            + "text and calls formatter.format on the Formatter it is handed, and "
                            + "every thread hands it the same one. The format call is commons-lang3's",
                    IssueSeverity.HIGH),

            new RecordingSubject("agent_lang3FormattableAppend_oneFormatterPerCall", LANG3,
                    "org.apache.commons.lang3.text.FormattableUtils",
                    DetectorType.SHARED_FORMATTER, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same commons-lang3 call into a Formatter this call built and closes"),

            new RecordingSubject("agent_groovyMatcherCount_oneMatcherForEveryThread", GROOVY,
                    "org.codehaus.groovy.runtime.StringGroovyMethods",
                    DetectorType.SHARED_MATCHER, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "getCount(Matcher) resets the Matcher it is handed and calls find() on it until "
                            + "it fails, in Groovy's class file, and every thread hands it the same "
                            + "one. Groovy states no contract; Matcher's javadoc does: not safe for "
                            + "use by multiple concurrent threads (#545)",
                    IssueSeverity.HIGH),

            new RecordingSubject("agent_groovyMatcherCount_oneMatcherPerCall", GROOVY,
                    "org.codehaus.groovy.runtime.StringGroovyMethods",
                    DetectorType.SHARED_MATCHER, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same Groovy call on a Matcher this call took from the shared Pattern, "
                            + "which is how the regex API is meant to be used"),
            new RecordingSubject("agent_guavaMonitorTryEnter_leftAfterFailing", GUAVA,
                    "com.google.common.util.concurrent.Monitor",
                    DetectorType.TRY_LOCK_MISUSE, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "the Monitor javadoc says a boolean enter \"should always appear as the "
                            + "condition of an if statement\". This one does not: tryEnter fails "
                            + "on a monitor another thread occupies, and leave() unlocks a lock "
                            + "the worker never took. Both lock calls are Guava's",
                    IssueSeverity.HIGH),

            new RecordingSubject("agent_guavaMonitorTryEnter_leftOnlyWhenEntered", GUAVA,
                    "com.google.common.util.concurrent.Monitor",
                    DetectorType.TRY_LOCK_MISUSE, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the javadoc's shape, on the occupied monitor and on one the call can enter, "
                            + "so this half reaches a successful tryLock and its unlock as well as "
                            + "the failure. Leaving only when entered is the whole rule"),

            new RecordingSubject("agent_guavaMonitorEnter_neverLeft", GUAVA,
                    "com.google.common.util.concurrent.Monitor",
                    DetectorType.LOCK_LEAKS, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "enter() with no leave(). The Monitor javadoc says a void enter \"should "
                            + "always be followed immediately by a try/finally block\"; without "
                            + "one, the ReentrantLock inside the monitor stays held",
                    IssueSeverity.CRITICAL),

            new RecordingSubject("agent_guavaMonitorEnter_leftInFinally", GUAVA,
                    "com.google.common.util.concurrent.Monitor",
                    DetectorType.LOCK_LEAKS, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "enter, try, finally leave: the first snippet in the Monitor javadoc. Guava's "
                            + "lock and unlock balance, and nothing is held at analysis"),

            new RecordingSubject("agent_guavaMonitorOrder_nestedBothWays", GUAVA,
                    "com.google.common.util.concurrent.Monitor",
                    DetectorType.LOCK_ORDER, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "B entered inside A, then A inside B. Monitor.enter is a ReentrantLock.lock "
                            + "in Guava's class file, so the two edges come from there and close "
                            + "the same two-cycle the ReentrantLock row writes by hand",
                    IssueSeverity.CRITICAL),

            new RecordingSubject("agent_guavaMonitorOrder_nestedOneWay", GUAVA,
                    "com.google.common.util.concurrent.Monitor",
                    DetectorType.LOCK_ORDER, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same two monitors, always A then B: one edge and no cycle"),

            new RecordingSubject("agent_hikariSleep_whileOccupyingAMonitor", HIKARI,
                    "com.zaxxer.hikari.util.UtilityElf",
                    DetectorType.SLEEP_IN_LOCK, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "HikariCP's quietlySleep, a stateless static helper, called while a Guava "
                            + "monitor is occupied. The lock is recorded from Guava's woven lock "
                            + "and the sleep from HikariCP's woven Thread.sleep, and neither "
                            + "library's code knows about the other",
                    IssueSeverity.MEDIUM),

            new RecordingSubject("agent_hikariSleep_afterLeavingTheMonitor", HIKARI,
                    "com.zaxxer.hikari.util.UtilityElf",
                    DetectorType.SLEEP_IN_LOCK, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same monitor traffic and the same sleep, with the sleep after the "
                            + "leave, so the lockset is empty when HikariCP's sleep asks it"),

            new RecordingSubject("agent_guavaLatchAwait_timedOut", GUAVA,
                    "com.google.common.util.concurrent.Uninterruptibles",
                    DetectorType.COUNTDOWN_LATCH, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "awaitUninterruptibly makes the timed await itself, on a latch of one that "
                            + "nothing counts down, so the await Guava made times out and the "
                            + "body drops the false Guava returns",
                    IssueSeverity.CRITICAL),

            new RecordingSubject("agent_guavaLatchAwait_sawItsCount", GUAVA,
                    "com.google.common.util.concurrent.Uninterruptibles",
                    DetectorType.COUNTDOWN_LATCH, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same Guava await on a latch this thread already counted down, which "
                            + "returns true with no timing assumption"),

            new RecordingSubject("agent_guavaLatchAwait_neverCountedDown", GUAVA,
                    "com.google.common.util.concurrent.Uninterruptibles",
                    DetectorType.LATCH_MISUSE, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "the await Guava makes on a latch of one never returns true and nothing ever "
                            + "counts it down, which is LatchMisuseDetector's missing-countdown "
                            + "condition: a gate its author believed would open. The detector needs "
                            + "no library countDown for it, only Guava's await (#545)",
                    IssueSeverity.CRITICAL),

            new RecordingSubject("agent_guavaLatchAwait_countedDownBeforeTheAwait", GUAVA,
                    "com.google.common.util.concurrent.Uninterruptibles",
                    DetectorType.LATCH_MISUSE, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same Guava await after the body counted the latch down to zero: the "
                            + "await returns, and a returned await settles the latch"),

            new RecordingSubject("agent_guavaQueuePut_filledToCapacity", GUAVA,
                    "com.google.common.util.concurrent.Uninterruptibles",
                    DetectorType.BLOCKING_QUEUE, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "two putUninterruptibly calls into a queue of two before any take, so the "
                            + "peak Guava's woven put observes reaches the bound. The bound is "
                            + "read off the queue, not written in the body",
                    IssueSeverity.MEDIUM),

            new RecordingSubject("agent_guavaQueuePut_drainedAsItFilled", GUAVA,
                    "com.google.common.util.concurrent.Uninterruptibles",
                    DetectorType.BLOCKING_QUEUE, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same Guava puts and takes, alternated, so the peak never leaves one of "
                            + "two"),

            new RecordingSubject("agent_guavaSemaphore_permitNeverReturned", GUAVA,
                    "com.google.common.util.concurrent.Uninterruptibles",
                    DetectorType.SEMAPHORE, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "tryAcquireUninterruptibly takes the permit with Guava's woven tryAcquire, "
                            + "and nothing releases it, so acquisitions exceed releases",
                    IssueSeverity.HIGH),

            new RecordingSubject("agent_guavaSemaphore_permitReturnedInFinally", GUAVA,
                    "com.google.common.util.concurrent.Uninterruptibles",
                    DetectorType.SEMAPHORE, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same Guava acquisition, released in a finally when it succeeded. The "
                            + "release is written in the body, because Guava has no release "
                            + "helper; the acquisition the leak is made of is Guava's in both"),

            // --- Deadlock. The one detector here whose input is the JVM rather than a woven call
            //     site, and the one whose MUST_FIRE row leaves the JVM permanently changed: a real
            //     deadlock does not end. Both rows are therefore ordered last, and the silent row
            //     asserts it ran while the JVM was still clean rather than assuming it.

            new RecordingSubject("agent_deadlock_noThreadBlockedOnAnother", JDK,
                    "java.lang.Thread",
                    DetectorType.DEADLOCKS, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the detector samples ThreadMXBean.findDeadlockedThreads() on every analysis "
                            + "whether or not anything called it, so this silence is a decision "
                            + "rather than an absent call. Nothing in the JVM is deadlocked when "
                            + "this row runs, and its premise gate asserts exactly that"),

            new RecordingSubject("agent_deadlock_twoThreadsBlockedOnEachOther", JDK,
                    "java.lang.Thread",
                    DetectorType.DEADLOCKS, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "two daemon threads take two monitors in opposite order and stay there. "
                            + "findDeadlockedThreads() reports any deadlocked thread in the JVM, "
                            + "not only a worker, which is what lets the corpus write a real "
                            + "deadlock without the workers being the ones stuck in it",
                    IssueSeverity.CRITICAL)
    );

    /**
     * The recording lane's subjects: the same libraries, with bodies that cooperate.
     *
     * <p>Every row is a both-directions pair with its twin, because one direction on its own
     * proves nothing. A detector that fires on everything passes a MUST_FIRE row; a detector
     * that was never wired up passes a MUST_STAY_SILENT row. Only the pair says the model works.
     */
    private static final List<RecordingSubject> RECORDING_SUBJECTS = List.of(

            // --- LatchMisuse and BlockingQueue. Both are classified AGENT-fed and neither can
            //     report in an agent-only run, because every record path resolves through a
            //     registry only register* populates and no hook calls it (#436). That makes them
            //     unreachable *there*, not unreachable: a recording body may call register*
            //     itself, which is the whole point of this lane. Both verdicts are pure counter
            //     arithmetic over the calls made, with no clock and no blocking anywhere in them.
            //
            //     Every subject is created inside the body on purpose. One AsyncTestContext
            //     serves all invocations x threads executions, so an instance held in a field
            //     would accumulate 240 executions' worth of counts against one registration and
            //     report for arithmetic that has nothing to do with the row.

            new RecordingSubject("recorded_latch_countedDownPastItsCount", JDK,
                    "java.util.concurrent.CountDownLatch",
                    DetectorType.LATCH_MISUSE, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "the latch is registered with a count of one and counted down twice, which is "
                            + "the extraCountDowns condition exactly. A latch counted past zero "
                            + "released waiters that its author believed were still gated",
                    IssueSeverity.CRITICAL),

            new RecordingSubject("recorded_latch_countedDownExactly", JDK,
                    "java.util.concurrent.CountDownLatch",
                    DetectorType.LATCH_MISUSE, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same registration and the same await, counted down once. Neither "
                            + "condition holds: one is not above one, and one is not below one. "
                            + "The rows differ by a single recorded call"),

            new RecordingSubject("recorded_blockingQueue_filledToCapacity", JDK,
                    "java.util.concurrent.ArrayBlockingQueue",
                    DetectorType.BLOCKING_QUEUE, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "five offers into a queue registered with capacity five and nothing taken "
                            + "out, so the observed peak reaches the 90% saturation threshold. "
                            + "Nothing drains it, which is what makes the peak monotone and the "
                            + "outcome independent of how the threads interleaved",
                    IssueSeverity.MEDIUM),

            new RecordingSubject("recorded_blockingQueue_drainedAsItFilled", JDK,
                    "java.util.concurrent.ArrayBlockingQueue",
                    DetectorType.BLOCKING_QUEUE, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same registered capacity, with every offer followed by a poll, so the "
                            + "peak never leaves one. A queue that keeps up with its producer is "
                            + "the ordinary case and must not read as saturated"),

            // --- The rest of the shared-instance family. Five detectors refused at wave 15 for
            //     want of a corpus library class with a documented contract - which was the wrong
            //     question. Each keeps its state per instance identity, so the pair is the same
            //     one the agent lane writes: one instance every thread records against, versus
            //     one per thread. The subject is the JDK type the detector models.
            //
            //     Only two of the five can cite the JDK. SHARED_KDF's detector quotes
            //     javax.crypto.KDF verbatim, and DocumentBuilderFactory's javadoc says outright
            //     that it "is not guaranteed to be thread safe". For CRC32, Deflater and TimeZone
            //     the JDK states no thread-safety contract at all, and the detectors' own javadoc
            //     asserts one without quoting a source. Those three rows therefore rest on
            //     documented *statefulness* - update/getValue, setRawOffset - rather than on a
            //     documented guarantee, and the NOT_THREAD_SAFE label on them is the corpus's
            //     reading rather than the JDK's word. Recorded here rather than left implied,
            //     because an unattributed contract sitting unremarked in this corpus is the same
            //     defect the netty ByteBuf note already calls out. See #437.

            new RecordingSubject("recorded_checksum_sharedAcrossThreads", JDK,
                    "java.util.zip.CRC32",
                    DetectorType.SHARED_CHECKSUM, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "every thread records against the one CRC32, with nothing held, so the "
                            + "instance's thread set exceeds one and its lockset is empty. update "
                            + "accumulates into the instance and getValue reads it back, so a "
                            + "shared one checksums an interleaving of everybody's bytes",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_checksum_oneInstancePerThread", JDK,
                    "java.util.zip.CRC32",
                    DetectorType.SHARED_CHECKSUM, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "a CRC32 per thread, recorded through the same method. The detector keys on "
                            + "identity, so each instance sees one thread; a finding here would "
                            + "mean it counts calls rather than sharing"),

            new RecordingSubject("recorded_deflater_sharedAcrossThreads", JDK,
                    "java.util.zip.Deflater",
                    DetectorType.SHARED_DEFLATER, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "one Deflater recorded by every thread with no lock held. A Deflater holds a "
                            + "native compression stream and an input buffer between calls, which "
                            + "is why it also needs an explicit end()",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_deflater_oneInstancePerThread", JDK,
                    "java.util.zip.Deflater",
                    DetectorType.SHARED_DEFLATER, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "a Deflater per thread, ended after use, recorded through the same overload. "
                            + "Distinct identities, one thread each"),

            new RecordingSubject("recorded_kdf_sharedAcrossThreads", JDK,
                    "javax.crypto.SecretKeyFactory",
                    DetectorType.SHARED_KDF, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "the one derivation object, recorded from every thread unguarded. This is the "
                            + "detector with a verbatim JDK citation behind it: javax.crypto.KDF "
                            + "states that its methods are not thread-safe and that threads "
                            + "sharing one object should synchronize amongst themselves",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_kdf_guardedByItsOwnMonitor", JDK,
                    "javax.crypto.SecretKeyFactory",
                    DetectorType.SHARED_KDF, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same one object, with every record made inside synchronized on it. That "
                            + "is precisely the remedy the KDF javadoc names, so the lockset "
                            + "never empties and the sharing is not the finding it would "
                            + "otherwise be"),

            new RecordingSubject("recorded_timeZone_mutatedByEveryThread", JDK,
                    "java.util.TimeZone",
                    DetectorType.SHARED_TIMEZONE, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "one TimeZone whose raw offset every thread records mutating. setRawOffset "
                            + "and setID are the documented mutators; a zone reached from a "
                            + "static field and then adjusted is the shape this models",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_timeZone_oneInstancePerThread", JDK,
                    "java.util.TimeZone",
                    DetectorType.SHARED_TIMEZONE, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "each thread mutates its own SimpleTimeZone and records that. Same call, same "
                            + "operation label, distinct identities - which is the only thing "
                            + "that may separate the two rows"),

            new RecordingSubject("recorded_xmlParser_sharedAcrossThreads", JDK,
                    "javax.xml.parsers.DocumentBuilder",
                    DetectorType.SHARED_XML_PARSER, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "one DocumentBuilder parsed from every thread. DocumentBuilderFactory's own "
                            + "javadoc states it is not guaranteed to be thread safe and that an "
                            + "application should use one builder per thread, which is the "
                            + "contract this row is the violation of",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_xmlParser_oneInstancePerThread", JDK,
                    "javax.xml.parsers.DocumentBuilder",
                    DetectorType.SHARED_XML_PARSER, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "newDocumentBuilder() per thread, which is what the factory javadoc tells you "
                            + "to do. The factory stays shared, exactly as the same javadoc "
                            + "permits, so only the builder's scope differs"),

            // --- StaticInitDeadlock: a ZERO_CONFIG detector with a recordable path. Its live
            //     sampler walks the JVM's stacks and needs two threads genuinely wedged in
            //     <clinit>, which poisons those classes for the life of the classloader. Its
            //     recorded path is pure bookkeeping over start/request/end and is deterministic,
            //     so that is the one worth pinning.

            new RecordingSubject("recorded_classInit_twoThreadsWaitingOnEachOther", JDK,
                    "java.lang.Class",
                    DetectorType.STATIC_INIT_DEADLOCK, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "half the threads hold Alpha and ask for Beta while the other half do the "
                            + "reverse, which is the cycle findCycles walks. This is the deadlock "
                            + "the JVM's own class-init lock produces, recorded rather than "
                            + "suffered: a real one wedges both classes permanently",
                    IssueSeverity.CRITICAL),

            new RecordingSubject("recorded_classInit_eachInitialiserCompleted", JDK,
                    "java.lang.Class",
                    DetectorType.STATIC_INIT_DEADLOCK, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same start and request calls, with each thread ending its own "
                            + "initialiser. Ending a class clears its holder and every wait on it, "
                            + "so an initialiser that touches another class and then finishes - "
                            + "which is most of them - must not read as a cycle"),


            // --- SharedJsonMapperReconfig: the cleanest both-directions case in the corpus.
            //     The two Jackson mappers are separate instances of the same class, and the only
            //     difference between them is whether the body reconfigures one after sharing it.

            new RecordingSubject("recorded_objectMapper_reconfigureWhileWriting", JACKSON,
                    "com.fasterxml.jackson.databind.ObjectMapper",
                    DetectorType.SHARED_JSON_MAPPER_RECONFIG, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "the body records a config mutation after every thread has recorded a use, "
                            + "which is the detector's stated precondition. Jackson documents the "
                            + "mapper as thread-safe once configured, and reconfiguring a shared "
                            + "one is the exception its own javadoc names",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_objectMapper_configuredThenShared", JACKSON,
                    "com.fasterxml.jackson.databind.ObjectMapper",
                    DetectorType.SHARED_JSON_MAPPER_RECONFIG, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the body records uses and never a mutation, so config-then-use - the "
                            + "documented correct pattern - has no precondition to meet"),

            // --- CacheConcurrency: a documented-unsafe map and a documented-safe one, recorded
            //     identically. The detector has only the map to tell them apart.

            new RecordingSubject("recorded_lruMap_getAndPut", COLLECTIONS4,
                    "org.apache.commons.collections4.map.LRUMap",
                    DetectorType.CACHE_CONCURRENCY, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "reads and writes are recorded against a map its own javadoc says is not "
                            + "synchronized, which is the read/write race the detector exists for",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_caffeineAsMap_getAndPut", CAFFEINE,
                    "com.github.benmanes.caffeine.cache.Cache",
                    DetectorType.CACHE_CONCURRENCY, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same reads and writes against a view whose javadoc promises a "
                            + "thread-safe map. The receiver implements ConcurrentMap and keeps "
                            + "the contract; a finding here is noise on correct code"),

            // --- ConcurrentMapCheckThenAct: this pair is about the usage, not the class. Both
            //     receivers are documented thread-safe, and only one body is wrong.

            new RecordingSubject("recorded_concurrentReferenceHashMap_checkThenAct", SPRING,
                    "org.springframework.util.ConcurrentReferenceHashMap",
                    DetectorType.CONCURRENT_MAP_CHECK_THEN_ACT, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "get-then-put on one key from six threads. Each call is atomic and the pair "
                            + "is not, which is the lost update the detector reports; the class "
                            + "is thread-safe and the caller is still wrong",
                    IssueSeverity.HIGH),

            // recorded_caffeineAsMap_computeIfAbsent stood here and was removed for #410. It
            // demonstrated the atomic primitive that fixes the row above, and as evidence it was
            // empty: it called no detector API, so a detector that fired on every recordCheckThenAct
            // would have passed it. It cannot be repaired either, and that is the interesting part.
            // The correct use of a ConcurrentMap has no check-then-act to record, so for this
            // detector the correct twin is unrecordable - which is the same fact, seen from the
            // other side, as its staying PROMPT: the caller declares the defect, and a caller with
            // nothing to declare is silent before the detector is consulted.
            // recorded_concurrentReferenceHashMap_checkThenActOnPrivateKeys is the silent row that
            // does exercise the model, on the same class as the firing row.
            // --- JdbcConnectionShared: a pool is the documented fix, and used to be reported
            //     as the defect. Deferred from #302 until a recording lane existed, because
            //     this detector is recording-fed and had an exposure of zero without one.

            new RecordingSubject("recorded_hikariPool_checkoutPerThread", HIKARI,
                    "com.zaxxer.hikari.HikariDataSource",
                    DetectorType.JDBC_CONNECTION_SHARED, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the pool hands one physical connection to many threads over the run, one at "
                            + "a time, and each body records its release. That is the per-thread "
                            + "checkout the detector's own message recommends, and the silence is "
                            + "guaranteed by HikariCP's checkout discipline rather than by "
                            + "timing: a checked-out connection is not handed to a second thread"),

            new RecordingSubject("recorded_hoistedConnection_sharedAcrossThreads", HIKARI,
                    "com.zaxxer.hikari.HikariDataSource",
                    DetectorType.JDBC_CONNECTION_SHARED, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "one connection is checked out once and then used by every thread without "
                            + "ever being released, which is the bug a pool exists to prevent. "
                            + "The pool is correct and the caller defeated it, so the finding is "
                            + "owed however thread-safe HikariDataSource itself is",
                    IssueSeverity.HIGH),

            // --- SharedMessageDigest: the pair differs by a lock, not by an instance. Both rows
            //     share one digest with six threads; only one of them holds its monitor.

            new RecordingSubject("recorded_messageDigest_sharedAcrossThreads", JDK,
                    "java.security.MessageDigest",
                    DetectorType.SHARED_MESSAGE_DIGEST, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "one SHA-256 instance is recorded from six threads with nothing held, which "
                            + "is both halves of the detector's rule met by construction. The "
                            + "JDK's own javadoc says a MessageDigest is not safe for use by "
                            + "multiple threads without external synchronization",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_messageDigest_guardedByItsOwnMonitor", JDK,
                    "java.security.MessageDigest",
                    DetectorType.SHARED_MESSAGE_DIGEST, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same instance and the same six threads, with every access inside "
                            + "synchronized on the digest itself. That is the external "
                            + "synchronization the javadoc asks for, and Thread.holdsLock sees it "
                            + "with no agent attached, so the candidate lock set never empties"),

            // --- SharedStatefulCrypto: the same question answered by confinement instead, so
            //     between the two crypto pairs both documented fixes have a row.

            new RecordingSubject("recorded_mac_sharedAcrossThreads", JDK,
                    "javax.crypto.Mac",
                    DetectorType.SHARED_STATEFUL_CRYPTO, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "one HmacSHA256 carries its running state in one object's fields and is "
                            + "recorded from six threads with nothing held. Mac's javadoc makes "
                            + "no thread-safety promise, and interleaved update() calls corrupt "
                            + "the MAC rather than failing loudly",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_mac_confinedToOneThreadEach", JDK,
                    "javax.crypto.Mac",
                    DetectorType.SHARED_STATEFUL_CRYPTO, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "a Mac per thread, built the same way and recorded the same number of times. "
                            + "No instance is ever recorded from a second thread, so the rule's "
                            + "first clause is never met; a detector keyed on the class rather "
                            + "than the instance would report six correct threads as a race"),

            // --- ResourceLeak: reference counting, where the caller owns the release. The pair
            //     differs by exactly the call the detector is looking for.

            new RecordingSubject("recorded_nettyByteBuf_releasedAfterUse", NETTY,
                    "io.netty.buffer.ByteBuf",
                    DetectorType.RESOURCE_LEAKS, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "a fresh buffer per body execution, acquired and released before the body "
                            + "returns, so every tracked instance has one open, one close and is "
                            + "not open at analysis. No other thread touches it, so no "
                            + "interleaving can move either count"),

            new RecordingSubject("recorded_nettyByteBuf_neverReleased", NETTY,
                    "io.netty.buffer.ByteBuf",
                    DetectorType.RESOURCE_LEAKS, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "the identical lifecycle with the release left out. A ByteBuf is reference "
                            + "counted and the caller owns the release, so opens outnumbering "
                            + "closes is a leak whatever the schedule did",
                    IssueSeverity.MEDIUM),

            // --- ConcurrentMapComputeRecursion: the pair differs by whether the mapping function
            //     touches its own map. Reaching this one at all took a measurement (#341).
            //
            //     A nested computeIfAbsent on an ABSENT key never reaches the inner mapping
            //     function: ConcurrentHashMap parks a reservation node in the bin and throws
            //     IllegalStateException("Recursive update") first, so the second
            //     recordComputeStart could only ever be written by hand at the call site. On a
            //     key that is PRESENT the bin holds a real node, the re-entry re-acquires its
            //     monitor, and a monitor is reentrant: the nested call completes and the outer
            //     return value silently overwrites what it stored. That is the shape below, and
            //     it is the one shape of the three the platform does not report by itself.
            //
            //     Measured on JDK 26 before these rows were written, same-key re-entry:
            //       ConcurrentHashMap          computeIfAbsent/compute  ISE, inner never ran
            //       ConcurrentHashMap          merge (present key)      inner ran, returned
            //       Caffeine asMap             computeIfAbsent/compute  ISE, inner never ran
            //       Caffeine asMap             merge (present key)      inner ran, returned
            //       ConcurrentSkipListMap      all three                inner ran, returned
            //       ConcurrentReferenceHashMap all three                inner ran, returned
            //       Guava Cache.asMap          all three                deadlocked, never returned
            //     and at this lane's own shape, 240 of 240 nested mapping functions ran with no
            //     exception thrown.

            new RecordingSubject("recorded_caffeineAsMap_recursiveMerge", CAFFEINE,
                    "com.github.benmanes.caffeine.cache.Cache",
                    DetectorType.CONCURRENT_MAP_COMPUTE_RECURSION, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "the remapping function merges the same key on the same map, and because the "
                            + "key is present the re-entry re-acquires a reentrant monitor rather "
                            + "than hitting a reservation node. Both recordComputeStart calls are "
                            + "therefore raised from inside a mapping function that really ran, "
                            + "which is what the detector's contract asks for. The class is "
                            + "thread-safe and the caller is still wrong: the nested update is "
                            + "overwritten by the outer one and lost with nothing thrown",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_caffeineAsMap_selfContainedMerge", CAFFEINE,
                    "com.github.benmanes.caffeine.cache.Cache",
                    DetectorType.CONCURRENT_MAP_COMPUTE_RECURSION, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same merge, recorded the same way, with a remapping function that stays "
                            + "out of the map. One start per body execution, each closed by its "
                            + "end, so the slot is never occupied twice. Six threads merging the "
                            + "same key at once is contention, not recursion, and a detector "
                            + "keyed on the map alone rather than on the nesting would report it"),

            // --- ConcurrentMapComputeRecursion, the cross-key half (#343). The rule used to be
            //     same-key only, so a mapping function that reached its own map under another
            //     key was invisible - which is the shape example 40 ships to demonstrate this
            //     detector. ConcurrentHashMap's contract is "the mapping function must not
            //     modify this map", not "must not modify this key".
            //
            //     Measured at this lane's own six threads and forty invocations: 240 of 240
            //     nested mapping functions ran with nothing thrown, for both rows below.

            new RecordingSubject("recorded_caffeineAsMap_crossKeyMerge", CAFFEINE,
                    "com.github.benmanes.caffeine.cache.Cache",
                    DetectorType.CONCURRENT_MAP_COMPUTE_RECURSION, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "the remapping function merges a different key of the same map while the "
                            + "first key is still being computed. The contract it breaks is not "
                            + "key-scoped, and this version usually returns normally rather than "
                            + "throwing, which is why it survives review and why it is worth "
                            + "reporting: the map is updated in an order the caller did not "
                            + "intend, silently",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_caffeineTwoMaps_nestedMerge", CAFFEINE,
                    "com.github.benmanes.caffeine.cache.Cache",
                    DetectorType.CONCURRENT_MAP_COMPUTE_RECURSION, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the identical nesting one map apart. A mapping function that fills some "
                            + "other cache is ordinary layered-cache code, and the prohibition is "
                            + "per map, so this must stay silent. It is the row that makes the "
                            + "cross-key rule safe to have on by default: a detector keyed on the "
                            + "thread rather than the map would report every layered cache"),

            // --- SynchronizedCollectionIteration: the class is a synchronizing decorator and the
            //     defect is the caller's, exactly like the check-then-act pair above. What makes
            //     this a corpus contract rather than folklore is that commons-collections4 states
            //     the rule in the class javadoc itself, with the code:
            //
            //       "Iterators must be manually synchronized:
            //          synchronized (coll) { Iterator it = coll.iterator(); ... }"
            //       org/apache/commons/collections4/collection/SynchronizedCollection.java:29
            //
            //     Both rows share one wrapper and differ only in the holdingLock flag, so the
            //     detector is handed identical evidence apart from the one bit its model turns on.

            new RecordingSubject("recorded_synchronizedCollection_iteratedWithoutLock", COLLECTIONS4,
                    "org.apache.commons.collections4.collection.SynchronizedCollection",
                    DetectorType.SYNCHRONIZED_COLLECTION_ITERATION, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "every method of the decorator takes the collection's lock, and iteration is "
                            + "the documented exception the caller has to hold it for. Iterating "
                            + "without it leaves each next() individually synchronized and the "
                            + "traversal as a whole unprotected, which is a "
                            + "ConcurrentModificationException or a silently skipped element. The "
                            + "class is thread-safe and the caller is still wrong",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_synchronizedCollection_iteratedHoldingLock", COLLECTIONS4,
                    "org.apache.commons.collections4.collection.SynchronizedCollection",
                    DetectorType.SYNCHRONIZED_COLLECTION_ITERATION, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same traversal of the same decorator, inside synchronized (coll), which "
                            + "is the pattern the javadoc prints. A finding here would be a "
                            + "finding on the documented fix, which is the direction that stops "
                            + "people using the detector at all"),

            // --- SharedIterator: the collection is genuinely concurrent and the iterator is
            //     still single-thread state. Guava documents ConcurrentHashMultiset as
            //     "supports concurrent modifications and provides atomic versions of most
            //     Multiset operations" - com/google/common/collect/ConcurrentHashMultiset.java:50
            //     - which is what makes the pair worth having: the detector's own message says
            //     the hazard stands "even when that collection is itself a concurrent
            //     collection", and this is the row that holds it to that.
            //
            //     Both rows call hasNext() on an iterator of the same collection and differ only
            //     in whether the iterator object is shared. hasNext() rather than next() because
            //     it does not consume: a shared iterator drained by 240 body executions would
            //     end the run on NoSuchElementException instead of measuring anything.

            new RecordingSubject("recorded_concurrentHashMultiset_sharedIterator", GUAVA,
                    "com.google.common.collect.ConcurrentHashMultiset",
                    DetectorType.SHARED_ITERATOR, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "one iterator instance is advanced by every thread in the run. The multiset "
                            + "is documented to support concurrent modification and that buys the "
                            + "iterator nothing: the cursor is unsynchronized state of its own, "
                            + "and sharing it skips or duplicates elements. Thread-safe class, "
                            + "unsafe caller",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_concurrentHashMultiset_iteratorPerThread", GUAVA,
                    "com.google.common.collect.ConcurrentHashMultiset",
                    DetectorType.SHARED_ITERATOR, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same call on the same collection, with each body taking its own "
                            + "iterator. Confining an iterator to the thread that created it is "
                            + "the fix, and a finding here would report every correct traversal "
                            + "of a concurrent collection there is"),

            // --- ConcurrentModifications. This pair was written a few hours before #395 was
            //     fixed, and its silent twin had to be a JDK CopyOnWriteArrayList, because the
            //     detector recognised safety by package prefix and every third-party thread-safe
            //     collection reported. That is closed: the model now reads the naming convention
            //     the ecosystem actually uses, so the twin is third-party like the rest of the
            //     corpus, and this row is what holds the fix.

            new RecordingSubject("recorded_cursorableLinkedList_concurrentAdd", COLLECTIONS4,
                    "org.apache.commons.collections4.list.CursorableLinkedList",
                    DetectorType.CONCURRENT_MODIFICATIONS, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "its own javadoc says in bold that the implementation is not synchronized, and "
                            + "every thread in the run mutates it. This is the case the detector "
                            + "exists for and the one it gets right",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_concurrentMultiset_concurrentAdd", GUAVA,
                    "com.google.common.collect.ConcurrentHashMultiset",
                    DetectorType.CONCURRENT_MODIFICATIONS, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the identical mutation on a multiset guava documents as supporting "
                            + "concurrent modification. This exact subject reported until #395 "
                            + "was fixed - it was one of the two the false positive was measured "
                            + "on - so the row is both the silent half of the pair and the thing "
                            + "that keeps that fix honest"),

            // --- MutableMapKey: the tightest pair in the lane. Both rows use the same
            //     commons-lang3 class, insert it as a key the same way, and differ only in
            //     whether the body then mutates it. Nothing about the subject separates them,
            //     which leaves only the detector's model to do it.
            //
            //     recordKeyInserted is called once for the run rather than per worker. It
            //     installs a fresh registration, so a per-worker call would reset the mutation
            //     count and the loud row could go quiet depending on interleaving. That is the
            //     shape three record*Created methods were fixed for; this one is left alone
            //     because resetting on re-insertion is arguably its correct semantics - a key
            //     re-inserted after mutation really has been re-hashed - and changing it would
            //     need evidence this row does not provide.

            new RecordingSubject("recorded_mutableIntKey_mutatedAfterInsertion", LANG3,
                    "org.apache.commons.lang3.mutable.MutableInt",
                    DetectorType.MUTABLE_MAP_KEY, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "a MutableInt is put in a map as a key and then mutated, which changes the "
                            + "hash the map filed it under. The entry becomes unreachable by "
                            + "equal keys and the map cannot repair itself, whatever "
                            + "synchronization the caller adds",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_mutableIntKey_neverMutated", LANG3,
                    "org.apache.commons.lang3.mutable.MutableInt",
                    DetectorType.MUTABLE_MAP_KEY, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same mutable class used as a key and left alone, which is the ordinary "
                            + "and correct way to use one. Mutability is a hazard only when "
                            + "exercised, and reporting the type itself would report every "
                            + "correct use of it"),
            // --- #406: two rows added so that what separates fire from silence is the defect and
            //     not the class. The third detector the issue named, CACHE_CONCURRENCY, gets no
            //     row: its model asks the map's type whether it synchronizes itself, so given one
            //     class both halves of a pair get the same answer by construction.

            new RecordingSubject("recorded_cursorableLinkedList_mutatedUnderItsOwnMonitor", COLLECTIONS4,
                    "org.apache.commons.collections4.list.CursorableLinkedList",
                    DetectorType.CONCURRENT_MODIFICATIONS, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same class as the firing row, mutated by every thread under the "
                            + "collection's own monitor. The detector intersects the locks held "
                            + "across recorded mutations and reports only an empty intersection, "
                            + "so this pair separates on the synchronization alone"),

            new RecordingSubject("recorded_concurrentReferenceHashMap_checkThenActOnPrivateKeys", SPRING,
                    "org.springframework.util.ConcurrentReferenceHashMap",
                    DetectorType.CONCURRENT_MAP_CHECK_THEN_ACT, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same class and the same recorded check-then-act as the firing row, on a "
                            + "key private to each thread. The detector groups by (map, key) and "
                            + "reports only a site more than one thread reached, so the silence is "
                            + "a decision rather than an absence of calls"),

            // --- SharedByteBuffer: one instance, six threads, and the pair separates on which
            //     half of the Buffer API the body uses. Buffer's own javadoc is the contract
            //     ("Buffers are not safe for use by multiple concurrent threads"), and the
            //     mutable state behind that sentence is the cursor - position, limit and mark -
            //     which only relative operations touch. Absolute get(int) reads at an explicit
            //     index and moves nothing, which is why the detector's model treats it as
            //     context rather than violation. Sharing is held constant, so the operation-kind
            //     distinction is the only thing that separates the rows.

            new RecordingSubject("recorded_byteBuffer_relativeGetsShared", JDK,
                    "java.nio.ByteBuffer",
                    DetectorType.SHARED_BYTE_BUFFER, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "rewind() and a relative get() are recorded from six threads with nothing "
                            + "held. Both mutate the cursor the Buffer javadoc leaves "
                            + "unprotected, so several positional threads with an empty lock "
                            + "set is met by construction, which is the detector's whole rule",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_byteBuffer_absoluteGetsShared", JDK,
                    "java.nio.ByteBuffer",
                    DetectorType.SHARED_BYTE_BUFFER, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same six threads share the twin buffer and record only absolute "
                            + "get(int) calls, which never read or move position, limit or mark. "
                            + "The detector counts them as context, not violation, so the "
                            + "silence is its operation model deciding rather than an absence "
                            + "of input"),

            // --- FileChannelPositionRace: the class is documented thread-safe and the hazard
            //     is the one stateful thing that guarantee does not cover, the implicit
            //     position. Both rows read the same temp file through a channel shared by every
            //     thread and differ only in which read overload the body uses - the
            //     cursor-advancing read(ByteBuffer) or the self-contained read(ByteBuffer, long).

            new RecordingSubject("recorded_fileChannel_implicitReadsShared", JDK,
                    "java.nio.channels.FileChannel",
                    DetectorType.FILE_CHANNEL_POSITION_RACE, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "every thread records a cursor-advancing read(ByteBuffer) on one shared "
                            + "channel. FileChannel serializes each call internally, but the "
                            + "offset a read starts from depends on every other thread's "
                            + "progress, so the I/O lands at positions no caller chose - the "
                            + "class is thread-safe and the caller is still wrong",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_fileChannel_positionalReadsShared", JDK,
                    "java.nio.channels.FileChannel",
                    DetectorType.FILE_CHANNEL_POSITION_RACE, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same sharing recorded through read(ByteBuffer, position), which takes "
                            + "an explicit offset and never consults the shared cursor. That is "
                            + "the overload the detector's own message recommends, and a "
                            + "finding on it would report the documented fix as the defect"),

            // --- WeakHashMapShared: the pair differs by a lock, exactly like the digest pair,
            //     on the JDK map whose javadoc says the class is not synchronized and names
            //     external synchronization as the fix. The keys are compile-time String
            //     constants, so the GC never clears a referent and the lazily-run expunge
            //     cannot restructure the table mid-row: the rows measure the sharing, not the
            //     reference queue.

            new RecordingSubject("recorded_weakHashMap_sharedAcrossThreads", JDK,
                    "java.util.WeakHashMap",
                    DetectorType.WEAK_HASH_MAP_SHARED, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "one WeakHashMap is recorded from six threads with nothing held. Its own "
                            + "javadoc says the class is not synchronized, and its GC-driven "
                            + "expunge mutates the table on every get and put, which is the "
                            + "hazard the detector names",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_weakHashMap_guardedByItsOwnMonitor", JDK,
                    "java.util.WeakHashMap",
                    DetectorType.WEAK_HASH_MAP_SHARED, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same map and the same six threads, with every access inside "
                            + "synchronized on the map itself - the external synchronization "
                            + "the javadoc asks for. Thread.holdsLock sees that with no agent "
                            + "attached, so the candidate lock set never empties and a finding "
                            + "here would report the fix as loudly as the bug"),

            // --- SharedCharsetCoder: the crypto pairs' confinement shape on the coder family.
            //     CharsetEncoder's class javadoc states the contract outright: "Instances of
            //     this class are not safe for use by multiple concurrent threads." The safe
            //     pattern the detector's own message leads with is a coder per thread, so the
            //     silent twin is confinement, exactly like the Mac pair.

            new RecordingSubject("recorded_charsetEncoder_sharedAcrossThreads", JDK,
                    "java.nio.charset.CharsetEncoder",
                    DetectorType.SHARED_CHARSET_CODER, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "one UTF-8 encoder is recorded from six threads with nothing held. Its "
                            + "javadoc says instances are not safe for use by multiple "
                            + "concurrent threads, and the state machine behind that sentence "
                            + "is advanced by every reset() and encode() the bodies make",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_charsetEncoder_encoderPerThread", JDK,
                    "java.nio.charset.CharsetEncoder",
                    DetectorType.SHARED_CHARSET_CODER, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "an encoder per thread, built from the same Charset and recorded the same "
                            + "number of times. No instance is ever recorded from a second "
                            + "thread, so the rule's first clause is never met; a detector "
                            + "keyed on the coder class rather than the instance would report "
                            + "six correct threads as a race"),

            // --- ExecutorShutdown: a protocol pair like the ResourceLeak rows, on the executor
            //     lifecycle. Ownership is declared, which is the detector's whole model:
            //     recordExecutorCreated means this scope owns the close, and the pair differs
            //     only in whether the declared owner ever performs it.

            new RecordingSubject("recorded_executor_neverShutDown", JDK,
                    "java.util.concurrent.ExecutorService",
                    DetectorType.EXECUTOR_SHUTDOWN, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "one declared-owned pool takes a real submission from every body and no "
                            + "shutdown is ever recorded. ExecutorService's javadoc says an "
                            + "unused executor should be shut down to allow reclamation of its "
                            + "resources; its non-daemon workers otherwise outlive the test, "
                            + "and the finding follows from the recorded lifecycle alone",
                    IssueSeverity.MEDIUM),

            new RecordingSubject("recorded_executor_shutdownAndAwaited", JDK,
                    "java.util.concurrent.ExecutorService",
                    DetectorType.EXECUTOR_SHUTDOWN, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "a fresh pool per body execution, declared, submitted to, shut down and "
                            + "awaited before the body returns - the full protocol the "
                            + "detector's own fix text prescribes, recorded call for call. "
                            + "Every tracked instance ends with both flags set, so the silence "
                            + "is the model clearing a completed lifecycle, not an absence of "
                            + "input"),

            // --- Timer: a lifecycle pair on the class the JDK documents as thread-safe whose
            //     one fragility is its single task-execution thread. The pair separates on
            //     recordTaskException alone: the detector's thread-death claim follows from
            //     that one recorded event and from nothing the scheduler did. The silent row
            //     records schedule, run and complete for one task on its own timer. Since #575
            //     starvation is a task falling due while another holds the timer thread, which one
            //     task cannot do, so no GC pause can break the silence; before it the row had to
            //     leave recordTaskRun out, because runs were judged against a 100 ms threshold.

            new RecordingSubject("recorded_timer_taskExceptionKillsThread", JDK,
                    "java.util.Timer",
                    DetectorType.TIMER, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "a real TimerTask records its uncaught exception and then throws it, which "
                            + "really terminates the timer's single task-execution thread - the "
                            + "failure mode where every remaining task is cancelled with "
                            + "nothing reported. The body awaits the task before returning, so "
                            + "the record precedes analysis by construction",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_timer_tasksCompleteWithoutException", JDK,
                    "java.util.Timer",
                    DetectorType.TIMER, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same schedule-run-complete lifecycle on a second timer, with no "
                            + "exception recorded because none is thrown, and one task, which "
                            + "cannot fall due while another task holds the timer's thread. "
                            + "Neither thread death nor starvation can follow from these calls, "
                            + "so the silence is the model finding a completed lifecycle"),

            // --- Timer starvation (#616): the half of the detector the pair above never
            //     exercised. Both rows run the same two real tasks with the same records; they
            //     separate on whether the waiter's own scheduledExecutionTime falls inside the
            //     holder's recorded run, which each body arranges by construction.

            new RecordingSubject("recorded_timer_taskStarvedBehindAnother", JDK,
                    "java.util.Timer",
                    DetectorType.TIMER, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "a real holder task schedules a waiter two milliseconds out and keeps the "
                            + "timer's single thread until the wall clock is past the waiter's own "
                            + "scheduledExecutionTime, so the waiter falls due while another task "
                            + "holds the thread - starvation observed from the tasks' instants, "
                            + "with no duration threshold",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_timer_slowTaskWithNothingDueBehindIt", JDK,
                    "java.util.Timer",
                    DetectorType.TIMER, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same slow holder and the same waiter with the same records, on a "
                            + "second timer, except the waiter is scheduled only after the holder "
                            + "recorded its completion, so it falls due after the run. A long run "
                            + "that nothing waited behind is not starvation"),

            // --- FutureIgnored: the purest protocol pair in the lane. The detector's whole
            //     model is one boolean per submitted Future - was it ever inspected - so the
            //     rows differ in exactly that call and nothing else.

            new RecordingSubject("recorded_future_submittedAndNeverInspected", JDK,
                    "java.util.concurrent.Future",
                    DetectorType.FUTURE_IGNORED, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "every body submits a real task and records the returned Future, and no "
                            + "body ever records an inspection. An exception thrown by such a "
                            + "task is captured in the Future and discarded with it, which is "
                            + "the silent-failure mode the detector exists for; the finding "
                            + "follows from the absent call, so no schedule can remove it",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_future_inspectedAfterSubmit", JDK,
                    "java.util.concurrent.Future",
                    DetectorType.FUTURE_IGNORED, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same submissions to the same pool, each followed by a recorded "
                            + "inspection and a real get(). Retrieval is the fix the detector's "
                            + "own message prescribes, and a finding here would report every "
                            + "correctly awaited task in existence"),

            // --- NotifyWithoutMonitor: the tightest pair the lane can hold. Both rows record
            //     the identical call on the identical monitor, and the only difference is
            //     whether the body is inside synchronized (monitor). The detector samples
            //     Thread.holdsLock at record time, so its own probe is the discriminator, and
            //     java.lang.Object states the contract: notify/notifyAll throw
            //     IllegalMonitorStateException "if the current thread is not the owner of this
            //     object's monitor". The loud row proves its own premise by really calling
            //     notifyAll once and letting the JVM throw.

            new RecordingSubject("recorded_notify_withoutHoldingTheMonitor", JDK,
                    "java.lang.Object",
                    DetectorType.NOTIFY_WITHOUT_MONITOR, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "every body declares a notifyAll on a monitor it does not hold, which "
                            + "Object's javadoc says throws IllegalMonitorStateException. The "
                            + "row does not merely assert that: it calls notifyAll for real "
                            + "once and records the exception the JVM throws, so the premise "
                            + "behind every finding is verified rather than stated",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_notify_holdingTheMonitor", JDK,
                    "java.lang.Object",
                    DetectorType.NOTIFY_WITHOUT_MONITOR, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same declaration on the same monitor from the same six threads, made "
                            + "inside synchronized (monitor) and followed by a real notifyAll "
                            + "that the JVM accepts. The legal call is the overwhelmingly "
                            + "common one, so a detector that reported it would fire on almost "
                            + "every wait/notify in existence"),

            // --- InterruptSwallowing: a caller-declares model, and the rows say so. Both
            //     bodies suffer a real InterruptedException - self-interrupt then sleep, which
            //     is deterministic rather than timed - and differ in the one boolean the
            //     detector reads. Its tier stays PROMPT for exactly that reason: the finding
            //     is only as good as the declaration behind it.

            new RecordingSubject("recorded_interruptedException_swallowed", JDK,
                    "java.lang.InterruptedException",
                    DetectorType.INTERRUPT_SWALLOWING, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "a real InterruptedException is caught and the interrupt flag is left "
                            + "cleared, which is what the JDK does to it on throw. The "
                            + "cancellation signal is then unobservable to every layer above, "
                            + "and the finding follows from the recorded handling rather than "
                            + "from any interleaving",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_interruptedException_flagRestored", JDK,
                    "java.lang.InterruptedException",
                    DetectorType.INTERRUPT_SWALLOWING, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the identical catch on the identical exception, with "
                            + "Thread.currentThread().interrupt() called before the record - "
                            + "the fix the detector's own message prescribes. A finding here "
                            + "would report correctly propagated cancellation"),

            // --- StreamClosing: the ResourceLeak shape on file descriptors. Both rows open a
            //     real file-backed InputStream and differ only in whether the close is
            //     performed and recorded, in the thread that opened it.

            new RecordingSubject("recorded_inputStream_openedAndNeverClosed", JDK,
                    "java.io.InputStream",
                    DetectorType.STREAM_CLOSING, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "one real file-backed stream is recorded open for the run and no close is "
                            + "ever recorded, so it is still open when the run is analysed - "
                            + "the leaked file descriptor the detector exists for. One "
                            + "instance rather than one per body because the leak is the "
                            + "point and 240 of them would exhaust the runner rather than "
                            + "demonstrate anything",
                    IssueSeverity.MEDIUM),

            new RecordingSubject("recorded_inputStream_closedInTheOpeningThread", JDK,
                    "java.io.InputStream",
                    DetectorType.STREAM_CLOSING, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "a fresh stream per body execution, opened, read, closed and recorded "
                            + "closed by the thread that opened it. That clears both rules the "
                            + "detector applies - nothing left open, and no cross-thread close "
                            + "- so the silence is two decisions rather than an absence of "
                            + "calls"),

            // --- The blocking-inside-a-guard family. Three detectors share one shape: a
            //     blocking call is fine on its own and a hazard while something is held, so
            //     each pair moves the identical blocking record outside the region and changes
            //     nothing else. Holding a monitor, a ForkJoinTask and a CompletableFuture
            //     callback are three different things to be inside, and the model is the same.

            new RecordingSubject("recorded_blockingCall_insideAMonitor", JDK,
                    "java.lang.Object",
                    DetectorType.NESTED_MONITOR_LOCKOUT, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "a blocking wait is recorded while a monitor is held, so the blocked thread "
                            + "keeps the monitor no other thread can now take. That is the "
                            + "lockout, and it follows from the order of the recorded calls",
                    IssueSeverity.CRITICAL),

            new RecordingSubject("recorded_blockingCall_afterReleasingTheMonitor", JDK,
                    "java.lang.Object",
                    DetectorType.NESTED_MONITOR_LOCKOUT, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the identical three calls with the release moved before the block, which is "
                            + "the fix and also the ordinary shape of correct code. A detector "
                            + "that reported it would flag every blocking call in a program that "
                            + "also uses monitors"),

            new RecordingSubject("recorded_blockingCall_insideAForkJoinTask", JDK,
                    "java.util.concurrent.ForkJoinTask",
                    DetectorType.FORK_JOIN_TASK_BLOCKING, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "a blocking call is recorded between task entry and exit. A pool worker "
                            + "parked on something other than its own join starves the pool it "
                            + "belongs to, which is why ForkJoinPool has managedBlock at all",
                    IssueSeverity.MEDIUM),

            new RecordingSubject("recorded_blockingCall_afterLeavingTheForkJoinTask", JDK,
                    "java.util.concurrent.ForkJoinTask",
                    DetectorType.FORK_JOIN_TASK_BLOCKING, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same calls with the block moved after the exit, so no worker is parked "
                            + "while inside a task. Blocking on a plain thread is not a defect "
                            + "and reporting it would be noise on ordinary code"),

            new RecordingSubject("recorded_blockingCall_insideACompletableFutureCallback", JDK,
                    "java.util.concurrent.CompletableFuture",
                    DetectorType.COMPLETABLE_FUTURE_BLOCKING_CALLBACK, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "a join is recorded inside a completion callback, which blocks the thread "
                            + "that is supposed to be running continuations and can stall every "
                            + "other stage sharing it. CompletableFuture is thread-safe and the "
                            + "caller is still wrong",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_blockingCall_afterTheCallbackReturned", JDK,
                    "java.util.concurrent.CompletableFuture",
                    DetectorType.COMPLETABLE_FUTURE_BLOCKING_CALLBACK, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the identical join recorded after the callback has returned, which is "
                            + "where a caller is supposed to wait. The pair separates on the "
                            + "region the call sits in and on nothing else"),

            // --- The lock-object family: two detectors that both ask what you are
            //     synchronizing on rather than what you do inside. Each pair swaps a globally
            //     shared instance for a private one and changes nothing else.

            new RecordingSubject("recorded_synchronized_onAnInternedLiteral", JDK,
                    "java.lang.String",
                    DetectorType.SYNCHRONIZED_ON_LITERAL, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "the monitor is a string literal, and literals are interned per JVM, so "
                            + "unrelated code that happens to lock the same text shares this "
                            + "lock without either side knowing. String is immutable and "
                            + "thread-safe; what is wrong is using one as a monitor",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_synchronized_onAPrivateLockObject", JDK,
                    "java.lang.Object",
                    DetectorType.SYNCHRONIZED_ON_LITERAL, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same recorded acquisition on a private final Object, which is the "
                            + "documented lock idiom and cannot be reached by name from anywhere "
                            + "else. Reporting it would report the fix"),

            new RecordingSubject("recorded_lock_onABoxedInteger", JDK,
                    "java.lang.Integer",
                    DetectorType.BOXED_PRIMITIVE_LOCK, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "the monitor is a boxed Integer, and Integer.valueOf caches small values, "
                            + "so two unrelated places boxing the same number get the same "
                            + "object. The sharing is invisible at the call site, which is what "
                            + "makes it worth reporting",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_lock_onAPrivateObject", JDK,
                    "java.lang.Object",
                    DetectorType.BOXED_PRIMITIVE_LOCK, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same acquisition recorded on a private Object with no cache behind it. "
                            + "Identical evidence apart from the identity of the lock, which is "
                            + "the whole of this detector's model"),

            // --- AtomicNonAtomicUpdate: each operation is atomic and the pair is not, which is
            //     the same shape as the ConcurrentMap check-then-act row one type down.

            new RecordingSubject("recorded_atomicInteger_getThenSet", JDK,
                    "java.util.concurrent.atomic.AtomicInteger",
                    DetectorType.ATOMIC_NON_ATOMIC_UPDATE, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "a get and a set are recorded as a read-modify-write from six threads. Each "
                            + "call is atomic and the sequence is not, so an update between them "
                            + "is overwritten and lost - the reason compareAndSet exists",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_atomicInteger_getThenCompareAndSet", JDK,
                    "java.util.concurrent.atomic.AtomicInteger",
                    DetectorType.ATOMIC_NON_ATOMIC_UPDATE, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same read followed by a recorded compare-and-set, which is the atomic "
                            + "primitive the finding recommends. A detector that reported here "
                            + "would report the fix it prints"),

            // --- SpuriousWakeup: Object.wait's own javadoc says a wait may return without any
            //     notify and that callers must wait in a loop on a condition. The pair is that
            //     one bit.

            new RecordingSubject("recorded_wait_withoutALoop", JDK,
                    "java.lang.Object",
                    DetectorType.SPURIOUS_WAKEUP_HAZARD, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "a wait is recorded as not guarded by a condition loop, which the javadoc "
                            + "says is wrong however the schedule behaves: a wait may return "
                            + "spuriously, and a caller that treats the return as the condition "
                            + "proceeds on a state that never held",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_wait_insideAConditionLoop", JDK,
                    "java.lang.Object",
                    DetectorType.SPURIOUS_WAKEUP_HAZARD, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same wait declared as sitting inside a while loop over its condition, "
                            + "which is the shape the javadoc prints. The pair separates on the "
                            + "one bit the detector reads"),

            // --- MdcContextLeak: diagnostic context that outlives its task. The pair differs by
            //     what the context map holds when the task ends, not by what it held at start.

            new RecordingSubject("recorded_mdc_keyLeftBehindAtTaskEnd", JDK,
                    "java.util.Map",
                    DetectorType.MDC_CONTEXT_LEAK, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "the task starts with an empty diagnostic context and ends holding a key it "
                            + "put there. On a pooled thread that key is inherited by whatever "
                            + "task runs next, which is how one request's id ends up on another "
                            + "request's log lines",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_mdc_contextClearedBeforeTaskEnd", JDK,
                    "java.util.Map",
                    DetectorType.MDC_CONTEXT_LEAK, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same task ending with exactly the context it began with, which is what "
                            + "a correct filter guarantees in its finally block. Nothing crosses "
                            + "the task boundary, so there is nothing to inherit"),

            // --- The wait/notify protocol family. Three detectors read the same monitor idiom
            //     from three angles - how long you wait, whether anyone was waiting, and
            //     whether a stamp was validated - and each pair changes one call.

            new RecordingSubject("recorded_wait_withNoTimeout", JDK,
                    "java.lang.Object",
                    DetectorType.WAIT_TIMEOUT, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "an untimed wait is recorded, which parks the thread until some other "
                            + "thread chooses to notify it. If that notify is lost or never "
                            + "sent the thread waits forever, and the difference between a "
                            + "wedged process and a slow one is whether a timeout was passed",
                    IssueSeverity.CRITICAL),

            new RecordingSubject("recorded_wait_withATimeoutAndANotify", JDK,
                    "java.lang.Object",
                    DetectorType.WAIT_TIMEOUT, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same wait declared with a bound, followed by a recorded notifyAll. A "
                            + "bounded wait recovers on its own, so reporting it would report "
                            + "the defensive version of the same code"),

            new RecordingSubject("recorded_notify_withNobodyWaiting", JDK,
                    "java.lang.Object",
                    DetectorType.MISSED_SIGNAL, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "a notify is recorded on a monitor no thread is waiting on, and a wait "
                            + "recorded on it afterwards ends with no notify in between. A signal "
                            + "delivered before the waiter arrives is not queued - it is simply "
                            + "lost - and the wait that follows blocks for a notification that "
                            + "has already been and gone",
                    IssueSeverity.CRITICAL),

            new RecordingSubject("recorded_notify_afterAWaiterArrived", JDK,
                    "java.lang.Object",
                    DetectorType.MISSED_SIGNAL, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same three calls on a monitor with the wait recorded first, so the "
                            + "notify reaches it before the wakeup, which is the whole handshake. "
                            + "The pair separates on whether the wait received a notify, not on "
                            + "how the threads were scheduled: each execution has its own monitor"),

            new RecordingSubject("recorded_missedSignal_repeatedIfCheck", JDK,
                    "java.lang.Object",
                    DetectorType.MISSED_SIGNAL, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "a notify is lost, then an if (!ready) wait times out and the body goes on; "
                            + "a later check finds the predicate still false and nothing waits "
                            + "again. Recorded predicate checks do not make a loop: since #656 a "
                            + "wait is confirmed as guarded only by the waiter's next events in "
                            + "the same round, and an unsatisfied check with no wait after it is "
                            + "not one",
                    IssueSeverity.CRITICAL),

            new RecordingSubject("recorded_missedSignal_whileLoopRecheck", JDK,
                    "java.lang.Object",
                    DetectorType.MISSED_SIGNAL, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same lost notify and timed wait inside while (!ready), with the "
                            + "predicate re-checked right after the wakeup and found satisfied, so "
                            + "the loop exits. The halves call the same detector methods and "
                            + "differ only in whether the check after the wakeup is satisfied"),

            new RecordingSubject("recorded_missedSignal_markedIfThenSatisfiedCheck", JDK,
                    "java.lang.Object",
                    DetectorType.MISSED_SIGNAL, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "on a monitor whose loops are marked, a notify is lost, then an if (!ready) "
                            + "wait times out and a later check finds ready true. Unmarked, that "
                            + "check read as the loop exiting (#656); with recordLoopStart and "
                            + "recordLoopEnd around the monitor's real loop, a wait outside every "
                            + "mark is an if's (#669)",
                    IssueSeverity.CRITICAL),

            new RecordingSubject("recorded_missedSignal_markedConsecutiveIfs", JDK,
                    "java.lang.Object",
                    DetectorType.MISSED_SIGNAL, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "the same marked monitor and lost notify, then two consecutive if (!ready) "
                            + "waits and a later check that still finds ready false. Unmarked, the "
                            + "second wait read as a back-edge and the check as a poll giving up "
                            + "(#656); outside every marked loop neither wait is a loop's (#669)",
                    IssueSeverity.CRITICAL),

            new RecordingSubject("recorded_missedSignal_markedWhileLoop", JDK,
                    "java.lang.Object",
                    DetectorType.MISSED_SIGNAL, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same marked monitor and lost notify, with the wait inside a marked "
                            + "while (!ready) loop that re-checks after the wakeup and exits. All "
                            + "three rows make the same calls; the marks carry the back-edge (#669)"),

            new RecordingSubject("recorded_optimisticRead_usedWithoutValidating", JDK,
                    "java.util.concurrent.locks.StampedLock",
                    DetectorType.OPTIMISTIC_READ_VALIDATION, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "data is read under an optimistic stamp and used with no validate() at "
                            + "all. StampedLock's optimistic mode is documented as valid only "
                            + "when validate() confirms it, so the value may be one a writer was "
                            + "changing, and nothing checked",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_optimisticRead_validatedBeforeUse", JDK,
                    "java.util.concurrent.locks.StampedLock",
                    DetectorType.OPTIMISTIC_READ_VALIDATION, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same read validated before use, which is the protocol the class "
                            + "documents. The pair differs by the validate() call, which is the "
                            + "defect itself; a validation that fails and falls back to the read "
                            + "lock is the same protocol and is silent too"),

            // --- LockUpgradeDeadlock: a read lock is not upgradable, and the pair differs by
            //     whether the read is released before the write is attempted.

            new RecordingSubject("recorded_readLock_upgradedWithoutReleasing", JDK,
                    "java.util.concurrent.locks.ReentrantReadWriteLock",
                    DetectorType.LOCK_UPGRADE_DEADLOCK, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "a thread holding the read lock attempts the write lock without releasing "
                            + "it. ReentrantReadWriteLock does not support upgrading, and the "
                            + "write acquisition waits for readers that include the caller "
                            + "itself, which is a deadlock the caller cannot be woken from",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_readLock_releasedBeforeWriting", JDK,
                    "java.util.concurrent.locks.ReentrantReadWriteLock",
                    DetectorType.LOCK_UPGRADE_DEADLOCK, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same two acquisitions with the read release between them, which is "
                            + "the documented way to move from reading to writing. A finding "
                            + "here would report every correct read-then-write there is"),

            // --- ScopedValue: a get outside any binding, against one inside. The detector
            //     tracks the binding as a region the same way the blocking-call family does.

            new RecordingSubject("recorded_scopedValue_readOutsideItsBinding", JDK,
                    "java.lang.ScopedValue",
                    DetectorType.SCOPED_VALUE, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "a value is read on a thread that never entered a binding for it. A scoped "
                            + "value is only defined inside the dynamic scope that bound it, so "
                            + "the read outside one is either an exception or a stale value "
                            + "from somewhere the caller did not mean",
                    IssueSeverity.CRITICAL),

            new RecordingSubject("recorded_scopedValue_readInsideItsBinding", JDK,
                    "java.lang.ScopedValue",
                    DetectorType.SCOPED_VALUE, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same read between a recorded binding entry and its exit, which is the "
                            + "only place the value is defined. The pair separates on the "
                            + "region the read sits in"),

            // --- StatefulLambda: the lambda is the subject, and sharing one that mutates its
            //     captured state is the defect. Confinement is the fix, as with the Mac pair.

            new RecordingSubject("recorded_lambda_sharedAndMutatingItsCapture", JDK,
                    "java.lang.Runnable",
                    DetectorType.STATEFUL_LAMBDA, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "one lambda instance is executed by six threads and records a mutation of "
                            + "the state it captured. A lambda that keeps state is an object "
                            + "with a field, and sharing it across threads races on that field "
                            + "exactly as sharing any other mutable object would",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_lambda_confinedToItsOwnThread", JDK,
                    "java.lang.Runnable",
                    DetectorType.STATEFUL_LAMBDA, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "a lambda per thread, executed and mutated the same number of times. No "
                            + "instance is ever executed by a second thread, so a stateful "
                            + "lambda that never escapes is not a hazard and must not read as "
                            + "one"),

            // --- SystemPropertyMutation: system properties are process-global, so the pair
            //     separates on whether two threads write the same key.

            new RecordingSubject("recorded_systemProperty_mutatedByEveryThread", JDK,
                    "java.lang.System",
                    DetectorType.SYSTEM_PROPERTY_MUTATION, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "six threads write one process-global key. The properties table is "
                            + "synchronized so nothing corrupts, and that is the point: the "
                            + "race is over which value the rest of the process reads, and it "
                            + "reaches every library in the JVM rather than just the caller",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_systemProperty_mutatedOnAPrivateKey", JDK,
                    "java.lang.System",
                    DetectorType.SYSTEM_PROPERTY_MUTATION, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same number of writes to a key private to each thread. Nothing "
                            + "contends, so what remains is a single-threaded mutation, which "
                            + "this detector deliberately does not report"),

            // --- WeakReferenceRace: the referent can be collected between a null check and a
            //     use, and the pair differs by whether anything keeps it reachable.

            new RecordingSubject("recorded_weakReference_dereferencedAfterClearing", JDK,
                    "java.lang.ref.WeakReference",
                    DetectorType.WEAK_REFERENCE_RACE, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "a get on a weak reference is recorded as having returned null where the "
                            + "caller expected a referent. Nothing about the reference is "
                            + "wrong; what is wrong is code that checks a weak reference and "
                            + "then uses it as if the collector had agreed to wait",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_weakReference_readWithAStrongReferent", JDK,
                    "java.lang.ref.WeakReference",
                    DetectorType.WEAK_REFERENCE_RACE, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same recorded read of a reference whose referent is held strongly for "
                            + "the run, so it cannot be cleared and the read cannot come back "
                            + "empty. That is the pattern that makes weak references safe to "
                            + "use, and reporting it would report the fix"),

            // --- VolatileArray: volatile on an array reference publishes the reference and
            //     nothing about the elements, which is the most-repeated misreading of the
            //     keyword. The pair separates on whether the array is shared at all.

            new RecordingSubject("recorded_volatileArray_elementsWrittenByEveryThread", JDK,
                    "java.lang.Object",
                    DetectorType.VOLATILE_ARRAY, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "one array has its elements written by six threads. Declaring the field "
                            + "volatile publishes the array reference and gives the element "
                            + "writes no ordering or visibility at all, which is why this looks "
                            + "safe in review and is not",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_volatileArray_confinedToOneThread", JDK,
                    "java.lang.Object",
                    DetectorType.VOLATILE_ARRAY, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "an array per thread, written the same number of times. No element is ever "
                            + "reached by a second thread, so there is nothing for the missing "
                            + "ordering to be missing between"),

            // --- The CompletableFuture lifecycle family: two VERDICT-tier detectors that ask
            //     what happened to a future after it was created. Each pair creates one the
            //     same way and differs in what the body records afterwards.

            new RecordingSubject("recorded_completableFuture_failedWithNoHandler", JDK,
                    "java.util.concurrent.CompletableFuture",
                    DetectorType.COMPLETABLE_FUTURE_EXCEPTIONS, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "a future completes exceptionally and no handler is ever recorded for it. "
                            + "The exception is then held inside the future and discarded with "
                            + "it, so the failure is invisible to the code that asked for the "
                            + "work - the same silent-loss shape as an ignored Future",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_completableFuture_failureHandled", JDK,
                    "java.util.concurrent.CompletableFuture",
                    DetectorType.COMPLETABLE_FUTURE_EXCEPTIONS, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the identical failure with a recorded handler before the completion, which "
                            + "is what exceptionally and handle exist for. The pair separates "
                            + "on whether anybody dealt with the exception"),

            new RecordingSubject("recorded_completableFuture_neverCompleted", JDK,
                    "java.util.concurrent.CompletableFuture",
                    DetectorType.COMPLETABLE_FUTURE_COMPLETION_LEAKS, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "a future is created and no completion is ever recorded, so anything "
                            + "waiting on it waits for a result that is not coming. A "
                            + "manually-completed future whose completing path is missed is a "
                            + "hang, not an error, which is why it is worth a detector",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_completableFuture_completedBeforeTheBodyReturned", JDK,
                    "java.util.concurrent.CompletableFuture",
                    DetectorType.COMPLETABLE_FUTURE_COMPLETION_LEAKS, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same creation with its completion recorded before the body returns, so "
                            + "every tracked future ends the run completed. The outcome follows "
                            + "from the calls rather than from when a pool got round to it"),

            // --- UnboundedQueue: the capacity is the whole model, and it is a parameter rather
            //     than something the detector has to infer.

            new RecordingSubject("recorded_blockingQueue_createdUnbounded", JDK,
                    "java.util.concurrent.LinkedBlockingQueue",
                    DetectorType.UNBOUNDED_QUEUE, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "a queue is declared with no capacity bound. The class is thread-safe and "
                            + "that is not the hazard: an unbounded queue converts a producer "
                            + "that outruns its consumer from backpressure into heap growth, "
                            + "and the failure arrives much later as an OutOfMemoryError",
                    IssueSeverity.MEDIUM),

            new RecordingSubject("recorded_blockingQueue_createdWithACapacity", JDK,
                    "java.util.concurrent.ArrayBlockingQueue",
                    DetectorType.UNBOUNDED_QUEUE, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same declaration with a bound, plus recorded enqueues and dequeues. A "
                            + "bounded queue blocks the producer instead of growing, which is "
                            + "the fix, and reporting it would report every correctly sized "
                            + "queue in a program"),

            // --- CopyOnWriteCollections: the class is thread-safe and the question is whether
            //     the workload suits it, so the pair is the same collection type under two
            //     read/write mixes.

            new RecordingSubject("recorded_copyOnWrite_underAWriteHeavyWorkload", JDK,
                    "java.util.concurrent.CopyOnWriteArrayList",
                    DetectorType.COPY_ON_WRITE_COLLECTIONS, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "writes dominate the recorded operations on a copy-on-write list. Every "
                            + "write copies the whole backing array, so the cost is quadratic "
                            + "in a workload like this - correct, and the wrong data structure, "
                            + "which is exactly what an advisory detector is for",
                    IssueSeverity.MEDIUM),

            new RecordingSubject("recorded_copyOnWrite_underAReadHeavyWorkload", JDK,
                    "java.util.concurrent.CopyOnWriteArrayList",
                    DetectorType.COPY_ON_WRITE_COLLECTIONS, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same class recorded under the mix it was designed for, many reads to "
                            + "one write. The pair separates on the workload and not on the "
                            + "type, which is the only way to test a model whose subject is "
                            + "correct by construction"),

            // --- ParallelStreams: a stateful operation in a parallel pipeline. The stream is
            //     the same either way; what changes is what the lambda does.

            new RecordingSubject("recorded_parallelStream_withAStatefulOperation", JDK,
                    "java.util.stream.Stream",
                    DetectorType.PARALLEL_STREAMS, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "a parallel pipeline records a stateful operation. The stream contract asks "
                            + "for non-interfering, stateless lambdas precisely because the "
                            + "framework may run them on any thread in any order, so a "
                            + "stateful one races on state the pipeline never promised to guard",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_parallelStream_withStatelessOperations", JDK,
                    "java.util.stream.Stream",
                    DetectorType.PARALLEL_STREAMS, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same parallel pipeline recorded with a stateless operation, which is "
                            + "what the contract asks for and what almost every correct "
                            + "parallel stream does"),

            // --- ThreadLocalLeaks: set without remove. On a pooled thread the value outlives
            //     the task, which is the same hazard as the MDC pair one type down.

            new RecordingSubject("recorded_threadLocal_initialisedAndNeverCleaned", JDK,
                    "java.lang.ThreadLocal",
                    DetectorType.THREAD_LOCAL_LEAKS, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "a thread-local is initialised and no cleanup is ever recorded. The value "
                            + "then lives as long as the thread does, which on a pooled thread "
                            + "means forever, and it keeps its whole reference graph alive with "
                            + "it",
                    IssueSeverity.MEDIUM),

            new RecordingSubject("recorded_threadLocal_cleanedUpAfterUse", JDK,
                    "java.lang.ThreadLocal",
                    DetectorType.THREAD_LOCAL_LEAKS, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same initialisation with a recorded cleanup behind it, which is the "
                            + "remove() in a finally block that every correct use has. The "
                            + "pair separates on that one call"),

            // --- DoubleCheckedLocking: the pattern is declared rather than inferred, and the
            //     rows differ in the volatile flag alone - the single bit that decides whether
            //     the idiom is correct.

            new RecordingSubject("recorded_doubleCheckedLocking_withoutVolatile", JDK,
                    "java.lang.Object",
                    DetectorType.DOUBLE_CHECKED_LOCKING, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "the classic broken singleton: both checks, inside synchronized, on a "
                            + "non-volatile field. Without volatile another thread can see the "
                            + "reference before the constructor's writes, so it hands out a "
                            + "partially built object - the reason the idiom needed fixing",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_doubleCheckedLocking_withVolatile", JDK,
                    "java.lang.Object",
                    DetectorType.DOUBLE_CHECKED_LOCKING, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the identical declaration with the field volatile, which is the documented "
                            + "fix and correct since Java 5. Four flags, one of them flipped, "
                            + "and nothing else differs"),

            // --- SynchronizedNonFinal: locking on a field that can be reassigned means two
            //     threads can hold different monitors while believing they are excluded.

            new RecordingSubject("recorded_synchronized_onAReassignableLock", JDK,
                    "java.lang.Object",
                    DetectorType.SYNCHRONIZED_NON_FINAL, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "the monitor is a fresh object each time on one shared owner, which is "
                            + "what locking on a non-final field looks like once somebody "
                            + "reassigns it. Two threads then synchronize on different objects "
                            + "and exclude nobody, while the code reads as guarded",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_synchronized_onAFinalLock", JDK,
                    "java.lang.Object",
                    DetectorType.SYNCHRONIZED_NON_FINAL, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same recorded acquisition on one final lock object for the run, which "
                            + "is the idiom every guide prints. The pair separates on whether "
                            + "the monitor identity is stable"),

            // --- FinalFieldMutation: reflection past final. The silent row records reads of a
            //     field that is never mutated, so the detector sees traffic and decides.

            new RecordingSubject("recorded_finalField_mutatedReflectively", JDK,
                    "java.lang.reflect.Field",
                    DetectorType.FINAL_FIELD_MUTATION, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "a final field is recorded as mutated. Final fields carry a freeze "
                            + "guarantee that the memory model relies on, and writing one after "
                            + "construction voids it: other threads may keep observing the old "
                            + "value indefinitely, with no synchronization able to repair it",
                    IssueSeverity.CRITICAL),

            new RecordingSubject("recorded_finalField_onlyRead", JDK,
                    "java.lang.reflect.Field",
                    DetectorType.FINAL_FIELD_MUTATION, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same field read by every thread and never written, which is what final "
                            + "fields are for and is safe without any synchronization at all. "
                            + "The detector sees the traffic and reports nothing, which is the "
                            + "decision the row is testing"),

            // --- PublicLockExposure: synchronizing on an object your API also hands out means
            //     any caller can take your lock. The pair differs by whether the published
            //     object is the one being locked.

            new RecordingSubject("recorded_lock_publishedThroughTheApi", JDK,
                    "java.lang.Object",
                    DetectorType.PUBLIC_LOCK_EXPOSURE, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "the object being synchronized on is also handed out by an accessor. Any "
                            + "caller can then hold your lock for as long as it likes, and "
                            + "neither side can see the other's locking in review",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_lock_keptPrivate", JDK,
                    "java.lang.Object",
                    DetectorType.PUBLIC_LOCK_EXPOSURE, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same two calls with the published object being a value the class "
                            + "returns rather than the monitor it holds. Publishing something "
                            + "is not the defect; publishing the thing you lock on is"),

            // --- The synchronizer family. Four java.util.concurrent coordinators whose
            //     detectors all ask the same question - did the protocol complete, or did it
            //     end in the state the class documents as terminal - so each pair records a
            //     completed cycle against an abandoned one.

            new RecordingSubject("recorded_cyclicBarrier_awaitedWhileBroken", JDK,
                    "java.util.concurrent.CyclicBarrier",
                    DetectorType.CYCLIC_BARRIER, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "every party awaits a barrier that a timed-out await broke and nobody reset, "
                            + "catches BrokenBarrierException and awaits it again, so the retry "
                            + "fails at once too. The detector asks the barrier's isBroken() at "
                            + "each await and reports the party coming back to a barrier it "
                            + "already saw broken with no reset in between (#665); a recorded "
                            + "break on its own is not the finding, because breaking a barrier is "
                            + "how its parties are cancelled (#584)",
                    IssueSeverity.CRITICAL),

            new RecordingSubject("recorded_cyclicBarrier_awaitedAgainNextRound", JDK,
                    "java.util.concurrent.CyclicBarrier",
                    DetectorType.CYCLIC_BARRIER, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "every body awaits the shared broken barrier once, catches "
                            + "BrokenBarrierException and leaves; the reuse is the next round's "
                            + "body doing the same with no reset in between. Each body is a fresh "
                            + "virtual thread, so no thread ever comes back: the party that does "
                            + "is the runner's worker slot, which the detector keys parties on "
                            + "for virtual threads (#693). cancelledAndDropped makes the same "
                            + "three calls on a barrier that does not outlive the body and stays "
                            + "silent",
                    IssueSeverity.CRITICAL),

            new RecordingSubject("recorded_cyclicBarrier_completedItsCycle", JDK,
                    "java.util.concurrent.CyclicBarrier",
                    DetectorType.CYCLIC_BARRIER, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same barrier recorded through a whole cycle - arrival, await, "
                            + "completion - and never broken. That is the ordinary use, and it "
                            + "is what the harness itself does on every round"),

            new RecordingSubject("recorded_cyclicBarrier_resetAfterABreak", JDK,
                    "java.util.concurrent.CyclicBarrier",
                    DetectorType.CYCLIC_BARRIER, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "a party awaits a barrier a timed-out await broke, catches "
                            + "BrokenBarrierException and calls reset(), which is the reuse "
                            + "report's own fix. The await really found the barrier broken; the "
                            + "recorded reset recovers it, and the barrier is whole for the next "
                            + "body (#662)"),

            new RecordingSubject("recorded_cyclicBarrier_cancelledAndDropped", JDK,
                    "java.util.concurrent.CyclicBarrier",
                    DetectorType.CYCLIC_BARRIER, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the loud row's calls on a barrier broken to cancel its parties: the late "
                            + "party awaits it once, catches BrokenBarrierException and drops it "
                            + "with no reset. That is correct cancellation, and one arrival at a "
                            + "broken barrier is not reuse (#665)"),

            new RecordingSubject("recorded_cyclicBarrier_partyLeftShortUntimed", JDK,
                    "java.util.concurrent.CyclicBarrier",
                    DetectorType.CYCLIC_BARRIER, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "one party of a two-party barrier parks in an untimed await() nobody else "
                            + "joins, so it is still parked at analysis with no way to leave. The "
                            + "detector reads the recording thread's state and the frame under "
                            + "CyclicBarrier.dowait, not the barrier (#631)",
                    IssueSeverity.CRITICAL),

            new RecordingSubject("recorded_cyclicBarrier_partyLeftShortTimed", JDK,
                    "java.util.concurrent.CyclicBarrier",
                    DetectorType.CYCLIC_BARRIER, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same shortfall with the party in await(timeout, unit): still parked "
                            + "at analysis, but a bounded wait ends by itself and breaks the "
                            + "barrier for every party, which is the stranded report's own fix "
                            + "(#631)"),

            new RecordingSubject("recorded_reentrantLock_holdLeftTaken", JDK,
                    "java.util.concurrent.locks.ReentrantLock",
                    DetectorType.REENTRANT_LOCK, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "a worker re-enters the lock and releases it once, so a hold is still taken "
                            + "when the bodies are done and every later lock() would park for "
                            + "good. The recorded acquire and release pair balances; the lock "
                            + "itself is the evidence. A recorded tryLock timeout is no longer "
                            + "this row, because backing off on one is correct (#589)",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_reentrantLock_acquiredAndReleased", JDK,
                    "java.util.concurrent.locks.ReentrantLock",
                    DetectorType.REENTRANT_LOCK, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "a second lock taken with lock() and released by every thread, contended "
                            + "but never left held and with no timeout recorded, which is what "
                            + "most locks in most programs do"),

            new RecordingSubject("recorded_phaser_terminated", JDK,
                    "java.util.concurrent.Phaser",
                    DetectorType.PHASER, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "a party arrives at a phaser whose only party already arrived and "
                            + "deregistered. That terminated it, so the arrival returns a "
                            + "negative phase rather than blocking, and the party is silently no "
                            + "longer synchronizing with anyone. Since #587 termination alone is "
                            + "not the finding; the late arrival is",
                    IssueSeverity.CRITICAL),

            new RecordingSubject("recorded_phaser_advancedThroughItsPhase", JDK,
                    "java.util.concurrent.Phaser",
                    DetectorType.PHASER, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "a phaser's one party arriving and completing a phase, which is the cycle "
                            + "it exists for. The pair separates on whether a party arrived "
                            + "after the party count had already reached zero"),

            new RecordingSubject("recorded_exchanger_leftWithNoPartner", JDK,
                    "java.util.concurrent.Exchanger",
                    DetectorType.EXCHANGER, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "an exchange is recorded as entered and nothing ever ends it: no completion, "
                            + "no timeout, no interrupt. An Exchanger needs exactly two threads to "
                            + "meet, and the one that arrived is still inside exchange() waiting "
                            + "for a partner that is not coming. A recorded timeout is no longer "
                            + "this row, because a timed exchange that handles it has left (#585)",
                    IssueSeverity.CRITICAL),
            new RecordingSubject("recorded_exchanger_exchangedNothing", JDK,
                    "java.util.concurrent.Exchanger",
                    DetectorType.EXCHANGER, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the rendezvous completes with a null payload, which the JDK permits: "
                            + "exchange(null) is legal and a payload-free handoff is how an "
                            + "Exchanger is used as a pure rendezvous, where the meeting is the "
                            + "synchronisation and what crossed is nobody's business. This row "
                            + "used to be MUST_FIRE on a rationale that described the mechanism "
                            + "without arguing it was a defect (#521)"),

            new RecordingSubject("recorded_exchanger_exchangedAPayload", JDK,
                    "java.util.concurrent.Exchanger",
                    DetectorType.EXCHANGER, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same rendezvous recorded start to finish with a real payload. With "
                            + "the null row now silent too, both payload shapes are correct and "
                            + "the pair separates on how the threads met rather than on what "
                            + "crossed, which is the axis the detector actually models"),

            new RecordingSubject("recorded_condition_awaitedWithNoSignal", JDK,
                    "java.util.concurrent.locks.Condition",
                    DetectorType.CONDITION_VARIABLES, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "a consumer parks in await() on a condition registered with its lock and "
                            + "its predicate, and threads put work on its queue and then signal a "
                            + "different condition. At analysis the lock's wait queue shows the "
                            + "waiter still parked while its predicate holds, with no thread "
                            + "queued on the lock (#592, #618, #661)",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_condition_consumerIdleOnAnEmptyQueue", JDK,
                    "java.util.concurrent.locks.Condition",
                    DetectorType.CONDITION_VARIABLES, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same consumer registered the same way, parked on a queue nobody puts "
                            + "work on while threads signal a different condition. Parked with its "
                            + "predicate false is an idle consumer, which is correct code (#643); "
                            + "the pair separates on whether the work the waiter waits for arrived "
                            + "(#661)"),

            new RecordingSubject("recorded_condition_awaitedAndSignalled", JDK,
                    "java.util.concurrent.locks.Condition",
                    DetectorType.CONDITION_VARIABLES, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same waiter on a condition registered with its lock, correctly signalled "
                            + "and joined before analysis, so the lock shows no thread parked "
                            + "(#592, #618)"),

            // --- The value-lifecycle family: three detectors that ask whether a value was
            //     produced before it was consumed. Each silent row uses a key unique to its
            //     invocation, because the detectors accumulate across the whole run and a
            //     shared key would let one body's calls answer for another's.

            new RecordingSubject("recorded_aba_premiseReadBeforeAnotherThreadsToggle", JDK,
                    "java.util.concurrent.atomic.AtomicReference",
                    DetectorType.ABA_PROBLEM, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "a thread reads A from an AtomicReference, another thread swings it A to B "
                            + "and back to A, and the first thread's compareAndSet(A, C) then "
                            + "succeeds. A compare-and-set that only checks the value cannot "
                            + "tell that state from one that never moved, so it succeeds on a "
                            + "stale premise - the hazard that stamped and marked references "
                            + "exist to close",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_aba_premiseReadAfterAnotherThreadsToggle", JDK,
                    "java.util.concurrent.atomic.AtomicReference",
                    DetectorType.ABA_PROBLEM, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same toggle and the same compareAndSet(A, C), but the read it expects "
                            + "is taken after the toggle finished, so the premise is fresh. The "
                            + "value still went A to B to A; the pair separates on whether a "
                            + "compare-and-set held a read from before it"),

            new RecordingSubject("recorded_stableValue_readBeforeItWasSet", JDK,
                    "java.lang.Object",
                    DetectorType.STABLE_VALUE_MISUSE, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "a read is recorded against a stable value nothing has set. A "
                            + "write-once holder read before its write hands back the "
                            + "uninitialised state, and because the holder is meant to be set "
                            + "exactly once there is no later correction",
                    IssueSeverity.CRITICAL),

            new RecordingSubject("recorded_stableValue_setBeforeItWasRead", JDK,
                    "java.lang.Object",
                    DetectorType.STABLE_VALUE_MISUSE, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same read with its set recorded first, on a name unique to this "
                            + "invocation so no other body's calls can satisfy it. Set then "
                            + "read is the entire contract of a write-once holder"),

            new RecordingSubject("recorded_varHandle_plainGetThenPlainSet", JDK,
                    "java.lang.invoke.VarHandle",
                    DetectorType.VAR_HANDLE_NON_ATOMIC_UPDATE, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "a plain get and a plain set are recorded as one read-modify-write. "
                            + "VarHandle gives the caller the ordering they ask for and plain "
                            + "mode asks for none, so the pair is neither atomic nor ordered - "
                            + "the same lost update as the AtomicInteger row, one level down",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_varHandle_volatileGetThenAtomicUpdate", JDK,
                    "java.lang.invoke.VarHandle",
                    DetectorType.VAR_HANDLE_NON_ATOMIC_UPDATE, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same read-modify-write expressed as a volatile read and a recorded "
                            + "atomic update, which is what the class provides "
                            + "compareAndSet and getAndAdd for. The pair separates on the "
                            + "access mode the caller chose"),

            // --- The thread-lifecycle family. Four detectors that watch what happens to a
            //     thread rather than to shared data: was it joined, did anyone hear it die, will
            //     it hold the JVM open, and was it built with the hygiene a pool needs.

            new RecordingSubject("recorded_thread_startedAndNeverJoined", JDK,
                    "java.lang.Thread",
                    DetectorType.THREAD_LEAKS, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "a thread is started and its end is never recorded, so it is still running "
                            + "when the run is analysed. A test that leaks a thread per "
                            + "execution leaks them by the hundred, and each one holds "
                            + "everything it referenced",
                    IssueSeverity.MEDIUM),

            new RecordingSubject("recorded_thread_startedAndJoined", JDK,
                    "java.lang.Thread",
                    DetectorType.THREAD_LEAKS, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same thread started, joined and recorded as ended before the body "
                            + "returns. The join makes the outcome structural rather than a bet "
                            + "on the thread finishing in time"),

            new RecordingSubject("recorded_thread_diedWithNoHandler", JDK,
                    "java.lang.Thread",
                    DetectorType.UNCAUGHT_EXCEPTION_HANDLER, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "a thread with no uncaught-exception handler is recorded as dying from one. "
                            + "The default handler prints to stderr and the thread disappears, "
                            + "so in a build log the work simply stops happening with nothing "
                            + "failing",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_thread_diedWithAHandlerInstalled", JDK,
                    "java.lang.Thread",
                    DetectorType.UNCAUGHT_EXCEPTION_HANDLER, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the identical death on a thread that had a handler set before it started, "
                            + "which is the fix. The pair separates on whether anything was "
                            + "installed to hear the failure"),

            new RecordingSubject("recorded_thread_leftNonDaemonAndAlive", JDK,
                    "java.lang.Thread",
                    DetectorType.DAEMON_THREAD_HYGIENE, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "a live non-daemon thread is recorded, which is the one kind that keeps the "
                            + "JVM from exiting. A suite that leaves one behind hangs after the "
                            + "last test passes, and the symptom is a build that never returns "
                            + "rather than a failure",
                    IssueSeverity.MEDIUM),

            new RecordingSubject("recorded_thread_leftAsADaemon", JDK,
                    "java.lang.Thread",
                    DetectorType.DAEMON_THREAD_HYGIENE, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same recording of a thread marked daemon, which the JVM abandons at "
                            + "exit. Background threads are ordinary and reporting them would "
                            + "be noise on every scheduler and pool in a program"),

            new RecordingSubject("recorded_threadFactory_producedARawThread", JDK,
                    "java.util.concurrent.ThreadFactory",
                    DetectorType.THREAD_FACTORY, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "the factory hands back a thread with the default name, no daemon flag and "
                            + "no handler. Each of those is a diagnosis problem later: an "
                            + "unnamed thread in a dump says nothing about which pool it "
                            + "belongs to, and a missing handler loses its failures",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_threadFactory_producedAConfiguredThread", JDK,
                    "java.util.concurrent.ThreadFactory",
                    DetectorType.THREAD_FACTORY, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same factory recorded producing a named daemon thread with a handler "
                            + "installed, which is what a production factory does. The pair "
                            + "separates on how the thread was configured and nothing else"),

            // --- Per-thread state that outlives its task. Two detectors on the shape the MDC
            //     and ThreadLocal-leak pairs approach from other angles.

            new RecordingSubject("recorded_inheritableThreadLocal_setOnAPoolThread", JDK,
                    "java.lang.InheritableThreadLocal",
                    DetectorType.INHERITABLE_THREAD_LOCAL, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "an inheritable thread-local is set on a declared pool thread. Inheritance "
                            + "happens at thread creation, so a pooled worker keeps whatever the "
                            + "thread that created the pool had - and every task after it reads "
                            + "a value belonging to somebody else",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_inheritableThreadLocal_confinedToItsOwnName", JDK,
                    "java.lang.InheritableThreadLocal",
                    DetectorType.INHERITABLE_THREAD_LOCAL, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same set and get against a name private to each thread, on a thread "
                            + "never declared as pooled. Nothing is inherited and nothing is "
                            + "shared, which is what correct use of the class looks like"),

            new RecordingSubject("recorded_threadLocal_readAcrossATaskBoundary", JDK,
                    "java.lang.ThreadLocal",
                    DetectorType.THREAD_LOCAL_CONTAMINATION, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "a value is set during one task and still readable in the next task on the "
                            + "same thread. On a pool that is one request reading another "
                            + "request's context, which is a correctness problem long before it "
                            + "is a leak",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_threadLocal_clearedAtTheTaskBoundary", JDK,
                    "java.lang.ThreadLocal",
                    DetectorType.THREAD_LOCAL_CONTAMINATION, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same two tasks with the value read inside the task that set it and "
                            + "absent in the next, which is what a cleanup at the boundary "
                            + "produces. The pair separates on what crossed the boundary"),

            // --- Three more instance-sharing pairs, each the confinement shape on a different
            //     kind of state: a lambda's captured variable, and two generators.

            new RecordingSubject("recorded_lambda_readModifyWriteWithNoGuard", JDK,
                    "java.lang.Runnable",
                    DetectorType.LAMBDA_LOST_UPDATE, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "six threads read, modify and write one lambda's captured variable with no "
                            + "lock declared. Two threads that read the same value both write "
                            + "back one increment, so an update is lost with nothing thrown",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_lambda_readModifyWriteUnderAGuard", JDK,
                    "java.lang.Runnable",
                    DetectorType.LAMBDA_LOST_UPDATE, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the identical sequence with the guard the caller held passed to the "
                            + "detector, so it can see that every read-modify-write was "
                            + "serialised. A finding here would report a correctly locked "
                            + "counter"),

            new RecordingSubject("recorded_record_sharedWithAMutableComponent", JDK,
                    "java.lang.Record",
                    DetectorType.RECORD_MUTABLE_COMPONENT_LEAK, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "a record holding a mutable list is shared across threads. Records make the "
                            + "reference final and say nothing about what it points at, so the "
                            + "shallow immutability reads as a safety guarantee it does not "
                            + "provide",
                    IssueSeverity.MEDIUM),

            new RecordingSubject("recorded_record_sharedWithImmutableComponents", JDK,
                    "java.lang.Record",
                    DetectorType.RECORD_MUTABLE_COMPONENT_LEAK, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same sharing of a record whose components are all immutable, which is "
                            + "deeply immutable and safe to publish anywhere. Reporting it "
                            + "would report the single best reason to use a record"),

            new RecordingSubject("recorded_splittableRandom_sharedAcrossThreads", JDK,
                    "java.util.SplittableRandom",
                    DetectorType.SHARED_SPLITTABLE_RANDOM, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "one generator is recorded from six threads. SplittableRandom's javadoc "
                            + "says instances are not thread-safe and that split() exists "
                            + "precisely so each thread can have its own; sharing one corrupts "
                            + "the sequence rather than merely contending on it",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_splittableRandom_splitPerThread", JDK,
                    "java.util.SplittableRandom",
                    DetectorType.SHARED_SPLITTABLE_RANDOM, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "a generator per thread, which is what split() is for and what the javadoc "
                            + "prescribes. No instance is ever recorded from a second thread"),

            // --- The CompletableFuture protocol family. Five detectors on the same class, each
            //     asking a different question about what the caller did with the pipeline: was
            //     it terminated, was the pool it blocks on the one running it, did two threads
            //     race to complete it, did a cancel reach the work, was the combinator awaited.

            new RecordingSubject("recorded_completableFuture_chainNeverJoined", JDK,
                    "java.util.concurrent.CompletableFuture",
                    DetectorType.COMPLETABLEFUTURE_CHAIN, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "a future is created and the chain is never joined or handled, so nothing "
                            + "ever observes its outcome. A dangling chain runs for its side "
                            + "effects and reports neither result nor failure to anyone",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_completableFuture_chainJoined", JDK,
                    "java.util.concurrent.CompletableFuture",
                    DetectorType.COMPLETABLEFUTURE_CHAIN, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same creation followed by a recorded chain operation, a handler and a "
                            + "join, which is the whole pipeline terminated properly. The pair "
                            + "separates on whether anything consumed the end of the chain"),

            new RecordingSubject("recorded_completableFuture_blockedOnItsOwnPool", JDK,
                    "java.util.concurrent.CompletableFuture",
                    DetectorType.CF_COMMON_POOL_BLOCKING, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "a join is recorded on a future submitted to the common pool, from a thread "
                            + "the common pool runs. Blocking a pool worker on work that pool "
                            + "must run is how the default parallelism deadlocks under load, "
                            + "and the common pool is one per JVM so the blast radius is the "
                            + "whole process",
                    IssueSeverity.MEDIUM),

            new RecordingSubject("recorded_completableFuture_blockedOnADedicatedPool", JDK,
                    "java.util.concurrent.CompletableFuture",
                    DetectorType.CF_COMMON_POOL_BLOCKING, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the identical join recorded against a future that was never submitted to "
                            + "the common pool. Waiting on work running somewhere else is the "
                            + "ordinary case and is what the fix looks like"),

            new RecordingSubject("recorded_completableFuture_completedTwice", JDK,
                    "java.util.concurrent.CompletableFuture",
                    DetectorType.COMPLETABLE_FUTURE_COMPLETION_RACE, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "two threads attempt to complete one future and one of them loses. "
                            + "complete() returning false is the loser being told its value was "
                            + "discarded, and a caller that ignores that return has silently "
                            + "dropped a result somebody computed",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_completableFuture_completedOnce", JDK,
                    "java.util.concurrent.CompletableFuture",
                    DetectorType.COMPLETABLE_FUTURE_COMPLETION_RACE, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "one completion attempt per future, each on a future private to its own "
                            + "invocation, so no attempt ever loses. That is what a pipeline "
                            + "with a single producer looks like"),

            new RecordingSubject("recorded_completableFuture_cancelDidNotReachTheWork", JDK,
                    "java.util.concurrent.CompletableFuture",
                    DetectorType.COMPLETABLE_FUTURE_CANCELLATION_PROPAGATION, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "a cancel is recorded with mayInterruptIfRunning, which CompletableFuture's "
                            + "javadoc says has no effect on it. The caller believes the work "
                            + "stopped, the future completes exceptionally, and the task carries "
                            + "on holding whatever it holds",
                    IssueSeverity.MEDIUM),

            new RecordingSubject("recorded_completableFuture_cancelAfterTheWorkFinished", JDK,
                    "java.util.concurrent.CompletableFuture",
                    DetectorType.COMPLETABLE_FUTURE_CANCELLATION_PROPAGATION, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "a pipeline whose stage is recorded as started and completed before a cancel "
                            + "that asks for no interruption. Nothing was running to be left "
                            + "running, which is the case the detector must not report"),

            new RecordingSubject("recorded_completableFuture_combinatorNeverAwaited", JDK,
                    "java.util.concurrent.CompletableFuture",
                    DetectorType.COMPLETABLE_FUTURE_COMBINATOR_MISUSE, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "an allOf is recorded and never awaited. The combinator's whole purpose is "
                            + "to be waited on; building one and dropping it means the "
                            + "constituents' failures go the way of any unobserved future",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_completableFuture_combinatorAwaited", JDK,
                    "java.util.concurrent.CompletableFuture",
                    DetectorType.COMPLETABLE_FUTURE_COMBINATOR_MISUSE, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same combinator with both constituents recorded as completed and a "
                            + "recorded await, which is the complete pattern. The pair "
                            + "separates on whether anybody waited"),

            // --- The structured-concurrency family. Four detectors on scope lifecycles, where
            //     the whole promise of the construct is that a scope does not outlive its
            //     subtasks - so each pair is a lifecycle that closed properly against one that
            //     skipped a step.

            new RecordingSubject("recorded_scope_closedWithoutForking", JDK,
                    "java.util.concurrent.StructuredTaskScope",
                    DetectorType.STRUCTURED_CONCURRENCY, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "a scope is opened and closed with nothing forked into it. A scope with no "
                            + "subtasks is either dead code or a fork that was lost in a "
                            + "refactor, and the construct's cost buys nothing either way",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_scope_forkedJoinedAndRead", JDK,
                    "java.util.concurrent.StructuredTaskScope",
                    DetectorType.STRUCTURED_CONCURRENCY, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same scope with a subtask forked, joined and its result read before "
                            + "the close, which is the lifecycle the API is shaped around"),

            new RecordingSubject("recorded_taskScope_closedWithoutJoining", JDK,
                    "java.util.concurrent.StructuredTaskScope",
                    DetectorType.STRUCTURED_TASK_SCOPE_MISUSE, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "a subtask is forked and the scope closes without a join. Close cancels "
                            + "whatever is still running, so the work is abandoned mid-flight "
                            + "and its result and its failure are both discarded",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_taskScope_joinedBeforeClosing", JDK,
                    "java.util.concurrent.StructuredTaskScope",
                    DetectorType.STRUCTURED_TASK_SCOPE_MISUSE, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the identical fork with the join and the result read in between, which is "
                            + "what the try-with-resources shape in every example does"),

            new RecordingSubject("recorded_scopeJoiner_boundToTwoScopes", JDK,
                    "java.util.concurrent.StructuredTaskScope",
                    DetectorType.SCOPE_JOINER_MISUSE, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "one joiner is bound to two different scopes. A joiner accumulates the "
                            + "results of the scope it belongs to, so reusing one merges two "
                            + "scopes' outcomes into state neither scope's owner expects",
                    IssueSeverity.CRITICAL),

            new RecordingSubject("recorded_scopeJoiner_boundToOneScope", JDK,
                    "java.util.concurrent.StructuredTaskScope",
                    DetectorType.SCOPE_JOINER_MISUSE, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "a joiner per invocation bound to exactly one scope and taken through its "
                            + "whole callback lifecycle on the owning thread. One joiner, one "
                            + "scope is the contract"),

            new RecordingSubject("recorded_scope_configurationSilentlyIgnored", JDK,
                    "java.util.concurrent.StructuredTaskScope",
                    DetectorType.SCOPE_CONFIGURATION_MISUSE, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "the configuration the caller asked for and the one that took effect differ. "
                            + "A scope built with a name and a timeout that are quietly not the "
                            + "ones in force is a debugging trap: the thread dump and the "
                            + "deadline both say something the code does not",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_scope_configurationApplied", JDK,
                    "java.util.concurrent.StructuredTaskScope",
                    DetectorType.SCOPE_CONFIGURATION_MISUSE, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same scope whose effective configuration matches what was requested, "
                            + "on a name unique to the invocation so no two scopes collide. The "
                            + "pair separates on whether the request survived"),

            new RecordingSubject("recorded_scopeResult_readAfterTheScopeClosed", JDK,
                    "java.util.concurrent.StructuredTaskScope",
                    DetectorType.SCOPE_RESULT_ESCAPE, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "a subtask's result handle is read after its scope has closed. The handle is "
                            + "only defined for the scope's lifetime, so a read past the close "
                            + "is the structured-concurrency form of using a closed resource",
                    IssueSeverity.CRITICAL),

            new RecordingSubject("recorded_scopeResult_readBeforeTheScopeClosed", JDK,
                    "java.util.concurrent.StructuredTaskScope",
                    DetectorType.SCOPE_RESULT_ESCAPE, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same handle read after the join completed and before the close, which "
                            + "is the only window the API defines it in. The pair separates on "
                            + "which side of the close the read sits"),

            // --- The harness-model family: detectors whose subject is a value or a lifecycle
            //     the body describes rather than an object it holds. Each pair varies the one
            //     thing the model reads.

            new RecordingSubject("recorded_field_readInconsistentlyAcrossThreads", JDK,
                    "java.lang.Object",
                    DetectorType.VISIBILITY, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "one field identifier is recorded with a different value from every thread. "
                            + "Threads disagreeing about what a field holds is what a visibility "
                            + "failure looks like from the outside, and without a happens-before "
                            + "edge nothing obliges one thread's write to become visible to "
                            + "another. A counter that is meant to change looks the same, which "
                            + "is why the detector reports the observation at FACT",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_field_readConsistentlyAcrossThreads", JDK,
                    "java.lang.Object",
                    DetectorType.VISIBILITY, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same number of recordings of a field every thread sees identically, "
                            + "which is what an effectively-final or properly published value "
                            + "looks like. The pair separates on whether the observations agree"),

            new RecordingSubject("recorded_wait_returnedWithoutANotify", JDK,
                    "java.lang.Object",
                    DetectorType.WAKEUP_ISSUES, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "a wait is recorded as having returned with no notify, and the body goes on "
                            + "without waiting again, which is an if guard taking the spurious "
                            + "wakeup Object.wait's javadoc warns of as the condition. Code that "
                            + "treats the return as the condition acts on a state nobody "
                            + "established",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_wait_returnedWithoutANotify_thenWaitedAgain", JDK,
                    "java.lang.Object",
                    DetectorType.WAKEUP_ISSUES, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same unsignalled return followed by a second wait that a recorded "
                            + "notifyAll ends, which is the while loop re-checking its condition. "
                            + "The pair separates on whether the waiter waited again (#590)"),

            new RecordingSubject("recorded_object_accessedDuringConstruction", JDK,
                    "java.lang.Object",
                    DetectorType.CONSTRUCTOR_SAFETY, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "a constructor registers this with a listener before assigning its field, "
                            + "and the listener reads the object from another thread while the "
                            + "constructor is still running. A reference that escapes its "
                            + "constructor can be seen with its fields unset, which is the one "
                            + "hazard no amount of later synchronization can repair",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_object_accessedAfterConstruction", JDK,
                    "java.lang.Object",
                    DetectorType.CONSTRUCTOR_SAFETY, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same class and the same listener read, registered after the "
                            + "constructor returned. Publishing a fully built object is the rule, "
                            + "and the pair separates on which side of the constructor's return "
                            + "the read falls"),

            new RecordingSubject("recorded_barrier_partiesNeverArrived", JDK,
                    "java.util.concurrent.CyclicBarrier",
                    DetectorType.SYNCHRONIZERS, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "a synchronizer expecting a thousand parties is recorded receiving six. A "
                            + "barrier whose party count is never reached is a permanent stall, "
                            + "and the count is a construction-time constant rather than "
                            + "anything the schedule decides",
                    IssueSeverity.CRITICAL),

            new RecordingSubject("recorded_barrier_partiesArrivedAndAdvanced", JDK,
                    "java.util.concurrent.CyclicBarrier",
                    DetectorType.SYNCHRONIZERS, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "a synchronizer sized to the parties that actually arrive, recorded through "
                            + "an arrival and an advance. That is a barrier doing its job, which "
                            + "is what the harness itself does on every round"),

            new RecordingSubject("recorded_threadPool_rejectedItsWork", JDK,
                    "java.util.concurrent.ThreadPoolExecutor",
                    DetectorType.THREAD_POOL, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "a task is recorded as rejected by a pool of one with a queue of one. "
                            + "Rejection is the pool telling the caller it dropped work, and the "
                            + "default policy throws it back at whoever submitted - a failure "
                            + "that arrives far from the sizing decision that caused it",
                    IssueSeverity.MEDIUM),

            new RecordingSubject("recorded_threadPool_ranItsWorkToCompletion", JDK,
                    "java.util.concurrent.ThreadPoolExecutor",
                    DetectorType.THREAD_POOL, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "a pool sized for the work, recorded through submit, start and completion "
                            + "with nothing rejected. The pair separates on whether the pool "
                            + "could absorb what it was given"),

            new RecordingSubject("recorded_pipelineStage_publishedAndDropped", JDK,
                    "java.lang.Object",
                    DetectorType.ASYNC_PIPELINE, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "events are published to a stage and none is ever recorded as processed. An "
                            + "asynchronous stage that accepts work and never accounts for it is "
                            + "how a queue silently becomes a bin, and the counts say so without "
                            + "any timing being involved",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_pipelineStage_publishedAndProcessed", JDK,
                    "java.lang.Object",
                    DetectorType.ASYNC_PIPELINE, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same events published and each one recorded as processed, so the stage "
                            + "balances. The pair separates on whether anything came out the "
                            + "other end"),

            new RecordingSubject("recorded_readWriteLock_starvedItsWriter", JDK,
                    "java.util.concurrent.locks.ReentrantReadWriteLock",
                    DetectorType.READ_WRITE_LOCK_FAIRNESS, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "readers outnumber writers by an order of magnitude on one lock. A "
                            + "non-fair read-write lock lets a steady stream of readers keep a "
                            + "writer waiting indefinitely, which is a liveness problem the "
                            + "lock is behaving correctly to produce",
                    IssueSeverity.MEDIUM),

            new RecordingSubject("recorded_readWriteLock_balancedItsTraffic", JDK,
                    "java.util.concurrent.locks.ReentrantReadWriteLock",
                    DetectorType.READ_WRITE_LOCK_FAIRNESS, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same lock recorded with reads and writes in balance and each released. "
                            + "The pair separates on the ratio, which is the only thing this "
                            + "model reads"),

            new RecordingSubject("recorded_lazyInit_initialisedMoreThanOnce", JDK,
                    "java.lang.Object",
                    DetectorType.LAZY_INIT_RACE, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "one field is recorded as initialised by every thread that looked at it. A "
                            + "lazy initialisation that runs more than once has produced more "
                            + "than one instance of something meant to be unique, and every "
                            + "caller after the first holds an object the others do not",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_lazyInit_initialisedOnce", JDK,
                    "java.lang.Object",
                    DetectorType.LAZY_INIT_RACE, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same field null-checked by every thread and initialised exactly once "
                            + "for the run. One initialisation is what the idiom exists to "
                            + "guarantee, and the pair separates on the count"),

            new RecordingSubject("recorded_lock_contendedRepeatedly", JDK,
                    "java.lang.Object",
                    DetectorType.LOCK_CONTENTION, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "a monitor is recorded as contended on most of the attempts to take it. "
                            + "Nothing is broken; the lock is the bottleneck, which is a "
                            + "throughput fact the caller cannot see from the code and can only "
                            + "get from a count",
                    IssueSeverity.MEDIUM),

            new RecordingSubject("recorded_lock_takenWithoutContention", JDK,
                    "java.lang.Object",
                    DetectorType.LOCK_CONTENTION, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same attempts recorded as acquired and released with no contention at "
                            + "all, which is what most locks in most programs do. The pair "
                            + "separates on the recorded contention and nothing else"),

            new RecordingSubject("recorded_field_writtenByEveryThreadUnguarded", JDK,
                    "java.lang.Object",
                    DetectorType.RACE_CONDITIONS, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "six threads write one object's field with nothing held. That is the "
                            + "textbook data race, and the detector's lock fingerprint is empty "
                            + "across every recorded access because there is no lock to see",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_field_writtenUnderTheObjectsMonitor", JDK,
                    "java.lang.Object",
                    DetectorType.RACE_CONDITIONS, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the identical writes made inside synchronized on the object itself, which "
                            + "Thread.holdsLock sees with no agent attached. A finding here "
                            + "would report the most common correct guarding idiom in Java"),

            // --- The virtual-thread family. Four detectors whose model turns on the fact that
            //     virtual threads are cheap and numerous, so a per-thread cost that was
            //     negligible for a pool of eight is ruinous for a million. The lane's workers
            //     are platform threads, so these rows hand the detectors virtual threads built
            //     with Thread.ofVirtual().unstarted(...) - the detectors read isVirtual(),
            //     threadId() and getName(), all of which an unstarted instance answers.

            new RecordingSubject("recorded_virtualThread_contextNeverRemoved", JDK,
                    "java.lang.Thread",
                    DetectorType.VIRTUAL_THREAD_CONTEXT_LEAKS, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "an inheritable thread-local is set on a virtual thread and never removed. "
                            + "Every virtual thread inherits a copy, and where a pool has eight "
                            + "carriers an application may have a million virtual threads, so "
                            + "the per-thread cost that was invisible becomes the heap",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_virtualThread_contextRemoved", JDK,
                    "java.lang.Thread",
                    DetectorType.VIRTUAL_THREAD_CONTEXT_LEAKS, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same set, not inheritable and with a recorded removal behind it. The "
                            + "pair separates on inheritance and cleanup rather than on the "
                            + "kind of thread, which both rows hold constant"),

            new RecordingSubject("recorded_virtualThreads_saturatedAScarceResource", JDK,
                    "java.lang.Thread",
                    DetectorType.VIRTUAL_THREAD_RESOURCE_SATURATION, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "more virtual threads queue for a resource of capacity one than it can ever "
                            + "serve, and none is recorded as acquiring it. Virtual threads make "
                            + "it trivial to have more work in flight than the pool behind it, "
                            + "and the queue forms where nobody is looking",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_virtualThreads_withinResourceCapacity", JDK,
                    "java.lang.Thread",
                    DetectorType.VIRTUAL_THREAD_RESOURCE_SATURATION, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same acquisitions against a resource sized well above the demand, each "
                            + "recorded as acquired. The pair separates on whether the resource "
                            + "could serve what asked for it"),

            new RecordingSubject("recorded_virtualThreads_serialisedOnAMonitor", JDK,
                    "java.lang.Object",
                    DetectorType.VIRTUAL_THREAD_MONITOR_SERIALIZATION, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "four virtual threads are recorded entering one monitor and none acquiring "
                            + "it. A monitor serialises whatever asks for it, so a construct "
                            + "whose whole point is unbounded concurrency ends up single-file - "
                            + "and on older runtimes each blocked virtual thread also pinned its "
                            + "carrier. A throughput note, so MEDIUM and ADVISORY",
                    IssueSeverity.MEDIUM),

            new RecordingSubject("recorded_virtualThreads_acquiredTheMonitor", JDK,
                    "java.lang.Object",
                    DetectorType.VIRTUAL_THREAD_MONITOR_SERIALIZATION, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same entries recorded as acquired, on a monitor private to each "
                            + "invocation. Taking a lock is not a defect; a queue of threads "
                            + "waiting on one is what the detector reports"),

            new RecordingSubject("recorded_threadLocalCache_onePerVirtualThread", JDK,
                    "java.lang.ThreadLocal",
                    DetectorType.THREAD_LOCAL_CACHE_DEGRADATION, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "a distinct cached instance is recorded for each virtual thread. A "
                            + "ThreadLocal cache is an optimisation that assumes few, long-lived "
                            + "threads; with virtual threads it becomes an allocation per task, "
                            + "which is the opposite of what it was added for",
                    IssueSeverity.MEDIUM),

            new RecordingSubject("recorded_threadLocalCache_sharedAcrossVirtualThreads", JDK,
                    "java.lang.ThreadLocal",
                    DetectorType.THREAD_LOCAL_CACHE_DEGRADATION, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same number of recordings of one shared instance, which is what the "
                            + "cache looks like once it stops being per-thread. The pair "
                            + "separates on how many instances the threads saw"),

            new RecordingSubject("recorded_executor_pooledItsVirtualThreads", JDK,
                    "java.util.concurrent.ThreadPoolExecutor",
                    DetectorType.VIRTUAL_THREAD_POOLING, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "a fixed pool is built over a virtual-thread factory. Pooling exists to "
                            + "amortise the cost of creating a thread, and creating a virtual "
                            + "thread costs almost nothing - so the pool caps the concurrency "
                            + "the caller was trying to buy and gives nothing back",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_executor_pooledItsPlatformThreads", JDK,
                    "java.util.concurrent.ThreadPoolExecutor",
                    DetectorType.VIRTUAL_THREAD_POOLING, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the identical pool over the default platform-thread factory, which is what "
                            + "pooling is for. The pair separates on the factory alone"),

            // --- The foreign-memory pair: both rows share one segment, and what differs is
            //     whether two threads write the same bytes.

            new RecordingSubject("recorded_memorySegment_overlappingWrites", JDK,
                    "java.lang.foreign.MemorySegment",
                    DetectorType.SHARED_MEMORY_SEGMENT_RACE, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "six threads write the same eight bytes of one segment with no guard named. "
                            + "Off-heap memory has none of the protections the heap has: there "
                            + "is no header, no type check and no bounds beyond what the caller "
                            + "declares, so a torn write is simply wrong bytes",
                    IssueSeverity.MEDIUM),

            new RecordingSubject("recorded_memorySegment_disjointWrites", JDK,
                    "java.lang.foreign.MemorySegment",
                    DetectorType.SHARED_MEMORY_SEGMENT_RACE, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same segment written by the same six threads at offsets that cannot "
                            + "overlap. Slicing one segment into per-thread ranges is the "
                            + "documented way to share it, and a finding here would report the "
                            + "fix"),

            new RecordingSubject("recorded_confinedArena_accessedFromAnotherThread", JDK,
                    "java.lang.foreign.Arena",
                    DetectorType.CONFINED_ARENA_THREAD_ESCAPE, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "a segment allocated in a confined arena is accessed by threads other than "
                            + "the one that opened it. Confinement is the arena's entire safety "
                            + "argument - it is what lets it skip synchronization - so an escape "
                            + "removes the guarantee rather than merely bending it",
                    IssueSeverity.MEDIUM),

            new RecordingSubject("recorded_confinedArena_accessedByItsOwner", JDK,
                    "java.lang.foreign.Arena",
                    DetectorType.CONFINED_ARENA_THREAD_ESCAPE, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "an arena per invocation, allocated, accessed and closed by the one thread "
                            + "that opened it. That is confinement observed, and it is the "
                            + "pattern the API is built around"),

            // --- Three stragglers, each a value-lifecycle model like wave 10's.

            new RecordingSubject("recorded_gatherer_parallelWithoutACombiner", JDK,
                    "java.util.stream.Gatherer",
                    DetectorType.GATHERER_CONCURRENCY_MISUSE, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "a gatherer is declared parallel with no combiner and then integrated from "
                            + "six threads. A parallel pipeline splits the work and has nothing "
                            + "to merge the halves with, so the integrator's state is shared "
                            + "rather than combined",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_gatherer_parallelWithACombiner", JDK,
                    "java.util.stream.Gatherer",
                    DetectorType.GATHERER_CONCURRENCY_MISUSE, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same six threads integrating a parallel gatherer that has a combiner, "
                            + "each against its own state from the initializer. That is "
                            + "Gatherer.of(initializer, integrator, combiner, finisher) running "
                            + "as designed: every segment is confined to its own state and the "
                            + "combiner merges them"),

            new RecordingSubject("recorded_lazyConstant_computedToNothing", JDK,
                    "java.lang.Object",
                    DetectorType.LAZY_CONSTANT_MISUSE, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "a lazy constant's computation is recorded as finishing with no value. A "
                            + "holder meant to be computed once and kept forever that ends up "
                            + "holding nothing will be recomputed by every later caller, which "
                            + "is the opposite of the memoisation it was written for",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_lazyConstant_computedToAValue", JDK,
                    "java.lang.Object",
                    DetectorType.LAZY_CONSTANT_MISUSE, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same computation ending with a value, on a name unique to the "
                            + "invocation so no other body's calls can answer for it. The pair "
                            + "separates on what the computation produced"),

            new RecordingSubject("recorded_lazyCollection_entryComputedToNothing", JDK,
                    "java.util.Map",
                    DetectorType.LAZY_COLLECTION_MISUSE, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "a lazily computed entry finishes with no value, so the key stays absent. "
                            + "Every later lookup recomputes it, which turns a cache into a "
                            + "guarantee that the expensive path runs every time",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_lazyCollection_entryComputedToAValue", JDK,
                    "java.util.Map",
                    DetectorType.LAZY_COLLECTION_MISUSE, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same computation producing a value under a key unique to the "
                            + "invocation, which is a cache filling correctly. The pair "
                            + "separates on the value and not on the traffic"),

            // --- The last of the pairable set. Four pool and executor models, two protocol
            //     models, one spin model, one contention model, and the generator the accuracy
            //     eval keeps firing on by design.

            new RecordingSubject("recorded_random_sharedAcrossThreads", JDK,
                    "java.util.Random",
                    DetectorType.SHARED_RANDOM, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "one generator is recorded from six threads. Random is thread-safe, so this "
                            + "is a contention finding rather than a corruption one: its seed is "
                            + "a single CAS every caller retries on, which is why "
                            + "ThreadLocalRandom exists. The row is here because the pair below "
                            + "shows the detector still distinguishes confinement. It is a "
                            + "LOW advisory about correct code, never a verdict",
                    IssueSeverity.LOW),

            new RecordingSubject("recorded_random_confinedToOneThreadEach", JDK,
                    "java.util.Random",
                    DetectorType.SHARED_RANDOM, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "a generator per thread, recorded the same number of times. Nothing contends, "
                            + "so nothing is reported - which is the distinction that matters for "
                            + "a detector whose guarded twin fires by design: it reports "
                            + "contention, and confinement removes the contention"),

            new RecordingSubject("recorded_scheduledExecutor_taskOverranItsPeriod", JDK,
                    "java.util.concurrent.ScheduledExecutorService",
                    DetectorType.SCHEDULED_EXECUTOR, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "a task on a single-threaded scheduler is recorded taking five seconds. The "
                            + "duration is a parameter rather than a measurement, so the row "
                            + "states a slow task rather than waiting for one: on a scheduler of "
                            + "one, a task that overruns delays every task behind it",
                    IssueSeverity.MEDIUM),

            new RecordingSubject("recorded_scheduledExecutor_taskFinishedPromptly", JDK,
                    "java.util.concurrent.ScheduledExecutorService",
                    DetectorType.SCHEDULED_EXECUTOR, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same scheduler recorded through schedule, start and a completion that "
                            + "took milliseconds, then shut down. The pair separates on the "
                            + "duration the body reports, which no clock in the run can move"),

            new RecordingSubject("recorded_forkJoin_forkedWithoutJoining", JDK,
                    "java.util.concurrent.ForkJoinPool",
                    DetectorType.FORK_JOIN_POOL, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "a task is recorded as forked and never joined. Fork-join's whole contract "
                            + "is that every fork is joined: an unjoined task's result is "
                            + "discarded and its exception with it, and the pool cannot help "
                            + "because it does not know the caller stopped caring",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_forkJoin_forkedAndJoined", JDK,
                    "java.util.concurrent.ForkJoinPool",
                    DetectorType.FORK_JOIN_POOL, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same fork with its join recorded behind it and a task time reported. "
                            + "The pair separates on whether the fork was ever collected"),

            new RecordingSubject("recorded_spinLoop_ranWithoutYielding", JDK,
                    "java.lang.Thread",
                    DetectorType.BUSY_WAITING, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "ten thousand loop iterations are recorded before any yield, which is the "
                            + "detector's stated spin threshold. A spin that long is a core held "
                            + "at full power to wait, and it starves whatever it is waiting for "
                            + "on a machine with fewer cores than spinners",
                    IssueSeverity.MEDIUM),

            new RecordingSubject("recorded_spinLoop_yieldedOften", JDK,
                    "java.lang.Thread",
                    DetectorType.BUSY_WAITING, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "a hundred iterations between yields, recorded the same way. Short spins "
                            + "before parking are the normal shape of a lock's fast path, so "
                            + "the pair separates on the iteration count and not on the loop"),

            new RecordingSubject("recorded_httpRequest_sentWithNoResponse", JDK,
                    "java.net.http.HttpClient",
                    DetectorType.HTTP_CLIENT, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "a request is recorded as sent and no response is ever recorded for it. "
                            + "HttpClient is thread-safe and that is not the question: a request "
                            + "in flight that nobody accounts for holds a connection from a "
                            + "bounded pool until it times out, which starves every later call",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_httpRequest_answered", JDK,
                    "java.net.http.HttpClient",
                    DetectorType.HTTP_CLIENT, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same request with its response recorded under the same name, which is "
                            + "unique to the invocation so no two bodies can answer for each "
                            + "other. The pair separates on whether the exchange completed"),

            new RecordingSubject("recorded_atomic_casRetriedUnderContention", JDK,
                    "java.util.concurrent.atomic.AtomicLong",
                    DetectorType.HIGH_CONTENTION_ATOMIC, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "every recorded compare-and-set fails. A failed CAS is work thrown away and "
                            + "retried, so an atomic under this much contention costs more than "
                            + "the lock it replaced - which is what LongAdder exists for. "
                            + "Nothing is incorrect here, which is why the finding is advisory",
                    IssueSeverity.LOW),

            new RecordingSubject("recorded_atomic_casSucceededFirstTime", JDK,
                    "java.util.concurrent.atomic.AtomicLong",
                    DetectorType.HIGH_CONTENTION_ATOMIC, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same number of recorded attempts, every one succeeding, which is an "
                            + "uncontended atomic doing exactly what it is for. The pair "
                            + "separates on the failure ratio the body reports"),

            new RecordingSubject("recorded_executor_taskWaitedOnItsSibling", JDK,
                    "java.util.concurrent.ExecutorService",
                    DetectorType.EXECUTOR_DEADLOCK, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "a task on a pool of one is recorded waiting for another task on the same "
                            + "pool. The sibling cannot start until this one finishes and this "
                            + "one will not finish until the sibling does, which is a deadlock "
                            + "the pool has no way to break",
                    IssueSeverity.CRITICAL),

            new RecordingSubject("recorded_executor_taskWaitedWithThreadsToSpare", JDK,
                    "java.util.concurrent.ExecutorService",
                    DetectorType.EXECUTOR_DEADLOCK, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same wait on a pool of two that each call creates. The second thread "
                            + "runs the sibling and the wait ends, so at no moment is every "
                            + "worker waiting with work queued, and no wait can close the cycle"),

            new RecordingSubject("recorded_future_blockedOnAFullPool", JDK,
                    "java.util.concurrent.ExecutorService",
                    DetectorType.FUTURE_BLOCKING, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "every thread of a pool of one is recorded blocked waiting on a future. A "
                            + "pool whose workers are all parked on results has nobody left to "
                            + "produce them, which is the same shape as the sibling deadlock "
                            + "seen from the future's end",
                    IssueSeverity.CRITICAL),

            new RecordingSubject("recorded_future_blockedWithThreadsToSpare", JDK,
                    "java.util.concurrent.ExecutorService",
                    DetectorType.FUTURE_BLOCKING, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same blocking wait on a pool of two that each call creates. A worker "
                            + "remains to run the work being waited for, and the wait ends"),

            new RecordingSubject("recorded_flowSubscriber_signalledAfterCompletion", JDK,
                    "java.util.concurrent.Flow",
                    DetectorType.FLOW_PUBLISHER_CONCURRENCY, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "an onNext is recorded after the subscriber has already been completed. The "
                            + "Reactive Streams rule the Flow API adopts is that onComplete is "
                            + "terminal and nothing may follow it, so a subscriber that receives "
                            + "one is being handed state it has already torn down",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_flowSubscriber_signalledInOrder", JDK,
                    "java.util.concurrent.Flow",
                    DetectorType.FLOW_PUBLISHER_CONCURRENCY, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "a subscriber per invocation taken through subscribe, request, one onNext "
                            + "and onComplete in that order. That is the protocol as specified, "
                            + "and the pair separates on where the terminal signal sits"),

            // --- The last three, which close the recording-fed set: every detector the lane can
            //     feed now has either a pair or a refusal with a reason.

            new RecordingSubject("recorded_stampedLock_stampNeverReleased", JDK,
                    "java.util.concurrent.locks.StampedLock",
                    DetectorType.STAMPED_LOCK, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "a write stamp is taken on a real lock and never released, with nothing "
                            + "declared. StampedLock is not reentrant and holds no owner, so a "
                            + "leaked stamp is a lock nobody can release and every later writer "
                            + "waits on it forever. Since #588 the detector infers the leak from "
                            + "the acquisition no unlock matched, and reports it only while the "
                            + "lock itself is still write-held at analysis",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_stampedLock_stampReleased", JDK,
                    "java.util.concurrent.locks.StampedLock",
                    DetectorType.STAMPED_LOCK, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same real acquisition released in a finally block with its unlock "
                            + "recorded against the same stamp, which is the whole discipline the "
                            + "class asks of a caller. The pair separates on whether the stamp "
                            + "came back"),

            new RecordingSubject("recorded_interruptedException_swallowedWholesale", JDK,
                    "java.lang.InterruptedException",
                    DetectorType.INTERRUPT_MISHANDLING, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "an InterruptedException is recorded as caught and no restore is ever "
                            + "recorded. This is the same defect the INTERRUPT_SWALLOWING pair "
                            + "covers, seen by the monitor that counts catches against restores "
                            + "rather than by the one that reads a per-catch flag",
                    IssueSeverity.HIGH),

            new RecordingSubject("recorded_interruptedException_restoredAfterCatching", JDK,
                    "java.lang.InterruptedException",
                    DetectorType.INTERRUPT_MISHANDLING, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same catch with a recorded restore behind it, so catches and restores "
                            + "balance. The pair separates on whether the interrupt was put back"),

            new RecordingSubject("recorded_secureRandom_sharedAcrossThreads", JDK,
                    "java.security.SecureRandom",
                    DetectorType.SHARED_SECURE_RANDOM, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "one SecureRandom is recorded from six threads. Like Random it is "
                            + "thread-safe, so this is a contention note rather than a "
                            + "corruption claim - and entropy draws serialise, which makes the "
                            + "queue behind a shared instance longer than the one behind a "
                            + "shared Random",
                    IssueSeverity.MEDIUM),

            new RecordingSubject("recorded_secureRandom_confinedToOneThreadEach", JDK,
                    "java.security.SecureRandom",
                    DetectorType.SHARED_SECURE_RANDOM, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "an instance per thread, recorded the same number of times. This is the "
                            + "second detector whose guarded twin fires by design and whose "
                            + "confined twin does not: wrapping a shared generator in a lock "
                            + "adds to the contention, and giving each thread its own removes it")
    );

    /** The idiom lane's own test class, whose nested classes are the user code a row runs. */
    private static final String IDIOM_LANE = "com.example.corpus.CorpusIdiomLaneTest.";

    /**
     * The idiom lane's rows: correct user-code concurrency, each with its broken twin.
     *
     * <p>Unlike the other two pair lanes, a row here is not written around one detector. The body
     * is the idiom as a user writes it, every detector is on, and the detector a row names is the
     * one the idiom is about: the one a broken twin must wake, and the one that must stay silent
     * at every tier on the correct half. The correct half is held to more than that: no detector
     * at all may report on it at {@code FACT} tier or above. {@link CorpusGates#checkIdiomLane}
     * has the whole bar.
     *
     * <p>A correct row that sets {@code expectedSeverity} is expecting a note: its named detector
     * must report at exactly that severity, below {@code FACT}. A shared {@code java.util.Random}
     * is the one such row, because contention on a thread-safe generator is worth a word and is
     * not a defect.
     */
    private static final List<RecordingSubject> IDIOM_SUBJECTS = List.of(
            // --- Seed rows: the idioms the 1.12.3 false positives were found in, each fixed on
            //     the way to this lane and each now held there.

            new RecordingSubject("idiom_blockingQueue_handsOffAMutableObject", JDK,
                    "java.util.concurrent.LinkedBlockingQueue",
                    DetectorType.ATOMICITY_VIOLATIONS, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the producer writes the order before put() and the consumer writes it after "
                            + "take(). A BlockingQueue's javadoc names that as a happens-before "
                            + "edge, so the two threads' writes to one object are ordered and the "
                            + "consumer owns what it took"),

            new RecordingSubject("idiom_blockingQueue_handsOffThroughAPlainDeque", JDK,
                    "java.util.ArrayDeque",
                    DetectorType.SHARED_COLLECTIONS, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "the same hand-off through an ArrayDeque, offered to and polled from by six "
                            + "threads with no lock. The deque is the synchronization that was "
                            + "removed, so the collection detector is the one it must wake. "
                            + "AtomicityValidator also reports the orders in some runs and not in "
                            + "others, depending on whether a poll ever caught an offer, so it is "
                            + "not what this row pins",
                    IssueSeverity.HIGH),

            new RecordingSubject("idiom_volatileFlag_publishesPlainData", JDK,
                    IDIOM_LANE + "Mailbox",
                    DetectorType.ATOMICITY_VIOLATIONS, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "one writer per round writes plain data and then a volatile flag, and every "
                            + "reader reads the flag before the data. The volatile write and the "
                            + "read that sees it order the data write before every data read"),

            new RecordingSubject("idiom_volatileFlag_plainFlagPublishesNothing", JDK,
                    IDIOM_LANE + "PlainMailbox",
                    DetectorType.ATOMICITY_VIOLATIONS, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "the same code with the flag declared without volatile. Nothing orders the "
                            + "writer's two writes before any reader's reads, and the flag itself "
                            + "is written and read by different threads with nothing between",
                    IssueSeverity.HIGH),

            new RecordingSubject("idiom_threadStartJoin_ordersTheChildsWrite", JDK,
                    "java.lang.Thread",
                    DetectorType.RACE_CONDITIONS, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the parent writes the input before start() and reads the output after "
                            + "join(). Thread.start orders the first and Thread.join the second, "
                            + "which the Java memory model states in so many words"),

            new RecordingSubject("idiom_threadStartJoin_readsBeforeTheJoin", JDK,
                    "java.lang.Thread",
                    DetectorType.RACE_CONDITIONS, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "the same child with the parent reading its output before join(). Nothing "
                            + "orders the child's write against that read",
                    IssueSeverity.HIGH),

            new RecordingSubject("idiom_atomicInteger_sharedCounter", JDK,
                    "java.util.concurrent.atomic.AtomicInteger",
                    DetectorType.ATOMICITY_VIOLATIONS, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "every thread calls incrementAndGet on one AtomicInteger, which is the "
                            + "class's whole purpose. The counter's field is final and its update "
                            + "is one atomic call"),

            new RecordingSubject("idiom_atomicInteger_plainCounterLosesUpdates", JDK,
                    IDIOM_LANE + "Hits",
                    DetectorType.ATOMICITY_VIOLATIONS, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "every thread increments one plain int field: a read and a write from six "
                            + "threads with no lock, the lost update the detector exists for",
                    IssueSeverity.HIGH),

            new RecordingSubject("idiom_singleWriter_publishesThroughAVolatile", JDK,
                    IDIOM_LANE + "Gauge",
                    DetectorType.ATOMICITY_VIOLATIONS, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "one thread per round bumps a volatile with a read-then-write and the rest "
                            + "only read it. With a single writer the read-then-write cannot lose "
                            + "an update, and the volatile publishes each value it writes"),

            new RecordingSubject("idiom_singleWriter_everyThreadWrites", JDK,
                    IDIOM_LANE + "Gauge",
                    DetectorType.ATOMICITY_VIOLATIONS, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "the same read-then-write from every thread. Volatile makes each access "
                            + "visible and the pair still is not atomic, so two writers lose one "
                            + "of their updates",
                    IssueSeverity.HIGH),

            new RecordingSubject("idiom_concurrentHashMap_publishesAFreshlyBuiltObject", JDK,
                    "java.util.concurrent.ConcurrentHashMap",
                    DetectorType.ATOMICITY_VIOLATIONS, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "each thread finishes its object with setters, then puts it, and the next "
                            + "ticket's thread gets it and reads it. ConcurrentMap's javadoc "
                            + "orders the put before the get that returns the value"),

            new RecordingSubject("idiom_concurrentHashMap_mutatedAfterThePut", JDK,
                    "java.util.concurrent.ConcurrentHashMap",
                    DetectorType.ATOMICITY_VIOLATIONS, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "the same object with the setters run after the put that published it. The "
                            + "map orders only what came before the put, so the reader's reads and "
                            + "the late writes are unordered",
                    IssueSeverity.HIGH),

            new RecordingSubject("idiom_threadConfined_twoObjectsPerThread", JDK,
                    IDIOM_LANE + "Account",
                    DetectorType.ATOMICITY_VIOLATIONS, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "each thread builds two accounts, moves money between them and drops them. "
                            + "Two objects of one class on every thread of every round, and none "
                            + "of them ever reaches a second thread"),

            new RecordingSubject("idiom_threadConfined_twoObjectsSharedByEveryThread", JDK,
                    IDIOM_LANE + "Account",
                    DetectorType.ATOMICITY_VIOLATIONS, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "the same transfer between two accounts every thread shares, with no lock: "
                            + "two read-then-writes per call from six threads",
                    IssueSeverity.HIGH),

            new RecordingSubject("idiom_messageDigestPool_checkedOutThroughAQueue", JDK,
                    "java.security.MessageDigest",
                    DetectorType.SHARED_MESSAGE_DIGEST, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "six digests in a BlockingQueue, each taken, used and put back. Every digest "
                            + "reaches many threads over the run and only ever one at a time, "
                            + "handed over by the queue each time"),

            new RecordingSubject("idiom_messageDigestPool_peekedByEveryThread", JDK,
                    "java.security.MessageDigest",
                    DetectorType.SHARED_MESSAGE_DIGEST, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "the same pool with peek() for take(): every thread updates and drains the "
                            + "head digest at once, so each hash covers an interleaving of inputs",
                    IssueSeverity.HIGH),

            new RecordingSubject("idiom_synchronizedList_iterateAndAdd", JDK,
                    "java.util.ArrayList",
                    DetectorType.SHARED_COLLECTIONS, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "every walk and every append of one ArrayList happen inside synchronized on "
                            + "the list itself, which is the idiom the Collections javadoc "
                            + "prescribes for iterating a shared list"),

            new RecordingSubject("idiom_synchronizedList_iterateAndAddUnguarded", JDK,
                    "java.util.ArrayList",
                    DetectorType.SHARED_COLLECTIONS, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "the same walk and append with no monitor, so an append lands in the middle "
                            + "of another thread's iteration",
                    IssueSeverity.HIGH),

            new RecordingSubject("idiom_synchronizedCheckThenAct_onAConcurrentHashMap", JDK,
                    "java.util.concurrent.ConcurrentHashMap",
                    DetectorType.CONCURRENT_MAP_CHECK_THEN_ACT, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "six threads of a round each run containsKey-then-put on the round's key, "
                            + "every one inside synchronized on the map. The monitor makes the "
                            + "pair atomic, so exactly one put wins"),

            new RecordingSubject("idiom_synchronizedCheckThenAct_withoutTheMonitor", JDK,
                    "java.util.concurrent.ConcurrentHashMap",
                    DetectorType.CONCURRENT_MAP_CHECK_THEN_ACT, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "the same containsKey-then-put with no monitor. Each call is atomic and the "
                            + "pair is not, so two threads can both find the key absent",
                    IssueSeverity.HIGH),

            new RecordingSubject("idiom_threadLocalRandom_currentOnEveryThread", JDK,
                    "java.util.concurrent.ThreadLocalRandom",
                    DetectorType.THREAD_LOCAL_RANDOM_MISUSE, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "every thread calls current() and uses what it got on that thread. current() "
                            + "returns one JVM-wide object, so this is correct even though six "
                            + "threads hold the same reference"),

            new RecordingSubject("idiom_threadLocalRandom_capturedByOneThread", JDK,
                    "java.util.concurrent.ThreadLocalRandom",
                    DetectorType.THREAD_LOCAL_RANDOM_MISUSE, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "one thread per round calls current() and the others use its capture. A "
                            + "thread that never called current() draws from an unseeded state",
                    IssueSeverity.MEDIUM),

            new RecordingSubject("idiom_guardedWait_loopsOnTheCondition", JDK,
                    "java.lang.Object",
                    DetectorType.MISSED_SIGNAL, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the notifier sets the condition and calls notifyAll under the monitor, and "
                            + "every waiter re-tests the condition in a loop around wait(). That "
                            + "is the form Object.wait's javadoc says a wait must take"),

            new RecordingSubject("idiom_guardedWait_waitsWithNoCondition", JDK,
                    "java.lang.Object",
                    DetectorType.MISSED_SIGNAL, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "the same monitor with the condition taken out: the notifier only notifies "
                            + "and each waiter waits once, timed. A notify that lands before a "
                            + "waiter arrives is lost, and that waiter waits unsignalled until "
                            + "its timeout, which is the missed signal the detector exists for",
                    IssueSeverity.CRITICAL),

            new RecordingSubject("idiom_countDownLatch_publishesBeforeTheCountDown", JDK,
                    "java.util.concurrent.CountDownLatch",
                    DetectorType.ATOMICITY_VIOLATIONS, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the writer writes and then counts down; every reader awaits and then reads. "
                            + "CountDownLatch's javadoc orders actions before countDown before "
                            + "actions after a successful await"),

            new RecordingSubject("idiom_countDownLatch_readersSkipTheAwait", JDK,
                    "java.util.concurrent.CountDownLatch",
                    DetectorType.ATOMICITY_VIOLATIONS, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "the same write and count-down with the readers not awaiting, so nothing "
                            + "orders the write before their reads",
                    IssueSeverity.HIGH),

            new RecordingSubject("idiom_sharedRandom_drawnByEveryThread", JDK,
                    "java.util.Random",
                    DetectorType.SHARED_RANDOM, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "every thread draws from one java.util.Random. Random is thread-safe, so "
                            + "the only thing worth saying is that its CAS loop is contended, and "
                            + "the detector says exactly that as a LOW advisory and nothing more",
                    IssueSeverity.LOW),

            new RecordingSubject("idiom_sharedRandom_splittableDrawnByEveryThread", JDK,
                    "java.util.SplittableRandom",
                    DetectorType.SHARED_SPLITTABLE_RANDOM, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "the same draw from one SplittableRandom, whose javadoc says instances are "
                            + "not thread-safe: the thread-safety Random has is what is missing",
                    IssueSeverity.HIGH)
    );

    /**
     * Idiom rows whose body calls the manual recording API, each with the reason.
     *
     * <p>Every other body in the lane records nothing, and {@link IdiomRowPremise} fails the lane
     * if one does. A row belongs here only when no woven call site can show its detector the
     * idiom, so the body has to say what it did, the way a user following
     * {@code AsyncTestContext} would.
     */
    private static final Map<String, String> IDIOM_MANUAL_API_ROWS = Map.of(
            "idiom_synchronizedCheckThenAct_onAConcurrentHashMap",
            "no agent-fed detector models a check-then-act: SharedCollectionDetector sees two "
                    + "atomic calls on a concurrent map and rightly says nothing, so "
                    + "NonAtomicConcurrentMapUpdateDetector is told the pair happened",
            "idiom_synchronizedCheckThenAct_withoutTheMonitor",
            "the twin of the row above, recording the same pair the same way",
            "idiom_threadLocalRandom_currentOnEveryThread",
            "the agent does not weave ThreadLocalRandom.current(), so the obtain and the use "
                    + "are reported by the body",
            "idiom_threadLocalRandom_capturedByOneThread",
            "the twin of the row above, recording the same obtain and use",
            "idiom_sharedRandom_drawnByEveryThread",
            "the agent does not weave java.util.Random, so the draw is reported by the body",
            "idiom_sharedRandom_splittableDrawnByEveryThread",
            "the twin of the row above, on the SplittableRandom recording API",
            "idiom_threadStartJoin_ordersTheChildsWrite",
            "the agent drops accesses on a thread the runner did not start (#500), so the "
                    + "child's half of the idiom is invisible to it; the body records both "
                    + "halves to RaceConditionDetector, and the woven start and join are the edges",
            "idiom_threadStartJoin_readsBeforeTheJoin",
            "the twin of the row above, recording the same accesses the same way"
    );

    /**
     * Correct idioms that still draw a finding from the detector they name, each with the reason.
     *
     * <p>The mirror of {@link DetectorCoverage}'s refusals, for rows rather than detectors. A row
     * here is correct code the happens-before model does not see yet, so its named detector still
     * reports on it. {@link CorpusGates#checkIdiomLane} holds each entry to that in both
     * directions: the row must still draw the finding, and the day a fix makes it silent the run
     * fails until the entry is deleted, so a closed gap cannot stay listed as open.
     */
    private static final Map<String, String> IDIOM_KNOWN_GAPS = Map.of(
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

    /**
     * {@return the paired subjects of {@code lane}}
     *
     * <p>Both pair lanes are the same measurement over a different feed, so everything downstream
     * of the subject list - the report, the per-subject outcome gate, the exposure denominators -
     * is written once against this and reads the same either way.
     */
    static List<RecordingSubject> subjectsFor(CorpusLane lane) {
        if (lane == CorpusLane.AGENT_PAIRS_LIBRARY_EXCLUDED) {
            // The library rows only: a JDK row calls the JDK from the test file, so excluding the
            // libraries from weaving says nothing about where its finding came from.
            return AGENT_SUBJECTS.stream()
                    .filter(Corpus::wovenCallSiteIsInsideTheLibrary)
                    .toList();
        }
        if (lane == CorpusLane.IDIOMS) {
            return IDIOM_SUBJECTS;
        }
        return lane == CorpusLane.AGENT_PAIRS ? AGENT_SUBJECTS : RECORDING_SUBJECTS;
    }

    /** {@return the idiom rows that call the manual API, each with the reason it has to} */
    static Map<String, String> idiomManualApiRows() {
        return IDIOM_MANUAL_API_ROWS;
    }

    /** {@return the correct idiom rows still pinned as reporting, each with the reason} */
    static Map<String, String> idiomKnownGaps() {
        return IDIOM_KNOWN_GAPS;
    }

    /** {@return the detectors the idiom lane's manual-API rows record to} */
    static Set<DetectorType> idiomRecordedDetectors() {
        return IDIOM_SUBJECTS.stream()
                .filter(subject -> IDIOM_MANUAL_API_ROWS.containsKey(subject.testMethod()))
                .map(RecordingSubject::detector)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(DetectorType.class)));
    }

    /**
     * Agent rows whose subject type comes from a library but whose woven call site is the test
     * body (#692).
     *
     * <p>Lane five assumes a library row's finding comes from a call woven inside the library, so
     * with the library excluded the row must go silent. A hand-off is the other way round: the
     * woven {@code offer} and {@code poll} sit in the body, where the weaver matches the queue's
     * interface by name, and they are what excuses the second thread, so excluding the library
     * changes nothing and the firing row keeps firing. Such a row measures the real library type,
     * not library bytecode. It is kept out of lane five and out of {@link LibraryReach}, the two
     * places that claim the latter, and is an ordinary agent pair everywhere else.
     */
    private static final Set<String> BODY_CALL_SITE_ROWS = Set.of(
            "agent_jctoolsHandOff_offererWritesAfterTheOffer",
            "agent_jctoolsHandOff_offererLetsGo");

    /** {@return whether {@code subject}'s finding is claimed to come from a call woven inside its library} */
    static boolean wovenCallSiteIsInsideTheLibrary(RecordingSubject subject) {
        return !subject.library().startsWith("jdk:")
                && !BODY_CALL_SITE_ROWS.contains(subject.testMethod());
    }

    /** {@return the detectors {@code lane} pairs, which is its whole denominator} */
    static Set<DetectorType> pairedDetectors(CorpusLane lane) {
        return subjectsFor(lane).stream()
                .map(RecordingSubject::detector)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(DetectorType.class)));
    }

    /** {@return the subject of {@code lane} for {@code testMethod}, or {@code null}} */
    static RecordingSubject pairByTestMethod(CorpusLane lane, String testMethod) {
        return subjectsFor(lane).stream()
                .filter(subject -> subject.testMethod().equals(testMethod))
                .findFirst()
                .orElse(null);
    }

    /** {@return the recording lane's subjects} */
    static List<RecordingSubject> recordingSubjects() {
        return RECORDING_SUBJECTS;
    }

    /** {@return the detectors the recording lane records to, which is its whole denominator} */
    static Set<DetectorType> recordedDetectors() {
        return RECORDING_SUBJECTS.stream()
                .map(RecordingSubject::detector)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(DetectorType.class)));
    }

    /** {@return the pair-lane subject for {@code testMethod}, from either lane, or {@code null}} */
    static RecordingSubject recordingByTestMethod(String testMethod) {
        // Both pair lanes, not just the recording one. Searching only RECORDING_SUBJECTS silently
        // restricted META-INF/async-test/verdict-evidence-corpus to recording rows: an agent-lane
        // pair could not back a tier at all, because the gate resolving the file's ids would
        // report the row as not existing. Both lanes are held to their stated outcomes per
        // subject on every run, so both can be evidence.
        return java.util.stream.Stream.concat(RECORDING_SUBJECTS.stream(), AGENT_SUBJECTS.stream())
                .filter(subject -> subject.testMethod().equals(testMethod))
                .findFirst()
                .orElse(null);
    }
}
