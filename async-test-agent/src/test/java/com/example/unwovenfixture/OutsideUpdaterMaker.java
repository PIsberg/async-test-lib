package com.example.unwovenfixture;

import com.example.agentfixture.OutsideUpdaterTargetBean;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

/**
 * Makes an updater on a woven class's field from outside that class's hierarchy, in a package the
 * weaver never scans (#659).
 *
 * <p>Nothing records this binding, so the only record for {@link OutsideUpdaterTargetBean} is its
 * own {@code busy} updater, and resolving {@link #STATE} from the receiver's recorded fields would
 * name the wrong flag.
 */
public final class OutsideUpdaterMaker {

    /** An updater on {@code OutsideUpdaterTargetBean.state}, made where no weaver looks. */
    public static final AtomicIntegerFieldUpdater<OutsideUpdaterTargetBean> STATE =
            AtomicIntegerFieldUpdater.newUpdater(OutsideUpdaterTargetBean.class, "state");

    private OutsideUpdaterMaker() {
    }

    /** Forces initialisation, so the type initializer runs before the agent attaches. */
    public static void initialise() {
        // Nothing to do: calling a static method is what initialises the class.
    }
}
