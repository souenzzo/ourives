(ns br.com.souenzzo.oring
  (:require [clojure.test :refer [deftest]]
            [midje.sweet :refer [fact =>]]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as string])
  (:import (java.io ByteArrayOutputStream)
           (java.net URLEncoder)
           (java.nio.charset StandardCharsets)))

(defn oapi-deref
  [spec {:keys [$ref]
         :as   v}]
  (when $ref
    (throw (ex-info "not implemented" v)))
  v)


(defn ring-request-for
  [{::keys [op-by-id]} {::keys [operation-id request-body]
                        :as    params}]
  (let [{:strs [parameters]
         :as   operation} (get op-by-id operation-id)
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
                           (str (URLEncoder/encode (str k)
                                  StandardCharsets/UTF_8)
                             "="
                             (URLEncoder/encode (str v)
                               StandardCharsets/UTF_8))))})
      (when requestBody
        (let [baos (ByteArrayOutputStream.)]
          (with-open [w (io/writer baos)]
            (json/write request-body w))
          {:body (io/input-stream (.toByteArray baos))})))))

(defn create
  [{:strs [paths]
    :as   api}]
  (assoc api
    ::op-by-id (into {} (for [[path path-item-object] paths
                              [method raw-op] path-item-object
                              :let [{:strs [operationId]
                                     :as   op} (assoc (oapi-deref api raw-op)
                                                 ::path path
                                                 ::method method)]]
                          [operationId op]))))



(deftest petstore+petstore-expanded
  (let [petstore (create (json/read (io/reader "../OpenAPI-Specification/examples/v3.0/petstore.json")))
        petstore-expanded (create (json/read (io/reader "../OpenAPI-Specification/examples/v3.0/petstore-expanded.json")))]
    (fact
      "petstore - showPetById"
      (ring-request-for petstore {::operation-id "showPetById"
                                  "petId"        "hello"})
      => {:request-method :get
          :uri            "/pets/hello"})
    (fact
      "petstore - listPets"
      (ring-request-for petstore {::operation-id "listPets"})
      => {:request-method :get
          :uri            "/pets"})
    (fact
      "petstore - createPets"
      (ring-request-for petstore {::operation-id "createPets"})
      => {:request-method :post
          :uri            "/pets"})
    (fact
      "petstore-expanded - findPets"
      (ring-request-for petstore-expanded {::operation-id "findPets"
                                           "tags"         ["a" "b"]
                                           "limit"        42})
      => {:query-string   "tags=a&tags=b&limit=42"
          :request-method :get
          :uri            "/pets"})
    (fact
      "petstore-expanded - addPet"
      (-> (ring-request-for petstore-expanded {::operation-id "addPet"
                                               ::request-body {:name "hello"
                                                               :tag  "dog"}})
        (update :body (comp json/read io/reader)))
      => {:body           {"name" "hello"
                           "tag"  "dog"}
          :request-method :post
          :uri            "/pets"})
    (fact
      "petstore-expanded - find pet by id"
      (ring-request-for petstore-expanded {::operation-id "find pet by id"
                                           "id"           123})
      => {:request-method :get
          :uri            "/pets/123"})
    (fact
      "petstore-expanded - deletePet"
      (ring-request-for petstore-expanded {::operation-id "deletePet"
                                           "id"           123})
      => {:request-method :delete
          :uri            "/pets/123"})))
