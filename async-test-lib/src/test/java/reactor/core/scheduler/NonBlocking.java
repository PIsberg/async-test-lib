package reactor.core.scheduler;

/**
 * Test stand-in for Reactor's marker interface, by its real name: {@code BlockingOnNonBlockingThreadDetector}
 * classifies a thread by the names its type implements, since the library cannot depend on Reactor.
 */
public interface NonBlocking {
}
