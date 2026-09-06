package se.deversity.asynctest.diagnostics;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Exchanger;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Detects Exchanger misuse patterns:
 * - Exchange timeout (exchange with timeout expiring)
 * - Missing exchange partner (odd number of threads)
 * - InterruptedException during exchange
 * - Null values being exchanged
 */
public class ExchangerDetector {

    private final Map<Exchanger<?>, ExchangerInfo> exchangerRegistry = new ConcurrentHashMap<>();
    private final Set<Exchanger<?>> timedOutExchangers = ConcurrentHashMap.newKeySet();
    private final Set<Exchanger<?>> interruptedExchangers = ConcurrentHashMap.newKeySet();
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
        // First registration wins: re-registering a subject must not discard what has
        // been observed about it. An @AsyncTest body runs once per thread, so a consumer
        // registering inside it registers once per worker.
        exchangerRegistry.putIfAbsent(exchanger, new ExchangerInfo(name));
    }

    /**
     * Record a thread starting an exchange.
     *
     * @param exchanger the exchanger being recorded, tracked by identity
     * @param exchangerName a label identifying the exchanger in the report
     */
    public void recordExchangeStart(Exchanger<?> exchanger, String exchangerName) {
        ExchangerInfo info = exchangerRegistry.get(exchanger);
        if (info != null) {
            info.startExchange();
        }
    }

    /**
     * Record a successful exchange completion.
     *
     * @param exchanger the exchanger being recorded, tracked by identity
     * @param exchangerName a label identifying the exchanger in the report
     * @param value the value handed to the partner in the exchange
     */
    public void recordExchangeComplete(Exchanger<?> exchanger, String exchangerName, Object value) {
        ExchangerInfo info = exchangerRegistry.get(exchanger);
        if (info != null) {
            info.completeExchange();
            if (value == null) {
                nullValueExchanges.incrementAndGet();
            }
        }
    }

    /**
     * Record an exchange that timed out.
     *
     * @param exchanger the exchanger being recorded, tracked by identity
     */
    public void recordTimeout(Exchanger<?> exchanger) {
        timedOutExchangers.add(exchanger);
    }

    /**
     * Record an exchange that was interrupted.
     *
     * @param exchanger the exchanger being recorded, tracked by identity
     */
    public void recordInterrupted(Exchanger<?> exchanger) {
        interruptedExchangers.add(exchanger);
    }

    /**
     * Analyze Exchanger usage and return report.
     *
     * @return the findings this detector collected during the run
     */
    public ExchangerReport analyze() {
        return new ExchangerReport(
            exchangerRegistry,
            timedOutExchangers,
            interruptedExchangers,
            nullValueExchanges.get()
        );
    }

    /**
     * Report class for Exchanger analysis.
     */
    public static class ExchangerReport {
        private final Map<Exchanger<?>, ExchangerInfo> exchangerRegistry;
        private final Set<Exchanger<?>> timedOutExchangers;
        private final Set<Exchanger<?>> interruptedExchangers;
        private final int nullValueExchanges;
        /**
         * Creates a ExchangerReport.
         *
         * @param exchangerRegistry every registered exchanger and what was observed on it
         * @param timedOutExchangers the exchangers whose exchange timed out
         * @param interruptedExchangers the exchangers whose exchange was interrupted
         * @param nullValueExchanges the exchanges that transferred {@code null}
         */
        public ExchangerReport(
            Map<Exchanger<?>, ExchangerInfo> exchangerRegistry,
            Set<Exchanger<?>> timedOutExchangers,
            Set<Exchanger<?>> interruptedExchangers,
            int nullValueExchanges
        ) {
            this.exchangerRegistry = Collections.unmodifiableMap(new HashMap<>(exchangerRegistry));
            this.timedOutExchangers = Collections.unmodifiableSet(new HashSet<>(timedOutExchangers));
            this.interruptedExchangers = Collections.unmodifiableSet(new HashSet<>(interruptedExchangers));
            this.nullValueExchanges = nullValueExchanges;
        }

        /**
         * {@return whether there are issues}
         */
        /**
         * {@return whether there are issues}
         *
         * <p>A null exchange is not one. {@code Exchanger.exchange(null)} is permitted, and
         * handing over {@code null} is the normal way to use an exchanger as a pure rendezvous:
         * the handoff is the synchronisation and the payload is irrelevant. Counting it as
         * CRITICAL said the code was wrong when it was not, so the count stays in the report as
         * context and the findings are the two grounded in something going wrong - an exchange
         * that timed out, and one that was interrupted (#517).
         */
        public boolean hasIssues() {
            return !timedOutExchangers.isEmpty()
                || !interruptedExchangers.isEmpty();
        }

        /**
         * Registry lookup that always yields a non-null {@code ExchangerInfo}.
         *
         * <p>Nothing requires a {@code record*} call's subject to have been passed to the matching
         * {@code register*} first — no precondition, no runtime check — and the two are written at
         * different places in a test. When the registration is missed the lookup returns
         * {@code null} and dereferencing it threw out of {@code toString()}. That NPE never reached
         * the user: {@code DetectorRegistry.ifIssue} catches it so one detector cannot discard the
         * whole sweep, so the finding was simply dropped and the report the user needed never
         * appeared. A placeholder keeps the finding and says plainly which subject was not
         * registered.
         */
        private ExchangerInfo infoFor(Exchanger<?> exchanger) {
            ExchangerInfo info = exchangerRegistry.get(exchanger);
            return info != null ? info : new ExchangerInfo("<unregistered exchanger>");
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("EXCHANGER ISSUES DETECTED:\n");

            if (!timedOutExchangers.isEmpty()) {
                sb.append("  Timed Out Exchanges:\n");
                for (Exchanger<?> exchanger : timedOutExchangers) {
                    ExchangerInfo info = infoFor(exchanger);
                    sb.append("    - ").append(info.name)
                      .append(" (exchange timed out - no partner thread found)\n");
                }
                sb.append("  Why: Exchanger requires exactly two threads to rendezvous simultaneously. If one thread times out or is\n");
                sb.append("       interrupted, the corresponding thread waits forever for a partner that will never arrive.\n");
                sb.append("  Fix: Ensure an even number of threads always call exchange(); use exchange(value, timeout, unit) and handle\n");
                sb.append("       TimeoutException so the orphaned thread does not block indefinitely\n");
            }

            if (!interruptedExchangers.isEmpty()) {
                sb.append("  Interrupted Exchanges:\n");
                for (Exchanger<?> exchanger : interruptedExchangers) {
                    ExchangerInfo info = infoFor(exchanger);
                    sb.append("    - ").append(info.name)
                      .append(" (exchange interrupted)\n");
                }
                sb.append("  Why: Swallowing InterruptedException leaves the interrupt flag cleared, silently preventing shutdown signals\n");
                sb.append("       from propagating up the call stack.\n");
                sb.append("  Fix: Restore the interrupt flag (Thread.currentThread().interrupt()) and rethrow or handle the interruption\n");
            }

            if (nullValueExchanges > 0) {
                sb.append("  Null value exchanges (legal; a rendezvous carries no payload): ")
                  .append(nullValueExchanges).append(System.lineSeparator());
                sb.append("  Warning: Exchanging null values may indicate logic errors\n");
            }

            if (!hasIssues()) {
                sb.append("  No Exchanger issues detected.\n");
            }

            return sb.toString();
        }
    }

    /**
     * Internal exchanger information.
     */
    static class ExchangerInfo {
        final String name;
        int startedExchanges = 0;
        int completedExchanges = 0;

        ExchangerInfo(String name) {
            this.name = name;
        }

        synchronized void startExchange() {
            startedExchanges++;
        }

        synchronized void completeExchange() {
            completedExchanges++;
        }
    }
}
