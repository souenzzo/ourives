(ns br.com.souenzzo.ourives.client
  (:refer-clojure :exclude [send])
  (:require [clojure.spec.alpha :as s]))

(s/def ::scheme #{:http :https})
(s/def ::server-name string?)
(s/def ::request (s/keys :req-un [::scheme
                                  ::server-name]))

(defprotocol IClient
  (send [this request]))
