(ns br.dev.zz.ourives.dynamic-router
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [juxt.reap.rfc6570 :as rfc6570]
            [clojure.string :as string]
            [ring.core.protocols :as rcp])
  (:import (java.io ByteArrayOutputStream)))

(set! *warn-on-reflection* true)

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
                             [method {:strs [parameters operationId] :as operation}] path-item]
                         {:path             path
                          :operation-method (-> method string/lower-case keyword)
                          :operation-id     (or operationId
                                              (str "#"
                                                (string/join "/"
                                                  ["paths" path method])))
                          :uri-matcher      (rfc6570/compile-uri-template path)
                          :path-types       (into {}
                                              (keep (fn [{:strs [name in schema]}]
                                                      (when (= in "path")
                                                        [(keyword name)
                                                         ;; todo: check if schema is list
                                                         #_schema
                                                         :string])))
                                              parameters)
                          :operation        operation})
        operations-by-method (group-by :operation-method all-operations)]
    (fn [{:keys [uri request-method] :as request}]
      (let [operations (get operations-by-method request-method)
            {:keys [operation-id path-params]} (some (fn [{:keys [uri-matcher path-types]
                                                           :as   operation}]
                                                       (when-let [path-params (rfc6570/match-uri uri-matcher
                                                                                path-types uri)]
                                                         (assoc operation
                                                           :path-params path-params)))
                                                 operations)]
        (assoc request
          ::path-params path-params
          ::operation-id (or operation-id ::not-found))))))

(defn wrap-router
  [{::keys [ring-handler]}]
  (let [*router (promise)]
    (fn [request]
      (when-not (realized? *router)
        (deliver *router (make-router ring-handler request)))
      (ring-handler (@*router request)))))
