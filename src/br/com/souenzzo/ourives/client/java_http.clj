(ns br.com.souenzzo.ourives.client.java-http
  (:refer-clojure :exclude [send])
  (:require [br.com.souenzzo.ourives.client :as client]
            [clojure.string :as string]
            [clojure.spec.alpha :as s]
            [clojure.core.async :as async])
  (:import (java.net.http HttpClient HttpResponse$BodyHandlers HttpRequest HttpRequest$BodyPublishers HttpClient$Version HttpResponse)
           (java.net URI)
           (java.util.function Supplier Function)
           (java.util.concurrent CompletableFuture)))

(set! *warn-on-reflection* true)

(defn ring-request->http-request
  [{:keys [body #_character-encoding #_content-length #_content-type headers protocol query-string #_remote-addr
           request-method scheme server-name server-port #_ssl-client-cert uri]
    :or   {server-port    -1
           request-method :get}
    :as   ring-request}]
  (when-not (s/valid? ::client/request ring-request)
    (throw (ex-info "Missing"
             (s/explain-data ::client/request ring-request))))

  (let [scheme (name scheme)
        user-info nil
        fragment nil
        path uri
        query query-string
        method (string/upper-case (name request-method))
        uri (URI. scheme user-info server-name server-port path query fragment)]
    (cond-> (HttpRequest/newBuilder uri)
      :always (.method method (if body
                                (HttpRequest$BodyPublishers/ofInputStream
                                  (reify Supplier
                                    (get [this]
                                      body)))
                                (HttpRequest$BodyPublishers/noBody)))
      (seq headers) (.headers (into-array String (sequence cat headers)))
      (= protocol "HTTP/1.1") (.version HttpClient$Version/HTTP_1_1)
      (= protocol "HTTP/2") (.version HttpClient$Version/HTTP_2)
      :always .build)))

(defn response->ring-response
  [^HttpResponse response]
  {:status  (.statusCode response)
   :body    (.body response)
   :headers (into {}
              (map (fn [[k vs]]
                     [k (if (next vs)
                          (vec vs)
                          (first vs))]))
              (.map (.headers response)))})

(extend-protocol client/RingClient
  HttpClient
  (send [this ring-request]
    (let [request (ring-request->http-request ring-request)
          response (.send this request (HttpResponse$BodyHandlers/ofString))]
      (response->ring-response response)))
  (sendAsync [this ring-request]
    (let [request (ring-request->http-request ring-request)
          response (.sendAsync this request (HttpResponse$BodyHandlers/ofString))]
      (.thenApply response (reify Function
                             (apply [this v]
                               (response->ring-response v))))))
  (send-async [this ring-request]
    (let [response ^CompletableFuture (client/sendAsync this ring-request)
          return (async/promise-chan)]
      (.thenApply response (reify Function
                             (apply [this v]
                               (async/put! return v))))
      return)))
