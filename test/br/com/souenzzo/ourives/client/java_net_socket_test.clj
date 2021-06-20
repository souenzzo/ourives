(ns br.com.souenzzo.ourives.client.java-net-socket-test
  (:require [clojure.test :refer [deftest]]
            [br.com.souenzzo.ourives.client.java-net-socket :as oc.jns]
            [br.com.souenzzo.ourives.client :as client]
            [midje.sweet :refer [fact =>]]
            [io.pedestal.http :as http]
            [io.pedestal.interceptor :as interceptor]
            [cheshire.core :as json])
  (:import (java.lang AutoCloseable)
           (java.util Map)))



(defn ^AutoCloseable run-echo-server
  []
  (let [server (-> {::http/routes #{}
                    ::http/type   :jetty
                    ::http/port   8080
                    ::http/join?  false
                    ::http/not-found-interceptor
                                  (interceptor/interceptor
                                    {:name  ::echo
                                     :leave (fn [{:keys [request]
                                                  :as   ctx}]
                                              (let [body (json/generate-string
                                                           {:body (slurp (:body request))
                                                            :ring (select-keys request
                                                                    [:headers :protocol :query-string
                                                                     :request-method :scheme :server-name
                                                                     :server-port :uri])})]
                                                (assoc ctx
                                                  :response {:headers {"x-server-name"  "echo-server"
                                                                       "Content-Length" (str (count body))}
                                                             :body    body
                                                             :status  200})))})}
                 http/default-interceptors
                 http/create-server
                 http/start)]
    (reify
      AutoCloseable
      (close [this] (http/stop server))
      Map
      (get [this k]
        (get server k)))))

(deftest hello
  (with-open [srv (run-echo-server)]
    (let [{::http/keys [port]} srv
          client (oc.jns/client {})]
      (fact
        (-> (client/send client {:server-name "localhost"
                                 :server-port port
                                 :scheme      :http})
          (update :body json/parse-string true)
          (update :headers dissoc "Date"))
        => {:body    {:body ""
                      :ring {:headers        {:content-length "0"
                                              :host           "localhost:8080"}
                             :protocol       "HTTP/1.1"
                             :query-string   nil
                             :request-method "get"
                             :scheme         "http"
                             :server-name    "localhost"
                             :server-port    8080
                             :uri            "/"}}
            :headers {"Content-Length" "213"
                      "Content-Type"   "text/plain"
                      "x-server-name"  "echo-server"}
            :status  200}))))

