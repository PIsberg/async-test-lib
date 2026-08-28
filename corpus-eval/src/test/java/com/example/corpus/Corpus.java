package com.example.corpus;

import se.deversity.asynctest.DetectorType;

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
                    "org/springframework/util/ConcurrentReferenceHashMap.java:51")
    );

    /**
     * The recording lane's subjects: the same libraries, with bodies that cooperate.
     *
     * <p>Every row is a both-directions pair with its twin, because one direction on its own
     * proves nothing. A detector that fires on everything passes a MUST_FIRE row; a detector
     * that was never wired up passes a MUST_STAY_SILENT row. Only the pair says the model works.
     */
    private static final List<RecordingSubject> RECORDING_SUBJECTS = List.of(

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
                            + "one is the exception its own javadoc names"),

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
                            + "synchronized, which is the read/write race the detector exists for"),

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
                            + "is thread-safe and the caller is still wrong"),

            new RecordingSubject("recorded_caffeineAsMap_computeIfAbsent", CAFFEINE,
                    "com.github.benmanes.caffeine.cache.Cache",
                    DetectorType.CONCURRENT_MAP_CHECK_THEN_ACT, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "computeIfAbsent is the atomic primitive that fixes the row above, so the "
                            + "body has no check-then-act to record and the detector has no input"),
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
                            + "owed however thread-safe HikariDataSource itself is"),

            // --- SharedMessageDigest: the pair differs by a lock, not by an instance. Both rows
            //     share one digest with six threads; only one of them holds its monitor.

            new RecordingSubject("recorded_messageDigest_sharedAcrossThreads", JDK,
                    "java.security.MessageDigest",
                    DetectorType.SHARED_MESSAGE_DIGEST, Contract.NOT_THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_FIRE,
                    "one SHA-256 instance is recorded from six threads with nothing held, which "
                            + "is both halves of the detector's rule met by construction. The "
                            + "JDK's own javadoc says a MessageDigest is not safe for use by "
                            + "multiple threads without external synchronization"),

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
                            + "the MAC rather than failing loudly"),

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
                            + "closes is a leak whatever the schedule did"),

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
                            + "overwritten by the outer one and lost with nothing thrown"),

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
                            + "intend, silently"),

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
                            + "class is thread-safe and the caller is still wrong"),

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
                            + "unsafe caller"),

            new RecordingSubject("recorded_concurrentHashMultiset_iteratorPerThread", GUAVA,
                    "com.google.common.collect.ConcurrentHashMultiset",
                    DetectorType.SHARED_ITERATOR, Contract.THREAD_SAFE,
                    RecordingSubject.Expectation.MUST_STAY_SILENT,
                    "the same call on the same collection, with each body taking its own "
                            + "iterator. Confining an iterator to the thread that created it is "
                            + "the fix, and a finding here would report every correct traversal "
                            + "of a concurrent collection there is")
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

    /** {@return the recording subject for {@code testMethod}, or {@code null}} */
    static RecordingSubject recordingByTestMethod(String testMethod) {
        return RECORDING_SUBJECTS.stream()
                .filter(subject -> subject.testMethod().equals(testMethod))
                .findFirst()
                .orElse(null);
    }
}
