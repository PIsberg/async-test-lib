;; The engine from clojure.test, with no JUnit class at all.
;;
;; The gen-class fixtures next to this file prove @AsyncTest works when a Clojure namespace is
;; compiled into a JUnit class. Idiomatic Clojure tests are deftests, and a deftest cannot carry a
;; Jupiter annotation. AsyncTestRunner is the engine as a method call: build the config, hand over
;; the body (a reify of AsyncTestRunner$Body), read the findings. Both directions again, so a
;; passing run proves a detector fired and stayed silent from clojure.test, not that the call
;; returned.
;;
;; The body is a reify rather than a fn because Body declares `throws Throwable` and is a Java
;; functional interface; Clojure has no SAM conversion for fns, and reify is the idiom.
;;
;; Run by clojure-maven-plugin's `test` goal (see pom.xml), which forks a Clojure process, so
;; the surefire system properties do not reach it; licenseMockMode is set on the config instead.
(ns se.deversity.asynctest.fixture.programmatic-runner-test
  (:require [clojure.test :refer [deftest is testing]])
  (:import [java.util.concurrent.atomic AtomicInteger]
           [se.deversity.asynctest AsyncTestConfig AsyncTestContext AsyncTestRunner
                                   AsyncTestRunner$Body FailOn]))

(defn- config []
  (-> (AsyncTestConfig/builder)
      (.threads 8)
      (.invocations 200)
      (.detectAll true)            ; the builder defaults every detector to off
      (.failOn FailOn/NONE)        ; findings are asserted below, not thrown
      (.licenseMockMode true)
      (.build)))

(defn- record-write [owner]
  (when-let [ctx (AsyncTestContext/get)]
    (.recordFieldWrite (.sharedRaceConditionDetector ctx) owner "cell")))

(deftest unguarded-writes-are-reported-from-clojure-test
  (let [owner (Object.)
        runs  (AtomicInteger.)
        body  (reify AsyncTestRunner$Body
                (run [_]
                  (.incrementAndGet runs)
                  (record-write owner)))
        findings (AsyncTestRunner/run "clojure.test unguarded" (config) body)]
    (testing "the body ran threads x invocations times"
      (is (= 1600 (.get runs))))
    (testing "RaceConditionDetector reported the unguarded write"
      (is (seq (.violationsFrom findings "RaceConditionDetector"))))))

(deftest guarded-writes-stay-silent-from-clojure-test
  (let [owner (Object.)
        body  (reify AsyncTestRunner$Body
                (run [_]
                  (locking owner
                    (record-write owner))))
        findings (AsyncTestRunner/run "clojure.test guarded" (config) body)]
    (testing "a write under the owner's monitor is not a race"
      (is (empty? (.violationsFrom findings "RaceConditionDetector"))))))
