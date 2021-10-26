(ns br.com.souenzzo.openapi
  (:refer-clojure :exclude [methods ])
  (:require [clojure.spec.alpha :as s]
            [br.com.souenzzo.json-pointer :as json-pointer]
            [clojure.string :as string])
  (:import (java.net URI)))

(defn distinct-by
  ([keyfn coll]
   (sequence (distinct-by keyfn) coll))
  ([f]
   (fn [rf]
     (let [*seen (volatile! #{})]
       (fn
         ([] (rf))
         ([result] (rf result))
         ([result input]
          (let [k (f input)]
            (if (contains? @*seen k)
              result
              (do (vswap! *seen conj k)
                  (rf result input))))))))))



;; https://swagger.io/specification/#reference-object
(s/def ::reference (s/map-of #{"$ref"} string?))

(def methods #{"delete" "get" "head" "options" "patch" "post" "put" "trace"})

(s/def ::method methods)

(defn dereference
  [document object-or-reference]
  (if (s/valid? ::reference object-or-reference)
    (let [path (-> object-or-reference
                 (get "$ref")
                 URI/create
                 .getFragment
                 (string/split #"/")
                 rest
                 (->> (map json-pointer/unescape)))]
      (with-meta (dereference document (get-in document path))
        {::reference object-or-reference}))
    object-or-reference))

(defn $ref
  [& vs]
  {"$ref" (string/join "/"
            (cons "#" (map json-pointer/escape vs)))})

(defn operations
  [document]
  (let [methods ["get" "put" "post" "delete" "options" "head" "patch" "trace"]]
    (for [[path path-item] (get document "paths")
          :let [path-item (dereference document path-item)]
          [method operation] (select-keys path-item methods)]
      (assoc operation
        ::operation-ref (string/join "/"
                          (cons "#" (map json-pointer/escape ["paths" path method])))
        ::path-item-ref (string/join "/"
                          (cons "#" (map json-pointer/escape ["paths" path])))
        ::path-item (apply dissoc path-item methods)
        ::path path
        ::parameters (into []
                       (comp cat
                         (map (partial dereference document))
                         (distinct-by (fn [{:strs [name location]}]
                                        [name location])))
                       [(get operation "parameters")
                        (get path-item "parameters")])
        ::method method))))