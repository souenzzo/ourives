(ns br.com.souenzzo.ourives.io
  (:import (java.io InputStream)))

(defn ^String is-read-line
  [^InputStream is]
  (loop [sb (StringBuffer.)]
    (let [c (.read is)]
      (case c
        10 (str sb)
        13 (recur sb)
        (recur (.append sb (char c)))))))

(defn bounded-input-stream
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
