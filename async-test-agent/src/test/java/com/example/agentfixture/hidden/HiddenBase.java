package com.example.agentfixture.hidden;

/**
 * Package-private, so {@code HiddenBase.class} cannot be loaded as a constant from another package
 * (#1362): a weaver that names the declaring class there must fall back to the instruction's owner.
 */
class HiddenBase {
    public static volatile int flag;
}
