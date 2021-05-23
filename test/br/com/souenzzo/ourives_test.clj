(ns br.com.souenzzo.ourives-test
  (:require [clojure.test :refer [deftest]]
            [br.com.souenzzo.ourives :as ourives]
            [br.com.souenzzo.ourives.easy :as ourives.easy]
            [midje.sweet :refer [fact =>]]
            [br.com.souenzzo.ourives.test :refer [response-for http-client]])
  (:import (java.net URI)
           (java.net.http HttpClient
                          HttpRequest
                          HttpResponse$BodyHandlers HttpRequest$BodyPublishers)))

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
    (with-open [server (ourives.easy/start {::ourives/handler (fn [{:ring.request/keys [body]
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
                                         "user-agent"     "Java-http-/16.0.1"}
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
