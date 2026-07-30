// Optional ASM pre-scanner. Depends on nothing else in the project, by design.
val junitVersion = rootProject.extra["junitVersion"] as String
val asmVersion = rootProject.extra["asmVersion"] as String

dependencies {
    // ASM: static bytecode pre-scanner for Loom pinning sites (StaticPinningScanner)
    implementation("org.ow2.asm:asm:$asmVersion")

    testImplementation("org.junit.jupiter:junit-jupiter-api:$junitVersion")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:$junitVersion")
}

mavenPublishing {
    coordinates(
        groupId = project.group.toString(),
        artifactId = "async-test-analysis",
        version = project.version.toString()
    )
    pom {
        name = "Async Test Library — static analysis"
        description = "Optional ASM-based pre-scanner for async-test-lib: finds Loom pinning sites in " +
                "compiled classes before a test run."
    }
}
