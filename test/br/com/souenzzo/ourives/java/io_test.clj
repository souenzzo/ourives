(ns br.com.souenzzo.ourives.java.io-test
  (:require [clojure.test :refer [deftest]]
            [br.com.souenzzo.ourives.java.io :as ojio]
            [clojure.java.io :as io]
            [midje.sweet :refer [fact =>]])
  (:import (java.io ByteArrayOutputStream)))

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
    (fact
      (slurp (ojio/chunked-output-stream baos))
      => "ab")
    (fact
      (str baos)
      => "c\nefg")))

