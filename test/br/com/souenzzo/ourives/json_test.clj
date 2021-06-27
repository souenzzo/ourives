(ns br.com.souenzzo.ourives.json-test
  (:require [clojure.test :refer [deftest]]
            [midje.sweet :refer [fact =>]]
            [br.com.souenzzo.ourives.json :as json]
            [br.com.souenzzo.ourives.client :as client]))

(deftest hello
  (let [client (-> (reify client/RingClient
                     (send [this ring-request]
                       {:body   (slurp (:body ring-request))
                        :status 200}))
                 (json/client {}))]
    (fact
      (-> client
        (client/send {::json/source       ::from
                      ::json/read-options [:key-fn keyword]
                      ::json/target       ::to
                      ::from              {:a 42}})
        ::to)
      => {:a 42})))
