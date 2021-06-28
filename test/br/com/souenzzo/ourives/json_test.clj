(ns br.com.souenzzo.ourives.json-test
  (:require [clojure.test :refer [deftest]]
            [midje.sweet :refer [fact =>]]
            [br.com.souenzzo.ourives.json :as json]
            [br.com.souenzzo.ourives.client :as client]
            [br.com.souenzzo.ourives.client.java-net-http]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.http :as http]
            [clojure.java.io :as io]
            [clojure.core.async :as async])
  (:import (java.lang AutoCloseable)
           (java.net.http HttpClient)))


(defn ^AutoCloseable simple-server
  [port handler]
  (let [server (-> {::http/interceptors
                                 [(interceptor/interceptor {:enter (fn [{:keys [request]
                                                                         :as   ctx}]
                                                                     (assoc ctx
                                                                       :response (handler request)))})]
                    ::http/type  :jetty
                    ::http/port  port
                    ::http/join? false}
                 http/create-server
                 http/start)]
    (reify AutoCloseable
      (close [this]
        (http/stop server)))))


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

(deftest compose
  (let [client (-> (HttpClient/newHttpClient)
                 (json/client {}))]
    (with-open [srv (simple-server 8080 (fn [ring-request]
                                          {:body   (-> ring-request
                                                     (assoc ::json/target ::value)
                                                     json/parse-response
                                                     ::value)
                                           :status 200}))]
      (fact
        (-> client
          (client/send {:server-port    8080
                        :protocol       "HTTP/1.1"
                        :server-name    "localhost"
                        :uri            "/"
                        :scheme         :http
                        :request-method :post
                        ::json/source   ::from
                        ::json/target   ::to
                        ::from          {:hello "World"}})
          ::to)
        => {"hello" "World"}))))


(deftest compose-async
  (let [client (-> (HttpClient/newHttpClient)
                 (json/client {}))]
    (with-open [srv (simple-server 8080 (fn [ring-request]
                                          {:body   (-> ring-request
                                                     (assoc ::json/target ::value)
                                                     json/parse-response
                                                     ::value)
                                           :status 200}))]
      (fact
        (-> client
          (client/send-async {:server-port    8080
                              :protocol       "HTTP/1.1"
                              :server-name    "localhost"
                              :uri            "/"
                              :scheme         :http
                              :request-method :post
                              ::json/source   ::from
                              ::json/target   ::to
                              ::from          {:hello "World"}})
          async/<!!
          ::to)
        => {"hello" "World"}))))
