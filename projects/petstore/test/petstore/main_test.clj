(ns petstore.main-test
  (:refer-clojure :exclude [send])
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.test :refer [deftest is]]
            [petstore.main :as petstore]
            [ring.adapter.jetty :as jetty])
  (:import (java.net URI)
           (java.net.http HttpClient HttpHeaders HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.util Optional)
           (java.util.function BiPredicate)))

(defprotocol ISendable (send [this ring-request]))

(extend-protocol ISendable
  HttpClient
  (send [this {:keys [request-method scheme server-name port uri query-string remote-addr headers body]
               :or   {headers        {}
                      scheme         :http
                      uri            "/"
                      port           -1
                      request-method :get}}]
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


(deftest hello
  (let [server (jetty/run-jetty (petstore/create)
                 {:port  0
                  :join? false})
        http-client (HttpClient/newHttpClient)
        uri (.getURI server)
        base-request {:server-name "localhost"
                      :scheme      (URI/getScheme uri)
                      :port        (URI/getPort uri)}]
    (try
      (is (= {:headers {"content-type" "application/json"}
              :body    []
              :status  200}
            (-> (send http-client
                  (assoc base-request :uri "/api/pets"))

              (update :body slurp)
              (update :headers dissoc "date" "server" "transfer-encoding")
              (update :body json/read-str :key-fn keyword)
              #_(doto clojure.pprint/pprint))))
      (is (= {:headers {"content-type" "application/json"},
              :body    {:id   0
                        :name "jake"
                        :type "dog"},
              :status  200}
            (-> (send http-client
                  (assoc base-request :uri "/api/pets"
                                      :body (io/input-stream (.getBytes (json/write-str {:name "jake"
                                                                                         :type :dog})))
                                      :request-method :post))
              (update :body slurp)
              (update :headers dissoc "date" "server" "transfer-encoding")
              (update :body json/read-str :key-fn keyword)
              #_(doto clojure.pprint/pprint))))
      (is (= {:body    [{:id   0
                         :name "jake"
                         :type "dog"}]
              :headers {"content-type" "application/json"}
              :status  200}
            (-> (send http-client
                  (assoc base-request :uri "/api/pets"))

              (update :body slurp)
              (update :headers dissoc "date" "server" "transfer-encoding")
              (update :body json/read-str :key-fn keyword)
              #_(doto clojure.pprint/pprint))))

      (finally
        (.stop server)))))


