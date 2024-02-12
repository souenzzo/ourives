(ns petstore.main
  (:require [clojure.data.json :as json]
            [br.dev.zz.ourives.dynamic-router :as dynamic-router]))

(set! *warn-on-reflection* true)

(defonce *petstore-json-str
  (delay (slurp "https://raw.githubusercontent.com/OAI/OpenAPI-Specification/3.1.0/examples/v3.0/petstore-expanded.json")))

(defmulti petstore-operations ::dynamic-router/operation-id
  :default ::not-implemented)

(defmethod petstore-operations ::not-implemented
  [_]
  {:status 503})

;; Special/magical routes:
(defmethod petstore-operations ::dynamic-router/not-found
  [_]
  {:status 404})

(defmethod petstore-operations ::dynamic-router/openapi
  [_]
  {:headers {"Content-Type" "application/json"}
   :body    @*petstore-json-str
   :status  200})

;; Routes defined by openapi.json
(defmethod petstore-operations "findPets"
  [{::keys [*pets]}]
  {:headers {"Content-Type" "application/json"}
   :body    (json/write-str @*pets)
   :status  200})

(defn create
  []
  (let [*pets (atom [])
        handler (dynamic-router/wrap-router petstore-operations)]
    (fn [request]
      (handler (assoc request ::*pets *pets)))))
