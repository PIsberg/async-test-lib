package reactor.core.scheduler;

/**
 * Stands in for reactor-core's marker interface of the same name, so this fixture needs no Reactor
 * dependency: the detector recognises a non-blocking scheduler thread by the interface's name.
 */
public interface NonBlocking {
}
