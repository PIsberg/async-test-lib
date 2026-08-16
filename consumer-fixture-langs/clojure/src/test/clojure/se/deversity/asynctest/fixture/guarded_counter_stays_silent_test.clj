;; The other direction: the same write and hook inside (locking this ...). `locking` is a
;; monitorenter on the object, so the detector's Thread.holdsLock probe sees the lock and the
;; write is not reported. See lost_update_is_reported_test.clj for the gen-class notes.
(ns se.deversity.asynctest.fixture.guarded-counter-stays-silent-test
  (:import [se.deversity.asynctest AsyncFindings AsyncTestContext])
  (:gen-class
    :name se.deversity.asynctest.fixture.GuardedCounterStaysSilentTest
    :prefix "-"
    :methods [^{:static true} [^{org.junit.jupiter.api.BeforeAll {}}
                               collect [] void]
              ^{:static true} [^{org.junit.jupiter.api.AfterAll {}}
                               noRaceWasReported [] void]
              [^{se.deversity.asynctest.AsyncTest {:threads #=(int 8)
                                                    :invocations #=(int 200)
                                                    :failOn se.deversity.asynctest.FailOn/NONE
                                                    :licenseMockMode true}}
               guardedWritesFromEightThreads [] void]]))

(defonce ^:private findings (atom nil))
(defonce ^:private cell (long-array 1))

(defn -collect []
  (reset! findings (AsyncFindings/collect)))

(defn -noRaceWasReported []
  (let [^AsyncFindings f @findings]
    (try
      (.assertNotReported f "RaceConditionDetector")
      (finally
        (.close f)))))

(defn -guardedWritesFromEightThreads [this]
  (locking this
    (when-let [ctx (AsyncTestContext/get)]
      (.recordFieldWrite (.sharedRaceConditionDetector ctx) this "cell"))
    (let [^longs c cell]
      (aset c 0 (inc (aget c 0))))))
