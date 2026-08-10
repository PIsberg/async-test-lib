package se.deversity.asynctest.example.service;

/**
 * Application configuration, initialised once from a static block.
 *
 * <p>This class and {@link Registry} reference each other from their static initialisers.
 * Read either one alone and it looks ordinary; the cycle only exists across the pair, which is
 * why this survives review and then hangs in production.
 *
 * <p>What the JVM does, per JLS 12.4.2: the first thread to touch a class takes that class's
 * initialisation lock and runs {@code <clinit>}. Any other thread that touches the same class
 * blocks until the first finishes. With two threads entering the cycle from opposite ends:
 *
 * <pre>
 *   thread-a: touches Config   -> holds Config's init lock   -> needs Registry
 *   thread-b: touches Registry -> holds Registry's init lock -> needs Config
 * </pre>
 *
 * <p>Neither can proceed. It is a textbook circular wait, with one property that makes it far
 * worse than a deadlock on two monitors: <b>class initialisation locks are not monitors</b>.
 * They do not appear in {@code ThreadMXBean.findDeadlockedThreads()}, they are not visible in
 * a thread dump as a lock edge, and the threads show up merely as parked. The platform's own
 * deadlock detector reports nothing at all, which is exactly why this detector exists.
 *
 * <p>The fix is to break the cycle rather than to reorder it. Initialise lazily through a
 * holder class, or move the shared constant into a third class that neither initialiser needs
 * to call back into.
 */
public final class Config {

    /** BUG: touches Registry from Config's own static initialiser. */
    public static final String ENDPOINT = Registry.lookup("endpoint");

    private Config() {
    }

    public static String describe() {
        return "Config[endpoint=" + ENDPOINT + "]";
    }

    /**
     * The fix: a holder class defers initialisation until first use, so no thread is inside
     * {@code Config.<clinit>} while it needs {@code Registry}, and the cycle never forms.
     */
    public static final class LazyEndpoint {
        private static final class Holder {
            static final String VALUE = Registry.lookup("endpoint");
        }

        private LazyEndpoint() {
        }

        public static String value() {
            return Holder.VALUE;
        }
    }
}
