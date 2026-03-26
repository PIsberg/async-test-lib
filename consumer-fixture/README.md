# Consumer Fixture

This Maven project exercises `async-test` as a downstream consumer would:

- it depends on the built `com.github.asynctest:async-test` artifact
- it runs its own JUnit test suite
- it only uses the library's public API

Run it after installing the library locally:

```powershell
mvn clean install
mvn -f consumer-fixture\pom.xml test
```
