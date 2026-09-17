package com.example.unwovenfixture;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

/**
 * A superclass that binds its own spinlock updater, in a package the agent is not told to weave.
 *
 * <p>The weaver never scans this class, so the registry has no record that {@code STATE} exists.
 * A subclass that is woven records only its own updater field, and "exactly one recorded field in
 * the hierarchy" would then name the subclass's flag for a swap through {@code STATE} (#619).
 */
public class UnwovenUpdaterBase {

    protected static final AtomicIntegerFieldUpdater<UnwovenUpdaterBase> STATE =
            AtomicIntegerFieldUpdater.newUpdater(UnwovenUpdaterBase.class, "state");

    protected volatile int state;
}