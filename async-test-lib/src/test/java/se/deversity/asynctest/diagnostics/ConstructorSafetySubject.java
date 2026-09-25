package se.deversity.asynctest.diagnostics;

import java.util.function.Consumer;

/**
 * A class whose constructor records its own construction for {@link ConstructorSafetyValidator},
 * the way the validator is meant to be fed: start as the first statement, end as the last.
 *
 * <p>{@code duringConstruction} runs between the two with {@code this}, which is where a
 * constructor that leaks its reference (registers a listener, starts a thread, stores itself in
 * a shared field) does so. The validator checks the stack for this constructor, so a test cannot
 * stand in for it with a plain {@code new Object()}.
 */
final class ConstructorSafetySubject {

    /** Assigned after {@code duringConstruction} runs, so an escaped reader can see it unset. */
    String name;

    ConstructorSafetySubject(ConstructorSafetyValidator validator,
                             Consumer<ConstructorSafetySubject> duringConstruction,
                             boolean recordEnd) {
        validator.recordConstructionStart(this);
        duringConstruction.accept(this);
        this.name = "built";
        if (recordEnd) {
            validator.recordConstructionEnd(this);
        }
    }

    /** Runs {@code body} on a new thread and waits for it, from wherever it is called. */
    static void onAnotherThread(Runnable body) {
        Thread t = new Thread(body, "constructor-safety-other");
        t.start();
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
