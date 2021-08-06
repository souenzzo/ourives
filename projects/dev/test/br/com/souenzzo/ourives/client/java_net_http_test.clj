(ns br.com.souenzzo.ourives.client.java-net-http-test
  (:require [clojure.test :refer [deftest]]
            [br.com.souenzzo.ourives.client.java-net-http]
            [midje.sweet :refer [fact =>]]
            [br.com.souenzzo.ourives.client :as client]
            [io.pedestal.http :as http]
            [io.pedestal.interceptor :as interceptor]
            [clojure.core.async :as async]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [clojure.spec.gen.alpha :as gen]
            [clojure.test.check :as tc]
            [clojure.string :as string])
  (:import (java.lang AutoCloseable)
           (java.net.http HttpClient)
           (java.net URLDecoder URLEncoder)
           (java.nio.charset StandardCharsets)))

(set! *warn-on-reflection* true)

(def all-ring-keys
  [:body :headers :protocol :query-string :request-method :scheme :server-name :server-port :uri])

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

(defn get-ring-request-from-the-server
  [ring-request]
  (let [*req (promise)]
    (with-open [srv (simple-server 8080 (fn [req]
                                          (deliver *req req)
                                          {:headers {"Hello" "World"}
                                           :body    "Hello!"
                                           :status  200}))]
      (client/send (HttpClient/newHttpClient)
        (merge {:request-method :get
                :scheme         :http
                :server-port    8080
                :protocol       "HTTP/1.1"
                :server-name    "localhost"}
          ring-request)))
    (select-keys @*req all-ring-keys)))

(defspec preserve-uri 100
  (prop/for-all [v (gen/such-that
                     #(not (string/includes? % "+"))
                     (gen/string))]
    (let [v (str "/" v)]
      (= v
        (URLDecoder/decode
          (str (:uri (get-ring-request-from-the-server
                       {:uri v})))
          StandardCharsets/UTF_8)))))


(defspec preserve-query-string 100
  (prop/for-all [v (gen/such-that
                     #(not (string/includes? % "+"))
                     (gen/string))]
    (let [v v]
      (= v
        (URLDecoder/decode
          (str (:query-string (get-ring-request-from-the-server
                                {:query-string v})))
          StandardCharsets/UTF_8)))))



(defspec preserve-header 100
  (prop/for-all [v (gen/such-that
                     #(not (string/includes? % "+"))
                     (gen/string))]
    (let [v (URLEncoder/encode (str v)
              StandardCharsets/UTF_8)]
      (= v
        (str (get (:headers (get-ring-request-from-the-server
                              {:headers {"Hello" v}}))
               "hello"))))))

(comment
  (tc/quick-check 100 preserve-uri))
(deftest hello
  (let [client (HttpClient/newHttpClient)
        *req (atom nil)]
    (with-open [srv (simple-server 8080 (fn [req]
                                          (reset! *req req)
                                          {:headers {"Hello" "World"}
                                           :body    "Hello!"
                                           :status  200}))]
      (fact
        "Simple client request"
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
        "Check server request"
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
            :uri              "/"})
      (fact
        "They should be equal"
        (-> client
          (client/send (-> (select-keys @*req all-ring-keys)
                         (dissoc :body)
                         (update :headers dissoc "host" "content-length")))
          (update :headers dissoc "date")
          (update :body slurp))
        => {:body    "Hello!"
            :headers {"content-type"      "text/plain"
                      "hello"             "World"
                      "transfer-encoding" "chunked"}
            :status  200})
      (fact
        "Check server 2 request"
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

(deftest hello-async
  (let [client (HttpClient/newHttpClient)
        *req (promise)]
    (with-open [srv (simple-server 8080 (fn [req]
                                          (deliver *req req)
                                          {:headers {"Hello" "World"}
                                           :body    "Hello!"
                                           :status  200}))]
      (fact
        (-> client
          (client/send-async {:request-method :get
                              :scheme         :http
                              :server-port    8080
                              :protocol       "HTTP/1.1"
                              :server-name    "localhost"})
          async/<!!
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
