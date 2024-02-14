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

(def with-defaults
  (-> {}
    (with-meta `{http-client/send ~(fn [{::keys [http-client defaults]} ring-request]
                                     (http-client/send http-client (merge defaults ring-request)))})))

(deftest hello
  (let [server (jetty/run-jetty (petstore/create)
                 {:port  0
                  :join? false})
        uri (.getURI server)
        http-client (assoc with-defaults ::http-client (HttpClient/newHttpClient)
                                         ::defaults {:request-method :get
                                                     :server-name    (URI/getHost uri)
                                                     :scheme         (URI/getScheme uri)
                                                     :port           (URI/getPort uri)})
        http-client (assoc with-json-io ::http-client http-client)]
    (try
      (is (= {:headers {"content-type" "application/json"}
              :body    []
              :status  200}
            (-> http-client
              (http-client/send {:uri "/api/pets"})
              (update :headers dissoc "date" "server" "transfer-encoding")
              (doto clojure.pprint/pprint))))
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
              (update :headers dissoc "date" "server" "transfer-encoding")
              #_(doto clojure.pprint/pprint))))
      (is (= {:body    [{:id   0
                         :name "jake"
                         :type "dog"}]
              :headers {"content-type" "application/json"}
              :status  200}
            (-> http-client
              (http-client/send {:uri "/api/pets"})
              (update :headers dissoc "date" "server" "transfer-encoding")
              #_(doto clojure.pprint/pprint))))

      (finally
        (.stop server)))))


