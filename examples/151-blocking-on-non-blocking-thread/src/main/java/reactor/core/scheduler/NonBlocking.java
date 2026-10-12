package reactor.core.scheduler;

/**
 * Stands in for reactor-core's marker interface of the same name, so this example needs no
 * dependency beyond async-test-lib. Reactor's non-blocking schedulers ({@code Schedulers.parallel()}
 * and friends) make their threads implement it, and the detector recognises it by name. In a project
 * that uses Reactor, delete this file.
 */
public interface NonBlocking {
}
