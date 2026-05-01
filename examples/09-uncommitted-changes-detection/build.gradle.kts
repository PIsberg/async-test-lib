plugins {
    java
}

group = "se.deversity.async-test-lib"
version = "1.0.0"

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    testImplementation("se.deversity.async-test-lib:async-test-lib:0.7.0")
    testImplementation("org.junit.jupiter:junit-jupiter:6.0.3")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("license.mock.mode", "true")
}
