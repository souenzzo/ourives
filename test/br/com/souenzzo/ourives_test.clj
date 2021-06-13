(ns br.com.souenzzo.ourives-test
  (:require [clojure.test :refer [deftest]]
            [br.com.souenzzo.ourives :as ourives]
            [br.com.souenzzo.ourives.client :as client]
            [br.com.souenzzo.ourives.client.java-http]
            [br.com.souenzzo.ourives.client.pedestal :as ocp]
            [br.com.souenzzo.ourives.easy :as ourives.easy]
            [io.pedestal.http :as http]
            [midje.sweet :refer [fact =>]]
            [br.com.souenzzo.ourives.test :refer [response-for http-client]]
            [br.com.souenzzo.ourives.pedestal :as ourives.pedestal]
            [ring.util.mime-type :as mime]
            [clojure.java.io :as io])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers HttpRequest$BodyPublishers)
           (java.lang AutoCloseable)
           (java.util Map)))

(set! *warn-on-reflection* true)

(defn ^AutoCloseable run-server
  [service-map]
  (let [server (-> service-map
                 http/create-server
                 http/start)]
    (reify
      AutoCloseable
      (close [this] (http/stop server))
      Map
      (get [this k]
        (get server k)))))

(defn do-echo!
  [service-map req]
  (let [*req (promise)]
    (with-open [server (-> service-map
                         (assoc ::http/routes #{["/foo/:id" :post (fn [{:keys [body]
                                                                        :as   req}]
                                                                    (deliver *req req)
                                                                    {:headers {"Other"        "one"
                                                                               "Content-Type" (mime/default-mime-types "edn")}
                                                                     :body    (pr-str {:hello (slurp body)})
                                                                     :status  200})
                                                 :route-name :hello]}
                                ::http/join? false
                                ::http/port 8080)
                         http/default-interceptors
                         run-server)]
      (let [response (.send (HttpClient/newHttpClient) req
                       (HttpResponse$BodyHandlers/ofString))]
        {:req (deref *req 100 nil)
         :res {:headers (into {}
                          (map (fn [[k v]]
                                 [k (if (== 1 (count v))
                                      (first v)
                                      (vec v))]))
                          (.map (.headers response)))
               :body    (.body response)
               :status  (.statusCode response)}}))))


(deftest hello-jetty
  (let [*req (promise)]
    (with-open [server (-> {::http/type   :jetty
                            ::http/routes #{["/foo/:id" :post (fn [{:keys [body]
                                                                    :as   req}]
                                                                (deliver *req req)
                                                                {:headers {"Other"        "one"
                                                                           "Content-Type" (mime/default-mime-types "edn")}
                                                                 :body    (pr-str {:hello (slurp body)})
                                                                 :status  200})
                                             :route-name :hello]}
                            ::http/join?  false
                            ::http/port   8080}
                         http/default-interceptors
                         run-server)]
      (let [res (client/send (HttpClient/newHttpClient)
                  {:scheme         :http
                   :server-name    "localhost"
                   :query-string   "foo=42"
                   :headers        {"Content-Type" "text/plain; chartset=UTF-8"}
                   :uri            "/foo/123"
                   :request-method :post
                   :body           (io/input-stream (.getBytes "bodies"))
                   :server-port    8080})
            req-ks [:async-supported? :character-encoding :content-length :content-type :context-path :headers
                    :params :path-info :path-params :protocol :query-params :query-string :remote-addr
                    :request-method :scheme :server-name :server-port :uri
                    #_:servlet-response #_:servlet #_:body #_:servlet-request #_:url-for]]
        (fact
          "jetty response"
          (update res :headers dissoc "date")
          => {:body    "{:hello \"bodies\"}"
              :headers {"content-security-policy"           "object-src 'none'; script-src 'unsafe-inline' 'unsafe-eval' 'strict-dynamic' https: http:;"
                        "content-type"                      "application/edn"
                        ;; "date"                              "Fri, 11 Jun 2021 03:32:49 GMT"
                        "other"                             "one"
                        "strict-transport-security"         "max-age=31536000; includeSubdomains"
                        "transfer-encoding"                 "chunked"
                        "x-content-type-options"            "nosniff"
                        "x-download-options"                "noopen"
                        "x-frame-options"                   "DENY"
                        "x-permitted-cross-domain-policies" "none"
                        "x-xss-protection"                  "1; mode=block"}
              :status  200})
        (fact
          "jetty request"
          (select-keys @*req req-ks)
          => {:async-supported? true
              :content-type     "text/plain; chartset=UTF-8"
              :context-path     ""
              :headers          {"content-type"      "text/plain; chartset=UTF-8"
                                 "user-agent"        "Java-http-client/16.0.1"
                                 "host"              "localhost:8080"
                                 "transfer-encoding" "chunked"}
              :params           {:foo "42"}
              :path-info        "/foo/123"
              :path-params      {:id "123"}
              :protocol         "HTTP/1.1"
              :query-params     {:foo "42"}
              :query-string     "foo=42"
              :remote-addr      "127.0.0.1"
              :request-method   :post
              :scheme           :http
              :server-name      "localhost"
              :server-port      8080
              :uri              "/foo/123"})))))



(deftest hello-http-client-pedestal
  (let [*req (promise)
        client (-> {::http/type   :jetty
                    ::http/routes #{["/foo/:id" :post (fn [{:keys [body]
                                                            :as   req}]
                                                        (deliver *req req)
                                                        {:headers {"Other"        "one"
                                                                   "Content-Type" (mime/default-mime-types "edn")}
                                                         :body    (pr-str {:hello (slurp body)})
                                                         :status  200})
                                     :route-name :hello]}
                    ::http/join?  false
                    ::http/port   8080}
                 http/default-interceptors
                 ocp/client
                 ::ocp/client)
        res (client/send client
              {:scheme         :http
               :server-name    "localhost"
               :query-string   "foo=42"
               :headers        {"Content-Type" "text/plain; chartset=UTF-8"}
               :uri            "/foo/123"
               :request-method :post
               :body           (io/input-stream (.getBytes "bodies"))
               :server-port    8080})
        req-ks [:async-supported? :character-encoding :content-length :content-type :context-path :headers
                :params :path-info :path-params :protocol :query-params :query-string :remote-addr
                :request-method :scheme :server-name :server-port :uri
                #_:servlet-response #_:servlet #_:body #_:servlet-request #_:url-for]]
    (fact
      "response"
      (update res :headers dissoc "date")
      => {:body    "{:hello \"bodies\"}"
          :headers {"Content-Security-Policy"           "object-src 'none'; script-src 'unsafe-inline' 'unsafe-eval' 'strict-dynamic' https: http:;"
                    "Content-Type"                      "application/edn"
                    "Other"                             "one"
                    "Strict-Transport-Security"         "max-age=31536000; includeSubdomains"
                    "X-Content-Type-Options"            "nosniff"
                    "X-Download-Options"                "noopen"
                    "X-Frame-Options"                   "DENY"
                    "X-Permitted-Cross-Domain-Policies" "none"
                    "X-XSS-Protection"                  "1; mode=block"}
          :status  200})
    (fact
      "request"
      (select-keys @*req req-ks)
      => {:async-supported? false
          :context-path     ""
          :headers          {"content-type" "text/plain; chartset=UTF-8"}
          :params           {:foo "42"}
          :path-info        "/foo/123"
          :path-params      {:id "123"}
          :protocol         nil
          :query-params     {:foo "42"}
          :query-string     "foo=42"
          :remote-addr      nil
          :request-method   :post
          :scheme           :http
          :server-name      "localhost"
          :server-port      8080
          :uri              "/foo/123"})))


(deftest hello-pedestal
  (let [uri (URI/create (str "http://localhost:8080/foo/123?foo=42"))
        stdreq (-> (HttpRequest/newBuilder uri)
                 (.header "Content-Type" "text/plain; chartset=UTF-8")
                 (.POST (HttpRequest$BodyPublishers/ofString
                          "bodies"))
                 (.build))
        {:keys [req res]} (do-echo! {::http/type           ourives.pedestal/type
                                     ::http/chain-provider ourives.pedestal/chain-provider}
                            stdreq)
        {jetty-res :res
         jetty-req :req} (do-echo! {::http/type :jetty}
                           stdreq)
        req-ks [:async-supported? :character-encoding :content-length :content-type :context-path :headers
                :params :path-info :path-params :protocol :query-params :query-string :remote-addr
                :request-method :scheme :server-name :server-port :uri
                #_:servlet-response #_:servlet #_:body #_:servlet-request #_:url-for]]
    (fact
      "jetty response"
      (update jetty-res :headers dissoc "date")
      => {:body    "{:hello \"bodies\"}"
          :headers {"content-security-policy"           "object-src 'none'; script-src 'unsafe-inline' 'unsafe-eval' 'strict-dynamic' https: http:;"
                    "content-type"                      "application/edn"
                    ;; "date"                              "Fri, 11 Jun 2021 03:32:49 GMT"
                    "other"                             "one"
                    "strict-transport-security"         "max-age=31536000; includeSubdomains"
                    "transfer-encoding"                 "chunked"
                    "x-content-type-options"            "nosniff"
                    "x-download-options"                "noopen"
                    "x-frame-options"                   "DENY"
                    "x-permitted-cross-domain-policies" "none"
                    "x-xss-protection"                  "1; mode=block"}
          :status  200})
    (fact
      "ourives response"
      (update res :headers dissoc "date")
      => {:body    "{:hello \"bodies\"}"
          :headers {"content-security-policy"           "object-src 'none'; script-src 'unsafe-inline' 'unsafe-eval' 'strict-dynamic' https: http:;"
                    "content-type"                      "application/edn"
                    ;; "date"                              "Fri, 11 Jun 2021 03:32:49 GMT"
                    "other"                             "one"
                    "strict-transport-security"         "max-age=31536000; includeSubdomains"
                    "transfer-encoding"                 "chunked"
                    "x-content-type-options"            "nosniff"
                    "x-download-options"                "noopen"
                    "x-frame-options"                   "DENY"
                    "x-permitted-cross-domain-policies" "none"
                    "x-xss-protection"                  "1; mode=block"}
          :status  200})
    (fact
      "jetty request"
      (select-keys jetty-req req-ks)
      => {:async-supported? true
          :content-type     "text/plain; chartset=UTF-8"
          :content-length   6
          :context-path     ""
          :headers          {"connection"     "Upgrade, HTTP2-Settings"
                             "content-type"   "text/plain; chartset=UTF-8"
                             "user-agent"     "Java-http-client/16.0.1"
                             "host"           "localhost:8080"
                             "http2-settings" "AAEAAEAAAAIAAAABAAMAAABkAAQBAAAAAAUAAEAA"
                             "content-length" "6"
                             "upgrade"        "h2c"}
          :params           {:foo "42"}
          :path-info        "/foo/123"
          :path-params      {:id "123"}
          :protocol         "HTTP/1.1"
          :query-params     {:foo "42"}
          :query-string     "foo=42"
          :remote-addr      "127.0.0.1"
          :request-method   :post
          :scheme           :http
          :server-name      "localhost"
          :server-port      8080
          :uri              "/foo/123"})
    (fact
      "ourives request"
      (select-keys req req-ks)
      => {:async-supported? false
          :content-length   6
          :content-type     "text/plain; chartset=UTF-8"
          :context-path     ""
          :headers          {"connection"     "Upgrade, HTTP2-Settings"
                             "content-type"   "text/plain; chartset=UTF-8"
                             "user-agent"     "Java-http-client/16.0.1"
                             "host"           "localhost:8080"
                             "http2-settings" "AAEAAEAAAAIAAAABAAMAAABkAAQBAAAAAAUAAEAA"
                             "content-length" "6"
                             "upgrade"        "h2c"}
          :params           {:foo "42"}
          :path-info        "/foo/123"
          :path-params      {:id "123"}
          :protocol         "HTTP/1.1"
          :query-params     {:foo "42"}
          :query-string     "foo=42"
          :remote-addr      "127.0.0.1"
          :request-method   :post
          :scheme           :http
          :server-name      "localhost"
          :server-port      8080
          :uri              "/foo/123"})))

(def ring-request-keys
  [:ring.request/body
   :ring.request/headers
   :ring.request/method
   :ring.request/path
   :ring.request/protocol
   :ring.request/query
   :ring.request/remote-addr
   :ring.request/scheme
   :ring.request/server-name
   :ring.request/server-port
   :ring.request/ssl-client-cert])

(deftest integration
  (let [*request (promise)
        client (HttpClient/newHttpClient)]
    (with-open [server ^AutoCloseable (ourives.easy/start {::ourives/handler (fn [{:ring.request/keys [body]
                                                                                   :as                req}]
                                                                               (deliver *request (assoc req
                                                                                                   :ring.request/body (slurp body)))
                                                                               {:ring.response/body    "world!"
                                                                                :ring.response/headers {"hello" "123"}
                                                                                :ring.response/status  200})})]
      (let [server-port (::ourives.easy/port server)
            uri (URI/create (str "http://localhost:" server-port "/path?query=123"))
            response (.send client (.build (.POST (HttpRequest/newBuilder uri)
                                             (HttpRequest$BodyPublishers/ofString
                                               "bodies")))
                       (HttpResponse$BodyHandlers/ofString))]
        (fact
          (.body response)
          => "world!")
        (fact
          (.statusCode response)
          => 200)
        (fact
          (into {}
            (map (fn [[k vs]]
                   [k (vec vs)]))
            (.map (.headers response)))
          => {"hello" ["123"]})
        (fact
          (select-keys @*request ring-request-keys)
          => {:ring.request/body        "bodies"
              :ring.request/headers     {"connection"     "Upgrade, HTTP2-Settings"
                                         "content-length" "6"
                                         "host"           (str "localhost:" server-port)
                                         "http2-settings" "AAEAAEAAAAIAAAABAAMAAABkAAQBAAAAAAUAAEAA"
                                         "upgrade"        "h2c"
                                         "user-agent"     "Java-http-client/16.0.1"}
              :ring.request/method      :post
              :ring.request/path        "/path"
              :ring.request/query       "query=123"
              :ring.request/protocol    "HTTP/1.1"
              :ring.request/remote-addr "127.0.0.1"
              :ring.request/scheme      :http
              :ring.request/server-name "localhost"
              :ring.request/server-port server-port})))))
#_(.format java.time.format.DateTimeFormatter/RFC_1123_DATE_TIME (ZonedDateTime/now (ZoneId/of "UTC")))
(deftest response-for-test
  (let [*request (promise)
        handler (fn [{:ring.request/keys [body]
                      :as                req}]
                  (deliver *request (assoc req
                                      :ring.request/body (slurp body)))
                  {:ring.response/body    "world!"
                   :ring.response/headers {"hello" "123"}
                   :ring.response/status  200})]
    (fact
      (response-for handler
        {:ring.request/method :post
         :ring.request/body   "bodies"
         :ring.request/path   "/world"})
      => {:ring.response/body    "world!"
          :ring.response/headers {"hello" "123"}
          :ring.response/status  200})
    (fact
      (select-keys @*request ring-request-keys)
      => {:ring.request/headers     {"connection"     "Upgrade, HTTP2-Settings"
                                     "content-length" "6"
                                     "host"           "localhost"
                                     "http2-settings" "AAEAAEAAAAIAAAABAAMAAABkAAQBAAAAAAUAAEAA"
                                     "upgrade"        "h2c"
                                     "user-agent"     "Java-http-client/16.0.1"}
          :ring.request/method      :post
          :ring.request/body        "bodies"
          :ring.request/path        "/world"
          :ring.request/protocol    "HTTP/1.1"
          :ring.request/remote-addr "0.0.0.0"
          :ring.request/scheme      :http
          :ring.request/server-name "localhost"
          :ring.request/server-port -1})))


(defn app-handler
  [{:ring.request/keys [path]}]
  {:ring.response/body    (str "Hello from: " path)
   :ring.response/headers {"X-Custom" "Value"}
   :ring.response/status  200})


(deftest canonical-test
  (fact
    (response-for app-handler
      #:ring.request{:method :get :path "/world"})
    => {:ring.response/body    "Hello from: /world"
        :ring.response/headers {"X-Custom" "Value"}
        :ring.response/status  200}))
