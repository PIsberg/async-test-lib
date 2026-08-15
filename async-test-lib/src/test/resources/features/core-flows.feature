Feature: Core @AsyncTest flows
  The behaviours a consumer relies on before reading any documentation: the body runs on every
  thread in every round, a detector finding fails the test when failOn says so and is merely
  reported when it does not, an excluded detector stays silent, and a run configured to execute
  nothing is refused rather than passed. These scenarios are executed by CoreFlowsBddTest, which
  fails the build if a scenario here has no binding or a binding has no scenario, so this file
  cannot rot into fiction.

  Scenario: Every thread runs the body in every round
    Given an @AsyncTest with 3 threads and 4 invocations
    When the JUnit engine runs it
    Then the test succeeds
    And the body ran exactly 12 times

  Scenario: A detector finding fails the test when failOn is HIGH
    Given an @AsyncTest that records an unsynchronized field write from every thread
    And detectAll is on and failOn is HIGH
    When the JUnit engine runs it
    Then the test fails
    And RaceConditionDetector reported the write

  Scenario: Report-only mode records the finding and the test stays green
    Given the same racing @AsyncTest with failOn NONE
    When the JUnit engine runs it
    Then the test succeeds
    And RaceConditionDetector reported the write

  Scenario: An excluded detector stays silent
    Given the same racing @AsyncTest with RACE_CONDITIONS excluded and failOn HIGH
    And the body records the write only when the shared detector accessor is non-null
    When the JUnit engine runs it
    Then the test succeeds
    And RaceConditionDetector reported nothing

  Scenario: A run configured to execute nothing is refused
    Given an @AsyncTest with invocations set to 0
    When the JUnit engine runs it
    Then the test fails before any thread is created
    And the failure names the invocations attribute
