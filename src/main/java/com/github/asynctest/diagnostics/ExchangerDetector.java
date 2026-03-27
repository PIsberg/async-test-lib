package com.github.asynctest.diagnostics;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Exchanger;

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
    private int nullValueExchanges = 0;

    /**
     * Register an Exchanger for monitoring.
     */
    public void registerExchanger(Exchanger<?> exchanger, String name) {
        exchangerRegistry.put(exchanger, new ExchangerInfo(name));
    }

    /**
     * Record a thread starting an exchange.
     */
    public void recordExchangeStart(Exchanger<?> exchanger, String exchangerName) {
        ExchangerInfo info = exchangerRegistry.get(exchanger);
        if (info != null) {
            info.startExchange();
        }
    }

    /**
     * Record a successful exchange completion.
     */
    public void recordExchangeComplete(Exchanger<?> exchanger, String exchangerName, Object value) {
        ExchangerInfo info = exchangerRegistry.get(exchanger);
        if (info != null) {
            info.completeExchange();
            if (value == null) {
                nullValueExchanges++;
            }
        }
    }

    /**
     * Record an exchange that timed out.
     */
    public void recordTimeout(Exchanger<?> exchanger) {
        timedOutExchangers.add(exchanger);
    }

    /**
     * Record an exchange that was interrupted.
     */
    public void recordInterrupted(Exchanger<?> exchanger) {
        interruptedExchangers.add(exchanger);
    }

    /**
     * Analyze Exchanger usage and return report.
     */
    public ExchangerReport analyze() {
        return new ExchangerReport(
            exchangerRegistry,
            timedOutExchangers,
            interruptedExchangers,
            nullValueExchanges
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

        public ExchangerReport(
            Map<Exchanger<?>, ExchangerInfo> exchangerRegistry,
            Set<Exchanger<?>> timedOutExchangers,
            Set<Exchanger<?>> interruptedExchangers,
            int nullValueExchanges
        ) {
            this.exchangerRegistry = exchangerRegistry;
            this.timedOutExchangers = timedOutExchangers;
            this.interruptedExchangers = interruptedExchangers;
            this.nullValueExchanges = nullValueExchanges;
        }

        public boolean hasIssues() {
            return !timedOutExchangers.isEmpty() 
                || !interruptedExchangers.isEmpty()
                || nullValueExchanges > 0;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("EXCHANGER ISSUES DETECTED:\n");

            if (!timedOutExchangers.isEmpty()) {
                sb.append("  Timed Out Exchanges:\n");
                for (Exchanger<?> exchanger : timedOutExchangers) {
                    ExchangerInfo info = exchangerRegistry.get(exchanger);
                    sb.append("    - ").append(info.name)
                      .append(" (exchange timed out - no partner thread found)\n");
                }
                sb.append("  Fix: Ensure even number of threads call exchange()\n");
                sb.append("  Or use exchange(value, timeout, unit) with proper timeout handling\n");
            }

            if (!interruptedExchangers.isEmpty()) {
                sb.append("  Interrupted Exchanges:\n");
                for (Exchanger<?> exchanger : interruptedExchangers) {
                    ExchangerInfo info = exchangerRegistry.get(exchanger);
                    sb.append("    - ").append(info.name)
                      .append(" (exchange interrupted)\n");
                }
                sb.append("  Fix: Handle InterruptedException properly and restore interrupt status\n");
            }

            if (nullValueExchanges > 0) {
                sb.append("  Null Value Exchanges: ").append(nullValueExchanges).append("\n");
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
