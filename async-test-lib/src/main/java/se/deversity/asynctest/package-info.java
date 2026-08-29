/**
 * The core artifact: annotations, configuration, the runner, the extension and the detectors.
 *
 * <p>This is what a consumer puts on their test classpath, and the reason the weaving and
 * bytecode-scanning libraries are not in it. {@code async-test-agent} carries Byte Buddy and
 * {@code async-test-analysis} carries ASM; both are optional artifacts a user opts into, and
 * neither may be reached from here. A dependency added in this direction would put a bytecode
 * library on every consumer's default test classpath, which is the cost the three-module split
 * exists to avoid.
 *
 * <p>Enforced by {@code ArchitectureTest}: {@code bytebuddy_is_confined_to_the_agent} and
 * {@code asm_is_confined_to_analysis}, plus eighteen other rules.
 */
@AIArchitecture(
    belongsTo = "core",
    cannotReference = {
        "net.bytebuddy",
        "org.objectweb.asm",
        "se.deversity.asynctest.agent",
        "se.deversity.asynctest.analysis"
    }
)
package se.deversity.asynctest;

import se.deversity.vibetags.annotations.AIArchitecture;
