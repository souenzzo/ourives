(ns petstore.main-test
  (:require [br.dev.zz.ourives.http-client :as http-client]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.test :refer [deftest is]]
            [petstore.main :as petstore]
            [ring.adapter.jetty :as jetty])
  (:import (java.net URI)
           (java.net.http HttpClient)))

(set! *warn-on-reflection* true)

(def with-json-io
  (-> {}
    (with-meta `{http-client/send ~(fn [{::keys [http-client]} ring-request]
                                     (let [ring-response (http-client/send http-client (merge ring-request
                                                                                         (when-let [[_ body] (find ring-request :body)]
                                                                                           {:headers (merge {"Content-Type" ["application/json"]
                                                                                                             "Accept"       ["application/json"]}
                                                                                                       (:headers ring-request))
                                                                                            :body    (io/input-stream (.getBytes (json/write-str body)))})))]
                                       (if (and (contains? ring-response :body)
                                             (some-> ring-response :headers
                                               (get "content-type")
                                               (string/starts-with? "application/json")))
                                         (assoc ring-response
                                           :body (-> ring-response :body io/reader (json/read :key-fn keyword)))
                                         ring-response)))})))

(def with-on-uri
  (-> {}
    (with-meta `{http-client/send ~(fn [{::keys [http-client uri]} ring-request]
                                     (http-client/send http-client (merge {:request-method :get
                                                                           :server-name    (URI/getHost uri)
                                                                           :scheme         (URI/getScheme uri)
                                                                           :port           (URI/getPort uri)} ring-request)))})))

(def without-headers
  (-> {}
    (with-meta `{http-client/send ~(fn [{::keys [http-client headers]} ring-request]
                                     (-> (http-client/send http-client ring-request)
                                       (update :headers #(apply dissoc % headers))))})))

(deftest hello
  (let [server (jetty/run-jetty (petstore/create)
                 {:port  0
                  :join? false})
        http-client (assoc with-on-uri ::http-client (HttpClient/newHttpClient)
                                       ::uri (.getURI server))
        http-client (assoc with-json-io ::http-client http-client)
        http-client (assoc without-headers ::http-client http-client
                                           ::headers ["date" "server" "transfer-encoding"])]
    (try
      (is (= {:headers {"content-type" "application/json"}
              :body    []
              :status  200}
            (-> http-client
              (http-client/send {:uri "/api/pets"})
              #_(doto clojure.pprint/pprint))))
      (is (= {:headers {"content-type" "application/json"},
              :body    {:id   0
                        :name "jake"
                        :type "dog"},
              :status  200}
            (-> http-client
              (http-client/send {:uri            "/api/pets"
                                 :body           {:name "jake"
                                                  :type :dog}
                                 :request-method :post})
              #_(doto clojure.pprint/pprint))))
      (is (= {:body    [{:id   0
                         :name "jake"
                         :type "dog"}]
              :headers {"content-type" "application/json"}
              :status  200}
            (-> http-client
              (http-client/send {:uri "/api/pets"})
              #_(doto clojure.pprint/pprint))))

      (finally
        (.stop server)))))


