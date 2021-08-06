(ns br.com.souenzzo.ourives.java.io-test
  (:require [clojure.test :refer [deftest]]
            [br.com.souenzzo.ourives.java.io :as ojio]
            [clojure.java.io :as io]
            [midje.sweet :refer [fact =>]])
  (:import (java.io ByteArrayOutputStream)
           (java.nio.charset StandardCharsets)))

(deftest is-read-line
  (let [is (io/input-stream
             (.getBytes "abc\nefg"))]
    (fact
      (ojio/is-read-line is)
      => "abc")
    (fact
      (slurp is)
      => "efg")))

(deftest bounded-input-stream
  (let [is (io/input-stream
             (.getBytes "abc\nefg"))]
    (fact
      (slurp (ojio/bounded-input-stream is 2))
      => "ab")
    (fact
      (slurp is)
      => "c\nefg")))



(deftest chunked-output-stream
  (let [baos (ByteArrayOutputStream.)]
    (.write (ojio/chunked-output-stream baos)
      (.getBytes "abc" StandardCharsets/UTF_8))
    (fact
      (pr-str (str baos))
      => "\"3\\r\\nabc\\r\\n\"")
    (.write (ojio/chunked-output-stream baos)
      (.getBytes "abc" StandardCharsets/UTF_8)
      0 1)
    (fact
      (pr-str (str baos))
      => "\"3\\r\\nabc\\r\\n1\\r\\na\\r\\n\"")))
