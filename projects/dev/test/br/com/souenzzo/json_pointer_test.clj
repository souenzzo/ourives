(ns br.com.souenzzo.json-pointer-test
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [br.com.souenzzo.json-pointer :as json-pointer]
            [clojure.spec.alpha :as s]))

(defspec escape->unescape-identity-prop
  1e3
  (prop/for-all [s (s/gen string?)]
    (= s (json-pointer/unescape (json-pointer/escape s)))))
