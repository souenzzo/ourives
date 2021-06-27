(ns br.com.souenzzo.ourives.client.java-net-http-test
  (:require [clojure.test :refer [deftest]]
            [br.com.souenzzo.ourives.client.java-net-http]
            [midje.sweet :refer [fact =>]]
            [br.com.souenzzo.ourives.client :as client]
            [io.pedestal.http :as http]
            [io.pedestal.interceptor :as interceptor])
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
  (let [client (HttpClient/newHttpClient)
        *req (promise)]
    (with-open [srv (simple-server 8080 (fn [req]
                                          (deliver *req req)
                                          {:headers {"Hello" "World"}
                                           :body    "Hello!"
                                           :status  200}))]
      (fact
        (-> client
          (client/send {:request-method :get
                        :scheme         :http
                        :server-port    8080
                        :protocol       "HTTP/1.1"
                        :server-name    "localhost"})
          (update :headers dissoc "date")
          (update :body slurp))
        => {:body    "Hello!"
            :headers {"content-type"      "text/plain"
                      "hello"             "World"
                      "transfer-encoding" "chunked"}
            :status  200})
      (fact
        (-> @*req
          (dissoc :servlet :servlet-request :servlet-response :body)
          (update :headers dissoc "user-agent"))
        => {:async-supported? true
            :content-length   0
            :context-path     ""
            :headers          {"content-length" "0"
                               "host"           "localhost:8080"}
            :path-info        "/"
            :protocol         "HTTP/1.1"
            :query-string     nil
            :remote-addr      "127.0.0.1"
            :request-method   :get
            :scheme           :http
            :server-name      "localhost"
            :server-port      8080
            :uri              "/"}))))