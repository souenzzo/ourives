(ns petstore.main-test
  (:refer-clojure :exclude [send])
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is]]
            [ring.adapter.jetty :as jetty])
  (:import (java.net URI)
           (java.net.http HttpClient HttpHeaders HttpRequest HttpResponse$BodyHandlers)
           (java.util Optional)
           (java.util.function BiPredicate)))

(set! *warn-on-reflection* true)


(defprotocol ISendable
  (send [this ring-request]))

(extend-protocol ISendable
  HttpClient
  (send [this {:keys [request-method scheme server-name port uri query-string remote-addr headers]
               :or   {headers        {}
                      scheme         :http
                      uri            "/"
                      request-method :get}}]
    (let [http-response (.send this (proxy [HttpRequest] []
                                      (headers [] (HttpHeaders/of headers (reify BiPredicate (test [this _ _] true))))
                                      (timeout [] (Optional/empty))
                                      (expectContinue [] false)
                                      (version [] (Optional/empty))
                                      (bodyPublisher [] (Optional/empty))
                                      (uri []
                                        (URI. (name scheme) nil (or server-name remote-addr) port uri query-string nil))
                                      (method []
                                        (-> request-method name string/upper-case)))
                          (HttpResponse$BodyHandlers/ofInputStream))]
      {:headers (into {}
                  (map (fn [[k v]]
                         [k (if (next v)
                              (vec v)
                              (first v))]))
                  (.map (.headers http-response)))
       :body    (.body http-response)
       :status  (.statusCode http-response)})))


(defn petstore-handler
  [ring-request]
  {:status 202})

(deftest hello
  (let [server (jetty/run-jetty petstore-handler {:port  0
                                                  :join? false})]
    (try
      (is (= {:headers {"content-length" "0"}
              :body    "",
              :status  202}
            (-> (send (HttpClient/newHttpClient)
                  {:server-name "localhost"
                   :port        (.getPort (.getURI server))})
              (update :body slurp)
              (update :headers dissoc "date" "server")
              (doto clojure.pprint/pprint))))
      (finally
        (.stop server)))))
