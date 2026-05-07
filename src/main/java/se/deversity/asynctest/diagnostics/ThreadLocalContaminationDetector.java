package se.deversity.asynctest.diagnostics;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Detects {@link ThreadLocal} values that bleed from one task into the next task executing
 * on the same pooled thread — cross-task state contamination.
 *
 * <p>When a thread pool reuses a thread, any {@code ThreadLocal} values left by the previous
 * task are still visible to the next task. Unlike a memory leak ({@link ThreadLocalMonitor})
 * this is a correctness bug: task B silently reads state that was intended only for task A.
 *
 * <p>Common victims: MDC loggers, security contexts, request-scoped beans (Spring/Jakarta EE).
 *
 * <p>Usage inside {@code @AsyncTest}:
 * <pre>{@code
 * var mon = AsyncTestContext.threadLocalContaminationMonitor();
 * mon.recordNewTask(Thread.currentThread(), "task-A");
 * mon.recordSet(Thread.currentThread(), MY_TL, "MY_TL");
 * // simulate task boundary / thread reuse
 * mon.recordNewTask(Thread.currentThread(), "task-B");
 * mon.recordGet(Thread.currentThread(), MY_TL, "MY_TL", MY_TL.get() != null);
 * }</pre>
 */
public class ThreadLocalContaminationDetector {

    private static class ThreadState {
        int taskCount = 0;
        String currentTaskName = "task-0";
        final Map<Integer, Integer> lastSetInTask = new HashMap<>();
        final Map<Integer, String>  tlNames       = new HashMap<>();
    }

    private final Map<Long, ThreadState> threadStates   = new ConcurrentHashMap<>();
    private final List<String>           contaminations = new CopyOnWriteArrayList<>();

    /** Call at the start of each task submitted to a thread pool. */
    public void recordNewTask(Thread thread, String taskName) {
        if (thread == null) return;
        ThreadState s = threadStates.computeIfAbsent(thread.getId(), id -> new ThreadState());
        s.taskCount++;
        s.currentTaskName = taskName != null ? taskName : "task-" + s.taskCount;
    }

    /** Call after each {@code ThreadLocal.set()} inside a task. */
    public void recordSet(Thread thread, Object tl, String name) {
        if (thread == null || tl == null) return;
        ThreadState s = threadStates.get(thread.getId());
        if (s == null) return;
        int id = System.identityHashCode(tl);
        s.lastSetInTask.put(id, s.taskCount);
        if (name != null) s.tlNames.put(id, name);
    }

    /**
     * Call after each {@code ThreadLocal.get()} inside a task.
     *
     * @param hasValue {@code true} if the get returned a non-null value
     */
    public void recordGet(Thread thread, Object tl, String name, boolean hasValue) {
        if (thread == null || tl == null || !hasValue) return;
        ThreadState s = threadStates.get(thread.getId());
        if (s == null) return;
        int id = System.identityHashCode(tl);
        Integer setTask = s.lastSetInTask.get(id);
        if (setTask != null && setTask < s.taskCount) {
            String label = s.tlNames.getOrDefault(id, name != null ? name : "ThreadLocal@" + id);
            contaminations.add(String.format(
                "Thread '%s' in '%s': read %s whose value was set in task %d — not cleared between tasks",
                thread.getName(), s.currentTaskName, label, setTask));
        }
    }

    /** @return report of cross-task ThreadLocal contaminations */
    public ThreadLocalContaminationReport analyze() {
        ThreadLocalContaminationReport r = new ThreadLocalContaminationReport();
        r.contaminations.addAll(contaminations);
        return r;
    }

    /** Report produced by {@link #analyze()}. */
    public static class ThreadLocalContaminationReport {
        final List<String> contaminations = new ArrayList<>();

        public boolean hasIssues() { return !contaminations.isEmpty(); }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("THREADLOCAL CONTAMINATION DETECTED:\n");
            for (String c : contaminations) sb.append("  - ").append(c).append("\n");
            sb.append("  Why: Thread pools reuse threads across tasks. A ThreadLocal set by Task A persists into Task B\n" +
                    "       if it is not explicitly cleared. Task B then reads stale, unintended context — a security\n" +
                    "       boundary violation if the value is a user identity or tenant, and a correctness bug otherwise.\n" +
                    "  Fix:\n" +
                    "    - Call ThreadLocal.remove() in a task-finally block: try { doWork(); } finally { ctx.remove(); }\n" +
                    "    - Or wrap in AutoCloseable: try (var ctx = ScopedContext.bind(value)) { doWork(); }\n" +
                    "    - Consider ScopedValue (Java 21+) instead — it is automatically scoped to the current call and cleaned up");
            return sb.toString();
        }
    }
}
