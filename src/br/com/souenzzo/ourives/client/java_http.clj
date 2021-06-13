(ns br.com.souenzzo.ourives.client.java-http
  (:refer-clojure :exclude [send])
  (:require [br.com.souenzzo.ourives.client :as client]
            [clojure.string :as string]
            [clojure.spec.alpha :as s])
  (:import (java.net.http HttpClient HttpResponse$BodyHandlers HttpRequest HttpRequest$BodyPublishers HttpClient$Version)
           (java.net URI)
           (java.util.function Supplier)))

(set! *warn-on-reflection* true)

(extend-protocol client/IClient
  HttpClient
  (send [this request]
    (when-not (s/valid? ::client/request request)
      (throw (ex-info "Missing"
               (s/explain-data ::client/request request))))
    (let [{:keys [server-name scheme server-port uri query-string request-method body headers]
           :or   {server-port    -1
                  request-method :get}} request
          scheme (name scheme)
          user-info nil
          fragment nil
          path uri
          query query-string
          method (string/upper-case (name request-method))
          uri (URI. scheme user-info server-name server-port path query fragment)
          request (cond-> (HttpRequest/newBuilder uri)
                    :always (.method method (if body
                                              (HttpRequest$BodyPublishers/ofInputStream
                                                (reify Supplier
                                                  (get [this]
                                                    body)))
                                              (HttpRequest$BodyPublishers/noBody)))
                    (seq headers) (.headers (into-array String (sequence cat headers)))
                    :always (.version HttpClient$Version/HTTP_1_1)
                    :always .build)
          response (.send this request (HttpResponse$BodyHandlers/ofString))]
      {:status  (.statusCode response)
       :body    (.body response)
       :headers (into {}
                  (map (fn [[k vs]]
                         [k (if (next vs)
                              (vec vs)
                              (first vs))]))
                  (.map (.headers response)))})))
