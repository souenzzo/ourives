(ns br.com.souenzzo.oring
  (:require [clojure.test :refer [deftest]]
            [midje.sweet :refer [fact =>]]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as string])
  (:import (java.io ByteArrayOutputStream)))

(defn oapi-deref
  [spec {:keys [$ref]
         :as   v}]
  (when $ref
    (throw (ex-info "aaaa" v)))
  v)

(defn ring-request-for-old
  [{:strs [paths]
    :as   spec} {::keys [operation-id request-body]
                 :as    params}]
  (-> (for [[path path-item-object] paths
            [method raw-op] path-item-object
            :let [{:strs [operationId parameters]
                   :as   operation} (oapi-deref spec raw-op)]
            :when (= operationId operation-id)
            :let [rf (fn [{::keys [path]
                           :as    operation} {:strs [in required name]}]
                       (cond
                         (contains? params name)
                         (case in
                           "query" (assoc-in operation
                                     [::query name] (get params name))
                           "path" (assoc operation
                                    ::path (string/replace path
                                             #"\{(.+)\}"
                                             (fn [_]
                                               (str (get params name))))))
                         required (throw (ex-info (str "Missing parameter: " (pr-str name))
                                           {:cognitect.anomalies/category :cognitect.anomalies/incorrect}))
                         :else operation))
                  {:strs  [requestBody]
                   ::keys [path method query]} (reduce rf
                                                 (assoc operation
                                                   ::path path
                                                   ::method method)
                                                 parameters)]]
        (merge {:request-method (keyword method)
                :uri            path}
          (when query
            {:query-string (string/join "&"
                             (for [[k vs] query
                                   v (if (coll? vs)
                                       vs
                                       [vs])]
                               (str k "=" v)))})
          (when requestBody
            (let [baos (ByteArrayOutputStream.)]
              (with-open [w (io/writer baos)]
                (json/write request-body w))
              {:body (io/input-stream (.toByteArray baos))}))))
    first))

(defn ring-request-for
  [{::keys [apis]} {::keys [api operation-id request-body]
                    :as    params}]
  (let [{:strs [parameters]
         :as   operation} (get-in apis [api ::op-by-id operation-id])
        rf (fn [{::keys [path]
                 :as    operation} {:strs [in required name]}]
             (cond
               (contains? params name)
               (case in
                 "query" (assoc-in operation
                           [::query name] (get params name))
                 "path" (assoc operation
                          ::path (string/replace path
                                   #"\{(.+)\}"
                                   (fn [_]
                                     (str (get params name))))))
               required (throw (ex-info (str "Missing parameter: " (pr-str name))
                                 {:cognitect.anomalies/category :cognitect.anomalies/incorrect}))
               :else operation))
        {:strs  [requestBody]
         ::keys [path method query]} (reduce rf operation parameters)]
    (merge {:request-method (keyword method)
            :uri            path}
      (when query
        {:query-string (string/join "&"
                         (for [[k vs] query
                               v (if (coll? vs)
                                   vs
                                   [vs])]
                           (str k "=" v)))})
      (when requestBody
        (let [baos (ByteArrayOutputStream.)]
          (with-open [w (io/writer baos)]
            (json/write request-body w))
          {:body (io/input-stream (.toByteArray baos))})))))

(defn create
  [{::keys [apis]}]
  (let [apis (into {}
               (for [[k v] apis
                     :let [{:strs [paths]
                            :as   spec} (if (map? v)
                                          v
                                          (with-open [reader (io/reader v)]
                                            (json/read reader)))]]
                 [k {::op-by-id (into {} (for [[path path-item-object] paths
                                               [method raw-op] path-item-object
                                               :let [{:strs [operationId]
                                                      :as   op} (assoc (oapi-deref spec raw-op)
                                                                  ::path path
                                                                  ::method method)]]
                                           [operationId op]))
                     ::spec     spec}]))]

    {::apis apis}))


(deftest petstore+petstore-expanded
  (let [spec (create {::apis {:petstore          "../OpenAPI-Specification/examples/v3.0/petstore.json"
                              :petstore-expanded "../OpenAPI-Specification/examples/v3.0/petstore-expanded.json"}})]
    (fact
      "petstore - showPetById"
      (ring-request-for spec {::operation-id "showPetById"
                              ::api          :petstore
                              "petId"        "hello"})
      => {:request-method :get
          :uri            "/pets/hello"})
    (fact
      "petstore - listPets"
      (ring-request-for spec {::operation-id "listPets"
                              ::api          :petstore})
      => {:request-method :get
          :uri            "/pets"})
    (fact
      "petstore - createPets"
      (ring-request-for spec {::operation-id "createPets"
                              ::api          :petstore})
      => {:request-method :post
          :uri            "/pets"})
    (fact
      "petstore-expanded - findPets"
      (ring-request-for spec {::operation-id "findPets"
                              ::api          :petstore-expanded
                              "tags"         ["a" "b"]
                              "limit"        42})
      => {:query-string   "tags=a&tags=b&limit=42"
          :request-method :get
          :uri            "/pets"})
    (fact
      "petstore-expanded - addPet"
      (-> (ring-request-for spec {::operation-id "addPet"
                                  ::api          :petstore-expanded
                                  ::request-body {:name "hello"
                                                  :tag  "dog"}})
        (update :body (comp json/read io/reader)))
      => {:body           {"name" "hello"
                           "tag"  "dog"}
          :request-method :post
          :uri            "/pets"})
    (fact
      "petstore-expanded - find pet by id"
      (ring-request-for spec {::operation-id "find pet by id"
                              ::api          :petstore-expanded
                              "id"           123})
      => {:request-method :get
          :uri            "/pets/123"})
    (fact
      "petstore-expanded - deletePet"
      (ring-request-for spec {::operation-id "deletePet"
                              ::api          :petstore-expanded
                              "id"           123})
      => {:request-method :delete
          :uri            "/pets/123"})))
