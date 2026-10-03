// Optional ASM pre-scanner. Its main code depends on nothing else in the project, by design.
val junitVersion = rootProject.extra["junitVersion"] as String
val asmVersion = rootProject.extra["asmVersion"] as String

dependencies {
    // ASM: static bytecode pre-scanner for Loom pinning sites (StaticPinningScanner)
    implementation("org.ow2.asm:asm:$asmVersion")

    // Test scope only, read as bytes by LibraryStateIsKeyedByIdentityTest (#803, #895); see pom.xml.
    testImplementation(project(":async-test-lib"))
    testImplementation(project(":async-test-agent"))
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
                "compiled classes before a test run. Standalone — it depends on no other module in the project."
    }
}
