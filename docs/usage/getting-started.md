# Getting started

Part of the [Usage guide](../USAGE.md).

## Installation

### Via Maven (GitHub Packages)

Add to your `pom.xml`:

```xml
<dependency>
    <groupId>se.deversity.async-test-lib</groupId>
    <artifactId>async-test-lib</artifactId>
    <version>1.12.1</version>
    <scope>test</scope>
</dependency>
```

Configure the GitHub Packages repository in your `pom.xml`:

```xml
<repositories>
    <repository>
        <id>github</id>
        <name>GitHub Packages</name>
        <url>https://repo1.maven.org/maven2</url>
        <releases>
            <enabled>true</enabled>
        </releases>
        <snapshots>
            <enabled>true</enabled>
        </snapshots>
    </repository>
</repositories>
```

### Via Gradle

```gradle
repositories {
    maven {
        url = uri("https://repo1.maven.org/maven2")
        credentials {
            username = project.findProperty("gpr.user") ?: System.getenv("USERNAME")
            password = project.findProperty("gpr.key") ?: System.getenv("TOKEN")
        }
    }
}

dependencies {
    testImplementation 'se.deversity.async-test-lib:async-test-lib:1.12.1'
}
```

## Basic Usage

### 1. Import the annotation

```java
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.DetectorType;
```

### 2. Annotate your test method

```java
public class MyAsyncTests {
    
    @AsyncTest(
        threads = 10,
        invocations = 100,
        detectAll = true
    )
    void testConcurrentAccess() {
        // All 146 detectors are enabled!
    }
}
```
