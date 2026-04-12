plugins {
    java
}

group = "se.deversity.async-test-lib"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
    // Resolves the locally published async-test-lib artifact (run publishToMavenLocal first)
    mavenLocal()
}

val asyncTestVersion = "1.2.0"
val junitVersion = "5.10.2"

dependencies {
    testImplementation("se.deversity.async-test-lib:async-test-lib:$asyncTestVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
}

tasks.test {
    useJUnitPlatform()
}
