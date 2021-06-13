(ns br.com.souenzzo.ourives.client
  (:refer-clojure :exclude [send send-async])
  (:require [clojure.spec.alpha :as s]))

(s/def ::scheme #{:http :https})
(s/def ::server-name string?)
(s/def ::request (s/keys :req-un [::scheme
                                  ::server-name]))

(defprotocol RingClient
  (send [this ring-request])
  (send-async [this ring-request])
  (sendAsync [this ring-request]))
