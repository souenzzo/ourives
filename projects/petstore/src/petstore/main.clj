(ns petstore.main
  (:require [clojure.data.json :as json]
            [br.dev.zz.ourives.dynamic-router :as dynamic-router]
            [clojure.java.io :as io]))

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
(defmethod petstore-operations "addPet"
  [{::keys [*pets]
    :keys  [body]}]
  (let [{:keys [name type]} (some-> body io/reader (json/read :key-fn keyword))
        pet (last (swap! *pets
                    (fn [pets]
                      (conj pets {:id   (count pets)
                                  :name name
                                  :type type}))))]
    {:headers {"Content-Type" "application/json"}
     :body    (json/write-str pet)
     :status  200}))

(defmethod petstore-operations "deletePet"
  [{::keys                [*pets]
    ::dynamic-router/keys [path-params]}]
  (let [ids-to-remove (-> path-params :id parse-long hash-set)
        [old-pets _] (swap-vals! *pets (partial into []
                                         (remove (comp ids-to-remove :id))))]
    (if (contains? old-pets (first ids-to-remove))
      {:status 204}
      {:status 404})))

(defmethod petstore-operations "find pet by id"
  [{::keys                [*pets]
    ::dynamic-router/keys [path-params]}]
  (if-let [pet (-> path-params :id parse-long
                 (->> (get @*pets)))]
    {:headers {"Content-Type" "application/json"}
     :body    (json/write-str pet)
     :status  200}
    {:status 404}))

(defn create
  []
  (let [*pets (atom [])
        handler (dynamic-router/wrap-router
                  {::dynamic-router/ring-handler petstore-operations})]
    (fn [request]
      (handler (assoc request ::*pets *pets)))))
