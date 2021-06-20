(ns br.com.souenzzo.ourives.java.io
  (:require [clojure.java.io :as io])
  (:import (java.io InputStream ByteArrayOutputStream OutputStream)
           (java.nio.charset StandardCharsets)))

(defn ^String is-read-line
  [^InputStream is]
  (loop [sb (StringBuffer.)]
    (let [c (.read is)]
      (case c
        10 (str sb)
        13 (recur sb)
        (recur (.append sb (char c)))))))

(defn ^InputStream bounded-input-stream
  [^InputStream is n]
  (let [*n (atom n)]
    (letfn [(real-len! [len]
              (apply - (swap-vals! *n (fn [n]
                                        (if (< n len)
                                          0
                                          (- n len))))))]
      (proxy [InputStream] []
        (read
          ([]
           (locking is
             (if (pos? (real-len! 1))
               (.read is)
               -1)))
          ([buffer off len]
           (locking is
             (let [real-len (real-len! len)]
               (if (pos? real-len)
                 (.read is buffer off real-len)
                 -1)))))))))


(defn bounded-output-stream
  [os n]
  os)

(defn ^InputStream chunked-input-stream
  [^InputStream is]
  (let [*done? (atom false)]
    (proxy [InputStream] []
      (read
        ([])
        ([buf off len]
         (locking is
           (let [total-n (- len off)
                 max-n (count (format "%H\r\n\r\n" total-n))
                 my-buffer (byte-array max-n)
                 actual-n (.read is my-buffer 0 max-n)
                 head (.getBytes (format "%H\r\n" actual-n)
                        StandardCharsets/UTF_8)
                 n-head (count head)
                 final-n (+ n-head actual-n)]
             (if (pos? actual-n)
               (do
                 (dotimes [i (inc final-n)]
                   (cond
                     (< i n-head) (aset-byte buf i (get head i))
                     (< i final-n) (aset-byte buf i (get my-buffer (- i n-head)))
                     :else (do
                             (aset-byte buf i (int \r))
                             (aset-byte buf (inc i) (int \n)))))
                 final-n)
               actual-n))))))))


(defn ^OutputStream chunked-output-stream
  [os]
  (proxy [OutputStream] []
    (close []
      (.write os (.getBytes "0\r\n\r\n"
                   StandardCharsets/UTF_8)))
    (write [b off len]
      (.write os (.getBytes (format "%H\r\n" len)
                   StandardCharsets/UTF_8))
      (.write os b off len)
      (.write os (.getBytes "\r\n"
                   StandardCharsets/UTF_8)))))
