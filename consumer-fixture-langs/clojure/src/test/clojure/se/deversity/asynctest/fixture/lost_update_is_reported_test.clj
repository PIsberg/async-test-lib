;; @AsyncTest from Clojure, the direction that must fire: eight threads write the same cell with
;; no lock, and RaceConditionDetector reports it.
;;
;; Clojure has no annotation syntax, so the test class is a gen-class and the JUnit annotations
;; are metadata on the method signatures. Three things that are not obvious and cost real time:
;;
;;   1. int annotation elements need an actual Integer. Clojure literals are Long, and gen-class
;;      hands the value to ASM unchanged, so `{:threads 8}` compiles but JUnit discovery throws
;;      AnnotationTypeMismatchException ("Found data of type java.lang.Long[8]"). `#=(int 8)`
;;      read-evaluates to an Integer and works; `(int 8)` without `#=` does not, because
;;      metadata is not evaluated.
;;   2. Static lifecycle methods (@BeforeAll, @AfterAll) take ^{:static true} on the signature
;;      vector, not on the method name. On the name it compiles to an instance method and JUnit
;;      refuses it.
;;   3. The namespace must be AOT-compiled (clojure-maven-plugin testCompile) and Surefire must
;;      be told to include **/*Test.class, because the class name comes from :name, not the file.
;;
;; The finding is asserted in @AfterAll because detectors analyse after the last round.
(ns se.deversity.asynctest.fixture.lost-update-is-reported-test
  (:import [se.deversity.asynctest AsyncFindings AsyncTestContext])
  (:gen-class
    :name se.deversity.asynctest.fixture.LostUpdateIsReportedTest
    :prefix "-"
    :methods [^{:static true} [^{org.junit.jupiter.api.BeforeAll {}}
                               collect [] void]
              ^{:static true} [^{org.junit.jupiter.api.AfterAll {}}
                               theRaceWasReported [] void]
              [^{se.deversity.asynctest.AsyncTest {:threads #=(int 8)
                                                    :invocations #=(int 200)
                                                    :failOn se.deversity.asynctest.FailOn/NONE
                                                    :licenseMockMode true}}
               unguardedWritesFromEightThreads [] void]]))

(defonce ^:private findings (atom nil))
(defonce ^:private cell (long-array 1))

(defn -collect []
  (reset! findings (AsyncFindings/collect)))

(defn -theRaceWasReported []
  (let [^AsyncFindings f @findings]
    (try
      (.assertReported f "RaceConditionDetector")
      (finally
        (.close f)))))

(defn -unguardedWritesFromEightThreads [this]
  (when-let [ctx (AsyncTestContext/get)]
    (.recordFieldWrite (.sharedRaceConditionDetector ctx) this "cell"))
  (let [^longs c cell]
    (aset c 0 (inc (aget c 0)))))
