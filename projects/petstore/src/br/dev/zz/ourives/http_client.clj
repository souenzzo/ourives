(ns br.dev.zz.ourives.http-client
  (:refer-clojure :exclude [send])
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as string])
  (:import (java.net URI)
           (java.net.http HttpClient HttpClient$Version HttpHeaders HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
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
  (send [this {:keys [request-method scheme server-name port uri query-string remote-addr headers body protocol]
               :or   {headers {} port -1}}]
    (let [uri (URI. (name scheme) nil (or server-name remote-addr) port uri query-string nil)
          method (-> request-method name string/upper-case)
          headers (HttpHeaders/of headers (reify BiPredicate (test [this _ _] true)))
          version (if protocol
                    (Optional/of (HttpClient$Version/valueOf (string/replace protocol #"[\./]" "_")))
                    (Optional/empty))
          body-publisher (if body
                           (Optional/of (HttpRequest$BodyPublishers/ofInputStream (delay body)))
                           (Optional/empty))
          http-response (HttpClient/send this (proxy [HttpRequest] []
                                                (headers [] headers)
                                                (timeout [] (Optional/empty))
                                                (expectContinue [] false)
                                                (version [] version)
                                                (bodyPublisher [] body-publisher)
                                                (uri [] uri)
                                                (method [] method))
                          (HttpResponse$BodyHandlers/ofInputStream))]
      {:headers (into {}
                  (map (fn [[k v]]
                         [k (if (next v)
                              (vec v)
                              (first v))]))
                  (.map (.headers http-response)))
       :body    (.body http-response)
       :status  (.statusCode http-response)})))

