package se.deversity.asynctest.diagnostics;

import com.sun.management.HotSpotDiagnosticMXBean;
import org.jspecify.annotations.Nullable;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Finds monitor deadlocks that {@code ThreadMXBean.findDeadlockedThreads()} cannot see, by
 * reading the JVM's own thread dump.
 *
 * <p><strong>Why this exists.</strong> {@code findDeadlockedThreads()} reports platform threads.
 * {@code @AsyncTest} runs its workers on virtual threads by default, so the eight threads
 * colliding on the code under test are exactly the ones a JMX deadlock query cannot put in a
 * cycle, and {@code DeadlockDetector} returned a clean report for a textbook circular wait.
 * Measured on {@code examples/06-deadlock}: silent by default, reported with
 * {@code useVirtualThreads = false}. See issue #367.
 *
 * <p>{@code HotSpotDiagnosticMXBean.dumpThreads(path, JSON)} does include virtual threads, and on
 * JDKs whose dump carries lock information each thread entry has {@code blockedOn} (the monitor it
 * is waiting for) and {@code monitorsOwned} (the monitors it holds). That is a wait-for graph, and
 * a cycle in it is a deadlock.
 *
 * <p><strong>Capability, not version.</strong> The lock fields are not in every JDK's dump: they
 * are absent on 21 and on 24, and present on 26, all measured. Rather than carry a version table
 * that goes stale, {@link #scan()} looks for the fields and returns {@link Optional#empty()} when
 * they are not there. That distinction is the point: "looked, found no cycle" and "this JVM cannot
 * answer" are different answers, and reporting the second as the first is the silent green this
 * library exists to stop.
 *
 * <p><strong>Monitors only.</strong> A deadlock on {@code ReentrantLock} parks rather than blocks;
 * the dump names the blocker through {@code parkBlocker} but not its owner, so those cycles cannot
 * be closed from here and are not reported. Cycles made only of platform threads are not reported
 * either, because {@code findDeadlockedThreads()} already reports them and one deadlock should be
 * one finding.
 */
final class VirtualThreadLockGraph {

    /** Keys that appear inside a {@code monitorsOwned} array and are not lock identities. */
    private static final Set<String> MONITOR_ARRAY_KEYS = Set.of("depth", "locks");

    /** The quote character, so key literals below read as the JSON they match. */
    private static final String QUOTE = "\"";

    /** The JSON escape character. */
    private static final char BACKSLASH = '\\';

    /** Memoized answer for {@link #canSeeMonitors()}; null until first asked. */
    private static volatile @Nullable Boolean capability;

    private VirtualThreadLockGraph() {
    }

    /**
     * One thread, as the dump describes it.
     *
     * <p>{@code state} and {@code stack} play no part in cycle detection. They are here because
     * {@code StaticInitDeadlockDetector}'s live sample needs the same three facts about a virtual
     * thread that {@code Thread.getAllStackTraces()} gives it about a platform one, and this is
     * the only place that can supply them. {@code state} is null on JDKs whose dump omits it. See
     * issue #376.
     */
    record DumpedThread(String tid, String name, boolean virtual, @Nullable String blockedOn,
                        List<String> monitorsOwned, @Nullable String state, List<String> stack) {
    }

    /** A closed wait-for cycle: each thread waits for a monitor the next one holds. */
    record Cycle(List<String> threadNames, List<String> monitors) {
    }

    /**
     * Every thread the running JVM's dump describes, or empty when that dump carries no state.
     *
     * <p>Separate from {@link #scan()} because the two want different things out of the same
     * document: cycles need monitors, and {@code StaticInitDeadlockDetector}'s live sample needs a
     * state and a stack.
     *
     * @return the threads, or {@link Optional#empty()} when the dump carries no thread state
     */
    static Optional<List<DumpedThread>> threadsWithState() {
        String json = dumpJson();
        return json == null ? Optional.empty() : scanDumpForThreads(json);
    }

    /**
     * The half of {@link #threadsWithState()} that does not touch the JVM.
     *
     * <p>Empty when the document has no {@code state}, and that is the whole point rather than a
     * technicality: the JDK 21 dump carries stacks but no state, and a thread <em>running</em> a
     * static initializer is not parked in one. Handing back stacks with no state to filter on
     * would let the caller report a class that is merely initializing, which is a false positive
     * on correct code. See issue #376.
     *
     * @param json a thread dump in the JSON format {@code dumpThreads} writes
     * @return the threads, or {@link Optional#empty()} when the dump carries no thread state
     */
    static Optional<List<DumpedThread>> scanDumpForThreads(String json) {
        if (!json.contains(QUOTE + "state" + QUOTE)) {
            return Optional.empty();
        }
        return Optional.of(parse(json));
    }

    /**
     * Whether this JVM's thread dump names monitors, and so can answer the deadlock question for
     * virtual threads at all.
     *
     * <p>Cached: the answer cannot change while the JVM runs, and finding it out costs a thread
     * dump. Callers use it to decide whether a clean report means "no deadlock" or "not asked".
     *
     * @return {@code true} when a virtual-thread cycle would be visible here
     */
    static boolean canSeeMonitors() {
        Boolean known = capability;
        if (known == null) {
            String json = dumpJson();
            known = json != null && carriesLockInformation(json);
            capability = known;
        }
        return known;
    }

    /**
     * Scans the running JVM for monitor deadlocks involving at least one virtual thread.
     *
     * @return the cycles found, or {@link Optional#empty()} when this JVM's thread dump does not
     *         carry the lock information the question needs
     */
    static Optional<List<Cycle>> scan() {
        String json = dumpJson();
        return json == null ? Optional.empty() : scanDump(json);
    }

    /**
     * The half of {@link #scan()} that does not touch the JVM, so the parser can be pinned against
     * dumps captured from JDKs this machine is not running.
     *
     * @param json a thread dump in the JSON format {@code dumpThreads} writes
     * @return the cycles found, or {@link Optional#empty()} when the dump does not name monitors
     */
    static Optional<List<Cycle>> scanDump(String json) {
        if (!carriesLockInformation(json)) {
            return Optional.empty();
        }
        return Optional.of(cyclesIn(parse(json)));
    }

    /**
     * {@return whether this JVM's thread dump names monitors at all}
     *
     * <p>Keyed on the document rather than on what was parsed out of it. A JVM where nobody holds
     * a monitor would otherwise be indistinguishable from one that never reports monitors, and the
     * two need opposite answers.
     */
    private static boolean carriesLockInformation(String json) {
        return json.contains(QUOTE + "monitorsOwned" + QUOTE) || json.contains(QUOTE + "blockedOn" + QUOTE);
    }

    /**
     * Walks the wait-for graph and returns every cycle that contains at least one virtual thread.
     *
     * <p>A thread waits for whoever owns the monitor it is blocked on. Following that edge from
     * each thread and stopping when the walk revisits a thread already on the path finds the
     * cycle; the same cycle is reached from every member, so cycles are keyed on their member set
     * and reported once.
     */
    private static List<Cycle> cyclesIn(List<DumpedThread> threads) {
        Map<String, DumpedThread> byTid = new HashMap<>();
        Map<String, String> ownerOfMonitor = new HashMap<>();
        for (DumpedThread t : threads) {
            byTid.put(t.tid(), t);
            for (String monitor : t.monitorsOwned()) {
                ownerOfMonitor.put(monitor, t.tid());
            }
        }

        List<Cycle> found = new ArrayList<>();
        Set<Set<String>> reported = new LinkedHashSet<>();
        for (DumpedThread start : threads) {
            List<String> path = new ArrayList<>();
            List<String> monitors = new ArrayList<>();
            DumpedThread current = start;
            while (current != null && current.blockedOn() != null) {
                if (path.contains(current.tid())) {
                    break;
                }
                path.add(current.tid());
                monitors.add(current.blockedOn());
                String ownerTid = ownerOfMonitor.get(current.blockedOn());
                if (ownerTid == null || ownerTid.equals(current.tid())) {
                    current = null;
                    break;
                }
                current = byTid.get(ownerTid);
            }
            if (current == null || !path.contains(current.tid())) {
                continue;                       // the walk ran out rather than closing
            }
            // Trim the tail that leads into the cycle but is not part of it.
            int entry = path.indexOf(current.tid());
            List<String> members = path.subList(entry, path.size());
            Set<String> key = new LinkedHashSet<>(members);
            if (key.size() < 2 || !reported.add(key)) {
                continue;
            }
            List<String> names = new ArrayList<>();
            boolean anyVirtual = false;
            for (String tid : members) {
                DumpedThread t = byTid.get(tid);
                names.add(t == null ? tid : t.name());
                anyVirtual |= t != null && t.virtual();
            }
            if (!anyVirtual) {
                reported.remove(key);
                continue;                       // findDeadlockedThreads() already has this one
            }
            found.add(new Cycle(List.copyOf(names),
                    List.copyOf(monitors.subList(entry, monitors.size()))));
        }
        return found;
    }

    /**
     * Reads every thread object out of the dump.
     *
     * <p>Deliberately not a general JSON parser. The library ships no JSON dependency and adding
     * one to read a diagnostic file would be a poor trade, so this walks the document once,
     * tracking string and brace state, and keeps the innermost object that carries a {@code tid}.
     * Objects close innermost-first, so recording the start offset of the last object kept is
     * enough to skip the containers that merely enclose it. Anything that does not match the
     * shape is skipped rather than guessed at: a missed thread costs a false negative, and a
     * misread one would cost a false deadlock.
     */
    private static List<DumpedThread> parse(String json) {
        List<DumpedThread> out = new ArrayList<>();
        Deque<Integer> starts = new ArrayDeque<>();
        boolean inString = false;
        boolean escaped = false;
        int keptFrom = -1;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == BACKSLASH) {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                starts.push(i);
            } else if (c == '}') {
                Integer start = starts.poll();
                if (start == null || keptFrom >= start) {
                    continue;                   // malformed, or a container around a kept object
                }
                String slice = json.substring(start, i + 1);
                if (!slice.contains(QUOTE + "tid" + QUOTE)) {
                    continue;
                }
                DumpedThread thread = readThread(slice);
                if (thread != null) {
                    out.add(thread);
                    keptFrom = start;
                }
            }
        }
        return out;
    }

    /** {@return the thread this object describes, or null when it does not have the shape} */
    private static @Nullable DumpedThread readThread(String object) {
        String stack = arrayOf(object, "stack");
        String withoutStack = withoutArray(object, "stack");
        String tid = stringField(withoutStack, "tid");
        if (tid == null) {
            return null;
        }
        String name = stringField(withoutStack, "name");
        return new DumpedThread(
                tid,
                name == null ? tid : name,
                withoutStack.replace(" ", "").contains(QUOTE + "virtual" + QUOTE + ":true"),
                stringField(withoutStack, "blockedOn"),
                locksIn(withoutStack),
                stringField(withoutStack, "state"),
                stack == null ? List.of() : List.copyOf(quotedStringsIn(stack)));
    }

    /**
     * {@return the monitors named inside this thread's {@code monitorsOwned} array}
     *
     * <p>Every string in that array is either one of its two key names or a lock identity, so
     * dropping the keys leaves the locks. That is cheaper than another bracket walk and cannot
     * mistake a stack frame for a lock, because the frames are already gone.
     */
    private static List<String> locksIn(String object) {
        String monitors = arrayOf(object, "monitorsOwned");
        if (monitors == null) {
            return List.of();
        }
        List<String> locks = new ArrayList<>();
        for (String value : quotedStringsIn(monitors)) {
            if (!MONITOR_ARRAY_KEYS.contains(value)) {
                locks.add(value);
            }
        }
        return locks;
    }

    /** {@return the value of the first {@code "key": "value"} pair, or null} */
    private static @Nullable String stringField(String object, String key) {
        int at = object.indexOf(QUOTE + key + QUOTE);
        if (at < 0) {
            return null;
        }
        int colon = object.indexOf(':', at + key.length() + 2);
        if (colon < 0) {
            return null;
        }
        int open = object.indexOf('"', colon);
        if (open < 0) {
            return null;
        }
        int close = closingQuote(object, open + 1);
        return close < 0 ? null : unescape(object.substring(open + 1, close));
    }

    /** {@return the {@code [ ... ]} that follows {@code key}, brackets included, or null} */
    private static @Nullable String arrayOf(String object, String key) {
        int at = object.indexOf(QUOTE + key + QUOTE);
        if (at < 0) {
            return null;
        }
        int open = object.indexOf('[', at);
        if (open < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = open; i < object.length(); i++) {
            char c = object.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == BACKSLASH) {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    return object.substring(open, i + 1);
                }
            }
        }
        return null;
    }

    /** {@return the object with {@code key}'s array removed, so field scans cannot match inside it} */
    private static String withoutArray(String object, String key) {
        String array = arrayOf(object, key);
        return array == null ? object : object.replace(array, "[]");
    }

    /** {@return every quoted string in {@code text}, unescaped} */
    private static List<String> quotedStringsIn(String text) {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) != '"') {
                continue;
            }
            int close = closingQuote(text, i + 1);
            if (close < 0) {
                break;
            }
            values.add(unescape(text.substring(i + 1, close)));
            i = close;
        }
        return values;
    }

    /** {@return the index of the quote that closes the string starting at {@code from}, or -1} */
    private static int closingQuote(String text, int from) {
        boolean escaped = false;
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (c == BACKSLASH) {
                escaped = true;
            } else if (c == '"') {
                return i;
            }
        }
        return -1;
    }

    /**
     * {@return {@code value} with the escapes this dump actually uses resolved}
     *
     * <p>The dump escapes the forward slashes in class names and the quotes and backslashes any
     * JSON writer must. Lock identities and thread names contain none of the rest, and an
     * unresolved escape here would at worst make two identities look different, which loses a
     * cycle rather than inventing one.
     */
    private static String unescape(String value) {
        if (value.indexOf(BACKSLASH) < 0) {
            return value;
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == BACKSLASH && i + 1 < value.length()) {
                i++;
                out.append(value.charAt(i));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * {@return the JVM's thread dump as JSON, or null when it cannot be taken}
     *
     * <p>{@code dumpThreads} writes to a file and refuses to overwrite one, so the temp file is
     * created for the name and deleted before the call. Every failure is swallowed into null:
     * a JVM without {@code jdk.management}, a read-only temp directory or a security manager all
     * mean the same thing here, which is that the question cannot be answered, and none of them
     * is a reason to fail somebody's test.
     */
    private static @Nullable String dumpJson() {
        Path file = null;
        try {
            file = Files.createTempFile("asynctest-threaddump", ".json");
            Files.delete(file);
            ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class)
                    .dumpThreads(file.toAbsolutePath().toString(),
                            HotSpotDiagnosticMXBean.ThreadDumpFormat.JSON);
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (Throwable unavailable) {
            return null;
        } finally {
            if (file != null) {
                try {
                    Files.deleteIfExists(file);
                } catch (Exception stillThere) {
                    // A leftover temp file is not worth failing somebody's test over, but it is
                    // worth not leaving behind: hand it to the JVM to remove on the way out.
                    file.toFile().deleteOnExit();
                }
            }
        }
    }
}
