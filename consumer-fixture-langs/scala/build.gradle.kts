plugins {
    scala
}

dependencies {
    testImplementation("org.scala-lang:scala3-library_3:${libs.versions.scala.version.get()}")
    // On the classpath and not started: RunnerDefaultAttachTest proves the runner attaches it by
    // default for an AsyncTestRunner body (#1068).
    testRuntimeOnly("se.deversity.async-test-lib:async-test-agent:${rootProject.extra["asyncTestVersion"]}") {
        isTransitive = false
    }
}

// In a JVM of its own: the runner decides the default attach once per JVM, at the first run, which
// in the test task would be a library-package fixture. The maven twin is the runner-default-attach
// surefire execution in scala/pom.xml.
val runnerDefaultAttach = tasks.register<Test>("runnerDefaultAttach") {
    description = "Runs RunnerDefaultAttachTest with async-test-agent on the classpath and not started."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter { includeTestsMatching("com.example.scalarunner.RunnerDefaultAttachTest") }
}

tasks.named<Test>("test") {
    filter { excludeTestsMatching("com.example.scalarunner.RunnerDefaultAttachTest") }
    dependsOn(runnerDefaultAttach)
}
