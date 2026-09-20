package com.example.agentfixture;

/**
 * Inherits {@link WaitHelperBase#awaitOnce} without redeclaring it (#709).
 *
 * <p>Empty on purpose: a caller typed to this class writes this class's name at the call site,
 * while the method that waits is declared one level up. That gap is the whole fixture.
 */
public class WaitHelperSubclass extends WaitHelperBase {
}
