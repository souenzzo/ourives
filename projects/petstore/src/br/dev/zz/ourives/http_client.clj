(ns br.dev.zz.ourives.http-client
  (:refer-clojure :exclude [send])
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as string])
  (:import (java.net URI)
           (java.net.http HttpClient HttpHeaders HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.util Optional)
           (java.util.function BiPredicate)))

(set! *warn-on-reflection* true)

(defprotocol ISendable
  :extend-via-metadata true
  (send [this ring-request]))

(s/def ::request-method keyword?)
(s/def ::uri string?)
(s/def ::scheme keyword?)

(extend-protocol ISendable
  HttpClient
  (send [this {:keys [request-method scheme server-name port uri query-string remote-addr headers body]
               :or   {headers {}
                      port    -1}
               :as   ring-request}]
    (let [http-response (HttpClient/send this (proxy [HttpRequest] []
                                                (headers [] (HttpHeaders/of headers (reify BiPredicate (test [this _ _] true))))
                                                (timeout [] (Optional/empty))
                                                (expectContinue [] false)
                                                (version [] (Optional/empty))
                                                (bodyPublisher [] (if body
                                                                    (Optional/of (HttpRequest$BodyPublishers/ofInputStream (delay body)))
                                                                    (Optional/empty)))
                                                (uri [] (URI. (name scheme) nil (or server-name remote-addr) port uri query-string nil))
                                                (method [] (-> request-method name string/upper-case)))
                          (HttpResponse$BodyHandlers/ofInputStream))]
      {:headers (into {}
                  (map (fn [[k v]]
                         [k (if (next v)
                              (vec v)
                              (first v))]))
                  (.map (.headers http-response)))
       :body    (.body http-response)
       :status  (.statusCode http-response)})))

