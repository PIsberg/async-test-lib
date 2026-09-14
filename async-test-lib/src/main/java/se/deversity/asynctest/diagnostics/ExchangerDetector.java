package se.deversity.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Exchanger;
import java.util.concurrent.atomic.AtomicInteger;

import org.jspecify.annotations.Nullable;

/**
 * Detects an orphaned {@link Exchanger} rendezvous: an exchange a thread entered and never left.
 *
 * <p>An exchanger pairs its callers two at a time. When they do not arrive in pairs, the caller
 * left over waits for a partner that is not coming, and an untimed {@code exchange(v)} waits for
 * it forever, holding its thread.
 *
 * <p><strong>The model.</strong> Per exchanger, tracked by identity, the detector counts the
 * exchanges started ({@link #recordExchangeStart}) and the exchanges ended, which is any of
 * {@link #recordExchangeComplete}, {@link #recordTimeout} and {@link #recordInterrupted}. The
 * runner analyses after its workers have quiesced, so an exchanger with more starts than ends at
 * that point has a thread that went into {@code exchange()} and never came back out. That is the
 * finding, reported CRITICAL.
 *
 * <p><strong>What is not a finding.</strong> A recorded timeout or interrupt is how a thread
 * left an exchange, not evidence that one was orphaned: a timed {@code exchange(v, t, unit)} that
 * catches {@code TimeoutException} is the prescribed fix, and an interrupt handled during shutdown
 * is a normal way out. Both used to be the finding on their own, reported CRITICAL whether or not
 * anything was left waiting (#585); they are now printed as context. A {@code null} payload is not
 * a finding either: {@code exchange(null)} is permitted and a payload-free handoff is a normal
 * rendezvous (#521).
 *
 * <p><strong>Boundaries.</strong>
 * <ul>
 *   <li>The counts are per exchanger, not per call. An end recorded without its start (a
 *       completion recorded by a thread that never recorded starting) offsets another thread's
 *       open start, so that orphan goes unreported.</li>
 *   <li>An untimed orphan inside an {@code @AsyncTest} body blocks its round until
 *       {@code timeoutMs}. The runner then interrupts the workers before analysing; a body that
 *       records that interrupt has closed the exchange, and the round timeout is the only signal
 *       left. A body that lets the {@code InterruptedException} propagate leaves the start open,
 *       and the finding is printed with the timeout and named in its message.</li>
 * </ul>
 */
public class ExchangerDetector {

    /** Name shown for an exchanger no call has named yet. */
    static final String UNREGISTERED = "<unregistered exchanger>";

    private final Map<Exchanger<?>, ExchangerInfo> exchangerRegistry = new ConcurrentHashMap<>();
    // Both parties of an exchange call recordExchangeComplete concurrently by construction, so
    // this counter is written from two threads at once. A plain int made it a lost-update race
    // in a library whose own SharedCollectionDetector exists to flag exactly that shape.
    private final AtomicInteger nullValueExchanges = new AtomicInteger();

    /**
     * Register an Exchanger for monitoring.
     *
     * @param exchanger the exchanger being recorded, tracked by identity
     * @param name a label identifying the exchanger in the report
     */
    public void registerExchanger(Exchanger<?> exchanger, String name) {
        // First name wins, and registering never resets counts: an @AsyncTest body runs once per
        // thread, so a consumer registering inside it registers once per worker, and a second
        // registration must not discard the exchanges the first worker already started.
        infoFor(exchanger, name);
    }

    /**
     * Record a thread starting an exchange.
     *
     * <p>An exchanger that was never registered is tracked from here under {@code exchangerName}.
     * Unregistered starts used to be dropped, so an exchange nobody registered could never be
     * found orphaned.
     *
     * @param exchanger the exchanger being recorded, tracked by identity
     * @param exchangerName a label identifying the exchanger in the report
     */
    public void recordExchangeStart(Exchanger<?> exchanger, String exchangerName) {
        infoFor(exchanger, exchangerName).started.incrementAndGet();
    }

    /**
     * Record a successful exchange completion.
     *
     * @param exchanger the exchanger being recorded, tracked by identity
     * @param exchangerName a label identifying the exchanger in the report
     * @param value the value handed to the partner in the exchange
     */
    public void recordExchangeComplete(Exchanger<?> exchanger, String exchangerName,
                                       @Nullable Object value) {
        infoFor(exchanger, exchangerName).completed.incrementAndGet();
        if (value == null) {
            nullValueExchanges.incrementAndGet();
        }
    }

    /**
     * Record an exchange that timed out. This ends the exchange; it is not a finding on its own.
     *
     * @param exchanger the exchanger being recorded, tracked by identity
     */
    public void recordTimeout(Exchanger<?> exchanger) {
        infoFor(exchanger, null).timedOut.incrementAndGet();
    }

    /**
     * Record an exchange that was interrupted. This ends the exchange; it is not a finding on its
     * own.
     *
     * @param exchanger the exchanger being recorded, tracked by identity
     */
    public void recordInterrupted(Exchanger<?> exchanger) {
        infoFor(exchanger, null).interrupted.incrementAndGet();
    }

    private ExchangerInfo infoFor(Exchanger<?> exchanger, @Nullable String name) {
        ExchangerInfo info = exchangerRegistry.computeIfAbsent(exchanger,
                k -> new ExchangerInfo(name != null ? name : UNREGISTERED));
        if (name != null) {
            info.nameIfUnnamed(name);
        }
        return info;
    }

    /**
     * Analyze Exchanger usage and return report.
     *
     * @return the findings this detector collected during the run
     */
    public ExchangerReport analyze() {
        List<ExchangerInfo> counts = new ArrayList<>(exchangerRegistry.size());
        for (ExchangerInfo info : exchangerRegistry.values()) {
            counts.add(info.snapshot());
        }
        return new ExchangerReport(counts, nullValueExchanges.get());
    }

    /**
     * Report class for Exchanger analysis.
     */
    public static class ExchangerReport {
        private final List<ExchangerInfo> exchangers;
        private final int nullValueExchanges;

        /**
         * Creates a ExchangerReport from the detector's pre-#585 state.
         *
         * <p>Retained for binary compatibility. Each registered exchanger contributes the counts
         * it carries; an exchanger that appears only in one of the two sets counts one timeout or
         * one interrupt. Neither set is a finding any more, so only an exchange started and never
         * ended makes {@link #hasIssues()} true.
         *
         * @param exchangerRegistry every registered exchanger and what was observed on it
         * @param timedOutExchangers the exchangers whose exchange timed out
         * @param interruptedExchangers the exchangers whose exchange was interrupted
         * @param nullValueExchanges the exchanges that transferred {@code null}
         * @deprecated the report is produced by {@link ExchangerDetector#analyze()}; this
         *     constructor has no caller in the library
         */
        @Deprecated
        public ExchangerReport(
            Map<Exchanger<?>, ExchangerInfo> exchangerRegistry,
            Set<Exchanger<?>> timedOutExchangers,
            Set<Exchanger<?>> interruptedExchangers,
            int nullValueExchanges
        ) {
            List<ExchangerInfo> counts = new ArrayList<>();
            for (ExchangerInfo info : exchangerRegistry.values()) {
                counts.add(info.snapshot());
            }
            for (Exchanger<?> exchanger : timedOutExchangers) {
                if (!exchangerRegistry.containsKey(exchanger)) {
                    counts.add(new ExchangerInfo(UNREGISTERED, 0, 0, 1, 0));
                }
            }
            for (Exchanger<?> exchanger : interruptedExchangers) {
                if (!exchangerRegistry.containsKey(exchanger)) {
                    counts.add(new ExchangerInfo(UNREGISTERED, 0, 0, 0, 1));
                }
            }
            this.exchangers = Collections.unmodifiableList(counts);
            this.nullValueExchanges = nullValueExchanges;
        }

        private ExchangerReport(List<ExchangerInfo> exchangers, int nullValueExchanges) {
            this.exchangers = Collections.unmodifiableList(exchangers);
            this.nullValueExchanges = nullValueExchanges;
        }

        /**
         * {@return whether some exchanger has an exchange that started and never ended}
         *
         * <p>A timeout or an interrupt does not count, settling #585: a thread that gave up or was
         * interrupted has left the exchange, and whether it handled that is the caller's business,
         * which the recording API cannot see. A started exchange with no completion, timeout or
         * interrupt at analysis is a thread still inside {@code exchange()}.
         *
         * <p>A null payload does not count either, settling #521. {@code Exchanger.exchange(null)}
         * is permitted by the JDK, and using an exchanger as a pure rendezvous where the handoff is
         * the synchronisation and the payload is irrelevant is a normal way to use one. The count
         * is still collected and printed as context.
         */
        public boolean hasIssues() {
            for (ExchangerInfo c : exchangers) {
                if (c.open() > 0) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("EXCHANGER ISSUES DETECTED:\n");

            int timedOut = 0;
            int interrupted = 0;
            for (ExchangerInfo c : exchangers) {
                timedOut += c.timedOut.get();
                interrupted += c.interrupted.get();
            }

            if (hasIssues()) {
                sb.append("  CRITICAL: Orphaned Exchanges:\n");
                for (ExchangerInfo c : exchangers) {
                    if (c.open() > 0) {
                        sb.append("    - ").append(c.name).append(": ")
                          .append(c.open()).append(" of ").append(c.started.get())
                          .append(" started exchange(s) never ended (completed: ").append(c.completed.get())
                          .append(", timed out: ").append(c.timedOut.get())
                          .append(", interrupted: ").append(c.interrupted.get()).append(")\n");
                    }
                }
                sb.append("  Why: An Exchanger pairs its callers two at a time. A thread that entered exchange() and never came\n");
                sb.append("       back out is still waiting for a partner that is not coming, and an untimed exchange(v) holds\n");
                sb.append("       its thread forever.\n");
                sb.append("  Fix: Make callers arrive in pairs, or use exchange(value, timeout, unit) and handle TimeoutException\n");
                sb.append("       so a caller left without a partner gives up instead of blocking.\n");
            } else {
                sb.append("  No Exchanger issues detected.\n");
            }

            if (timedOut > 0 || interrupted > 0) {
                sb.append("  Exchanges ended by a recorded timeout: ").append(timedOut)
                  .append(", by a recorded interrupt: ").append(interrupted)
                  .append(" (not findings; the thread left the exchange)\n");
            }
            if (nullValueExchanges > 0) {
                sb.append("  Null value exchanges (legal; a rendezvous carries no payload): ")
                  .append(nullValueExchanges).append(System.lineSeparator());
            }

            return sb.toString();
        }
    }

    /**
     * Internal exchanger information: the live counts while recording, and an unchanging copy of
     * them in a report.
     */
    static class ExchangerInfo {
        private volatile String name;
        final AtomicInteger started;
        final AtomicInteger completed;
        final AtomicInteger timedOut;
        final AtomicInteger interrupted;

        ExchangerInfo(String name) {
            this(name, 0, 0, 0, 0);
        }

        ExchangerInfo(String name, int started, int completed, int timedOut, int interrupted) {
            this.name = name;
            this.started = new AtomicInteger(started);
            this.completed = new AtomicInteger(completed);
            this.timedOut = new AtomicInteger(timedOut);
            this.interrupted = new AtomicInteger(interrupted);
        }

        /** Replaces the placeholder with the first real label; a real name is never overwritten. */
        synchronized void nameIfUnnamed(String label) {
            if (UNREGISTERED.equals(name)) {
                name = label;
            }
        }

        /** A copy for a report, so recording that continues cannot change what it says. */
        ExchangerInfo snapshot() {
            return new ExchangerInfo(name, started.get(), completed.get(), timedOut.get(),
                    interrupted.get());
        }

        /** Exchanges started and not ended; never negative, so an unmatched end hides nothing extra. */
        int open() {
            return Math.max(0, started.get() - completed.get() - timedOut.get() - interrupted.get());
        }
    }
}
