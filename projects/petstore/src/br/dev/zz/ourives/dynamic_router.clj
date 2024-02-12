(ns br.dev.zz.ourives.dynamic-router
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [ring.core.protocols :as rcp])
  (:import (java.io ByteArrayOutputStream)))

(defn make-router
  [handler request]
  (let [{:keys [body] :as ring-response} (handler (assoc request
                                                    ::operation-id ::openapi))
        response-body (-> (ByteArrayOutputStream.)
                        (as-> % (with-open [baos %]
                                  (rcp/write-body-to-stream body ring-response baos)
                                  baos))
                        ByteArrayOutputStream/toByteArray)
        {:strs [paths]} (-> response-body io/reader json/read)
        all-operations (for [[path path-item] paths
                             [method operation] path-item]
                         {:path             path
                          :operation-method (-> method string/lower-case keyword)
                          :operation-id     (or (some-> operation (get "operationId"))
                                              (str "#"
                                                (string/join "/"
                                                  ["paths" path method])))
                          :operation        operation})
        operations-by-method (group-by :operation-method all-operations)]
    (fn [{:keys [uri request-method] :as request}]
      (let [operations (get operations-by-method request-method)
            {:keys [operation-id]} (some (fn [{:keys [path]
                                               :as   operation}]
                                           (when (= path (string/replace uri #"^/api/" "/"))
                                             operation))
                                     operations)]
        (assoc request ::operation-id
                       (or operation-id ::not-found))))))

(defn wrap-router
  [handler]
  (let [*router (promise)]
    (fn [request]
      (when-not (realized? *router)
        (deliver *router (make-router handler request)))
      (handler (@*router request)))))
