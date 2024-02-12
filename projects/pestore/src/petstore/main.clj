(ns petstore.main
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [ring.core.protocols :as rcp])
  (:import (java.io ByteArrayOutputStream)))



(set! *warn-on-reflection* true)

(defonce *petstore-json-str
  (delay (slurp "https://raw.githubusercontent.com/OAI/OpenAPI-Specification/3.1.0/examples/v3.0/petstore-expanded.json")))


(defn petstore-handler
  [{::keys [operation-id]}]
  (prn operation-id)
  (case operation-id
    :findPets {:headers {"Content-Type" "application/json"}
               :body    (json/write-str [])
               :status  200}
    {:headers {"Content-Type" "application/json"}
     :body    @*petstore-json-str
     :status  200}))

(defn make-router
  [{:keys [body] :as ring-response}]
  (let [baos (ByteArrayOutputStream.)
        _ (rcp/write-body-to-stream body ring-response baos)
        {:strs [paths]} (-> baos .toByteArray io/reader json/read)]
    (fn [{:keys [uri request-method] :as request}]
      (assoc request
        ::operation-id (some-> paths
                         (get (string/replace uri #"^/api/" "/"))
                         (get (name request-method))
                         (get "operationId")
                         keyword)))))


(defn wrap-openapi
  [handler]
  (let [*router (promise)]
    (fn [request]
      (when-not (realized? *router)
        (deliver *router (make-router (handler request))))
      (handler (@*router request)))))

(defn create
  []
  (let []
    (wrap-openapi petstore-handler)))