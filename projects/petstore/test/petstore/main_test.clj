(ns petstore.main-test
  (:require [br.dev.zz.ourives.http-client :as http-client]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [petstore.main :as petstore]
            [ring.adapter.jetty :as jetty])
  (:import (java.net URI)
           (java.net.http HttpClient)))

(set! *warn-on-reflection* true)

(deftest hello
  (let [server (jetty/run-jetty (petstore/create)
                 {:port  0
                  :join? false})
        http-client (HttpClient/newHttpClient)
        uri (.getURI server)
        base-request {:request-method :get
                      :server-name    (URI/getHost uri)
                      :scheme         (URI/getScheme uri)
                      :port           (URI/getPort uri)}]
    (try
      (is (= {:headers {"content-type" "application/json"}
              :body    []
              :status  200}
            (-> (http-client/send http-client
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
            (-> (http-client/send http-client
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
            (-> (http-client/send http-client
                  (assoc base-request :uri "/api/pets"))

              (update :body slurp)
              (update :headers dissoc "date" "server" "transfer-encoding")
              (update :body json/read-str :key-fn keyword)
              #_(doto clojure.pprint/pprint))))

      (finally
        (.stop server)))))


