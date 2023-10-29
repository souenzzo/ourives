(ns br.dev.zz.ourives.pedestal
  (:refer-clojure :exclude [type])
  (:require [br.dev.zz.ourives.alpha :as ourives]
            [io.pedestal.http :as http]
            [io.pedestal.interceptor.chain :as chain]
            [io.pedestal.log :as log])
  (:import (java.lang AutoCloseable)
           (java.net InetSocketAddress ServerSocket)))

(set! *warn-on-reflection* true)

(defn chain-provider
  [{::http/keys [interceptors]
    :as         service-map}]
  (letfn [(ring-handler [request]
            (-> service-map
              (assoc #_#_::ring-handler ring-handler
                     :request (merge {:path-info (:uri request)}
                                request))
              (chain/execute interceptors)
              :response))]
    (assoc service-map ::ring-handler ring-handler)))

(defn type
  [{::keys [ring-handler]} {:keys [port]}]
  (let [*server (delay (doto (ServerSocket.)
                         (.bind (InetSocketAddress. port))))]
    {:server   *server
     :start-fn (fn []
                 (log/info)
                 (ourives/start-accept! @*server ring-handler))

     :stop-fn  (fn []
                 (when (realized? *server)
                   (.close ^AutoCloseable @*server)
                   (log/info :message "Stopped")))}))

(def service-map
  {::http/chain-provider chain-provider
   ::http/type           type})
