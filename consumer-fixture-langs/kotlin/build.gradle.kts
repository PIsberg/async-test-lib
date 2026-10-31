plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

// Gradle twin of the -javaagent argLine in kotlin/pom.xml (#714). The wait gates need the agent
// weaving Kotlin bytecode, and premain rather than a self-attach: a self-attach that is refused
// turns them into skips, and a skipped gate is not a passed gate. Its own configuration, not
// testRuntimeOnly, because what is wanted is the jar's path and not its contents on the
// classpath; non-transitive because the published agent jar already shades byte-buddy.
val asyncTestAgent = configurations.create("asyncTestAgent") {
    isTransitive = false
}

dependencies {
    // The real dispatcher runBlocking is woven against (#1007).
    testImplementation(
        "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:${libs.versions.kotlinx.coroutines.version.get()}"
    )
    // KotlinSuspendAsyncTestFailsTest drives the real launcher (#1146); the root build adds it
    // only at runtime.
    testImplementation("org.junit.platform:junit-platform-launcher:${libs.versions.junit.platform.version.get()}")
    asyncTestAgent("se.deversity.async-test-lib:async-test-agent:${rootProject.extra["asyncTestVersion"]}")
}

// AsyncTestRunner from Kotlin with the agent only on the classpath (#1068): no -javaagent, so the
// runner attaches it, scoped to the package of the body's own class. A JVM of its own, because the
// default only applies while no agent is running. The maven twin is the runner-default-attach
// surefire execution in kotlin/pom.xml.
val runnerDefaultAttach = tasks.register<Test>("runnerDefaultAttach") {
    description = "Runs RunnerDefaultAttachTest with async-test-agent on the classpath and not started."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath + asyncTestAgent
    filter { includeTestsMatching("com.example.kotlinrunner.RunnerDefaultAttachTest") }
}

tasks.named<Test>("test") {
    filter {
        excludeTestsMatching("com.example.kotlinrunner.RunnerDefaultAttachTest")
        // Nested classes are fixtures run through the launcher from the test around them, such as
        // KotlinSuspendAsyncTestFailsTest's suspend body (#1146), not tests of their own. Surefire
        // never selects them (its default excludes **/*$*); Gradle hands every class to JUnit.
        excludeTestsMatching("*$*")
    }
    dependsOn(runnerDefaultAttach)
    // doFirst so the configuration is resolved when the task runs rather than while the build is
    // being configured. includes= is scoped to the bean package so the two race-condition
    // fixtures next door keep running unwoven; kotlinx.coroutines is woven too, so the start of a
    // dispatcher worker inside its scheduler carries the run to it (#1007).
    doFirst {
        jvmArgs(
            "-javaagent:${asyncTestAgent.singleFile}" +
                "=includes=com.example.kotlinfixture;kotlinx.coroutines,collections=true"
        )
    }
}
