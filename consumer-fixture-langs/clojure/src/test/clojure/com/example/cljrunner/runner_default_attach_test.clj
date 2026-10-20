;; AsyncTestRunner from Clojure with async-test-agent on the test classpath and no flag (#1042,
;; #1068). The body is a reify of AsyncTestRunner$Body, compiled into this namespace's package, so
;; the runner scopes the agent to com.example.cljrunner; the bare increment of a deftype's mutable
;; field is reported and the one under `locking` is not.
;;
;; A gen-class JUnit test rather than a deftest, so Surefire can run it in a JVM of its own: the
;; runner decides the default attach once per JVM, and only while no agent is running. Its
;; execution is runner-default-attach in pom.xml.
(ns com.example.cljrunner.runner-default-attach-test
  (:import [org.junit.jupiter.api Assertions]
           [se.deversity.asynctest AsyncFindings AsyncTestConfig AsyncTestRunner AsyncTestRunner$Body
                                   DetectorType]
           [se.deversity.asynctest.telemetry TelemetryRegistry])
  (:gen-class
    :name com.example.cljrunner.RunnerDefaultAttachTest
    :prefix "-"
    :methods [[^{org.junit.jupiter.api.Test {}} aBareIncrementInAClojureBodyIsReportedWithNoFlag [] void]]))

(definterface Increments
  (^void incrementRacing [])
  (^void incrementGuarded []))

;; Field instructions the agent can weave: a mutable deftype field is a plain JVM field.
(deftype Counters [lock ^:unsynchronized-mutable ^long racing ^:unsynchronized-mutable ^long guarded]
  Increments
  (incrementRacing [_] (set! racing (inc racing)))
  (incrementGuarded [_] (locking lock (set! guarded (inc guarded)))))

(defn- reported? [^AsyncFindings findings field]
  (boolean (some (fn [v] (and (.contains (.detector v) "AtomicityValidator")
                              (.contains (str (.get (.attributes v) "report")) field)))
                 (.violations findings))))

(defn -aBareIncrementInAClojureBodyIsReportedWithNoFlag [_]
  (Assertions/assertFalse (TelemetryRegistry/isRunning) "an agent is already running; this needs a JVM without one")
  (let [^Increments counters (Counters. (Object.) 0 0)
        config (-> (AsyncTestConfig/builder) (.threads 4) (.invocations 25)
                   (.includes (into-array DetectorType [DetectorType/ATOMICITY_VIOLATIONS]))
                   (.timeoutMs 60000) (.licenseMockMode true) (.build))
        body (reify AsyncTestRunner$Body
               (run [_]
                 (.incrementRacing counters)
                 (.incrementGuarded counters)))
        findings (AsyncTestRunner/run config body)]
    (Assertions/assertTrue (TelemetryRegistry/isRunning) "the runner should have attached the agent")
    (Assertions/assertTrue (reported? findings "Counters.racing")
                           (str "the bare increment was not reported: " (.violations findings)))
    (Assertions/assertFalse (reported? findings "Counters.guarded")
                            (str "the guarded increment was reported: " (.violations findings)))))
