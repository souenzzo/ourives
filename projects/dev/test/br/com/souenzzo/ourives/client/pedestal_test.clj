(ns br.com.souenzzo.ourives.client.pedestal-test
  (:require [clojure.test :refer [deftest]]
            [midje.sweet :refer [fact =>]]
            [br.com.souenzzo.ourives.client.pedestal :as ocp]
            [br.com.souenzzo.ourives.client :as client]
            [io.pedestal.http :as http]
            [io.pedestal.test :refer [response-for]]
            [io.pedestal.interceptor :as interceptor]
            [clojure.java.io :as io]
            [clojure.core.async :as async])
  (:import (java.nio.charset StandardCharsets)))

(def ring-keys
  [:body :headers :protocol :query-string :request-method :scheme :server-name :server-port :uri])

(def pedestal-keys
  [:protocol :async-supported? :remote-addr :headers :server-port :content-length :content-type :path-info :character-encoding :uri :server-name :query-string :body :scheme :request-method :context-path])

(deftest simple-get
  (let [*request (atom nil)
        {::http/keys [service-fn]
         ::ocp/keys  [client]} (-> {::http/interceptors
                                    [(interceptor/interceptor
                                       {:enter (fn [{:keys [request]
                                                     :as   ctx}]
                                                 (reset! *request (select-keys request ring-keys))
                                                 (assoc ctx
                                                   :response {:headers {"Hello" "World"}
                                                              :body    "ok"
                                                              :status  200}))})]}

                                 http/create-servlet
                                 ocp/create-client)
        response-for-response (response-for service-fn :get "/")
        response-for-request (dissoc @*request :body)]
    (fact
      "Reference values: response-for response"
      response-for-response
      => {:body    "ok"
          :headers {"Content-Type" "text/plain"
                    "Hello"        "World"}
          :status  200})
    (fact
      "Reference values: response-for request"
      response-for-request
      => {:headers        {"content-length" "0"
                           "content-type"   ""}
          :protocol       "HTTP/1.1"
          :query-string   nil
          :request-method :get
          :scheme         nil
          :server-name    nil
          :server-port    -1
          :uri            "/"})
    (fact
      "client/send should behave exactly as response-for response"
      (client/send client {:request-method :get
                           :uri            "/"})
      => response-for-response)
    (fact
      "client/send should behave exactly as response-for request"
      (dissoc @*request :body)
      => response-for-request)))


(deftest simple-post
  (let [*request (atom nil)
        {::http/keys [service-fn]
         ::ocp/keys  [client]} (-> {::http/interceptors
                                    [(interceptor/interceptor
                                       {:enter (fn [{:keys [request]
                                                     :as   ctx}]
                                                 (reset! *request (select-keys request ring-keys))
                                                 (assoc ctx
                                                   :response {:headers {"Hello" "World"}
                                                              :body    (slurp (:body request))
                                                              :status  200}))})]}
                                 http/create-servlet
                                 ocp/create-client)
        response-for-response (response-for service-fn :post "/"
                                :headers {"Content-Type" "application/edn"}
                                :body (pr-str {:hello "world"}))
        response-for-request (dissoc @*request :body)]
    (fact
      "Reference values: response-for response"
      response-for-response
      => {:body    "{:hello \"world\"}"
          :headers {"Content-Type"
                    "text/plain" "Hello" "World"}
          :status  200})
    (fact
      "Reference values: response-for request"
      response-for-request
      => {:headers        {"content-length" "0"
                           "content-type"   "application/edn"}
          :protocol       "HTTP/1.1"
          :query-string   nil
          :request-method :post
          :scheme         nil
          :server-name    nil
          :server-port    -1
          :uri            "/"})
    (fact
      "client/send should behave exactly as response-for response"
      (client/send client {:request-method :post
                           :uri            "/"
                           :headers        {"Content-Type" "application/edn"}
                           :body           (io/input-stream (.getBytes (pr-str {:hello "world"})
                                                              StandardCharsets/UTF_8))})
      => response-for-response)
    (fact
      "client/send should behave exactly as response-for request"
      (dissoc @*request :body)
      => response-for-request)))

(deftest simple-get-async
  (let [*request (atom nil)
        {::http/keys [service-fn]
         ::ocp/keys  [client]} (-> {::http/interceptors
                                    [(interceptor/interceptor
                                       {:enter (fn [{:keys [request]
                                                     :as   ctx}]
                                                 (reset! *request (select-keys request pedestal-keys))
                                                 (async/go
                                                   (assoc ctx
                                                     :response {:headers {"Hello" "World"}
                                                                :body    "ok"
                                                                :status  200})))})]}

                                 http/create-servlet
                                 ocp/create-client)
        response-for-response (response-for service-fn :get "/")
        response-for-request (dissoc @*request :body)]
    (fact
      "Reference values: response-for response"
      response-for-response
      => {:body    "ok"
          :headers {"Content-Type" "text/plain"
                    "Hello"        "World"}
          :status  200})
    (fact
      "Reference values: response-for request"
      response-for-request
      => {:async-supported?   true
          :character-encoding "UTF-8"
          :content-length     0
          :content-type       ""
          :context-path       ""
          :headers            {"content-length" "0"
                               "content-type" ""}
          :path-info          "/"
          :protocol           "HTTP/1.1"
          :query-string       nil
          :remote-addr        "127.0.0.1"
          :request-method     :get
          :scheme             nil
          :server-name        nil
          :server-port        -1
          :uri                "/"})
    (fact
      "client/send should behave exactly as response-for response"
      (client/send client {:request-method :get
                           :uri            "/"})
      => response-for-response)
    (fact
      "client/send should behave exactly as response-for request"
      (dissoc @*request :body)
      => response-for-request)))
