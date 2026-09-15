plugins {
    base
}

// A detector that throws during analysis fails the example instead of reading as silent
// (#612). The examples demonstrate misuse on purpose, but a crash is never the
// demonstration. The Maven side passes the same switch on the CI command line, because the
// example poms are copy-paste material and do not carry it.
subprojects {
    tasks.withType<Test>().configureEach {
        systemProperty("async-test.strict-detectors", "true")
    }
}
