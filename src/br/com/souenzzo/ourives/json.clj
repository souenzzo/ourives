(ns br.com.souenzzo.ourives.json
  (:require [br.com.souenzzo.ourives.client :as client]
            [ring.core.protocols :as ring.proto]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.core.async :as async])
  (:import (java.io ByteArrayOutputStream InputStream)))

(defn parse-request
  [{::keys [source write-options]
    :as    ring-request}]
  (merge ring-request
    (when-let [[_ v] (find ring-request source)]
      (let [baos (ByteArrayOutputStream.)]
        (with-open [w (io/writer baos)]
          (apply json/write v w write-options))
        {:body (io/input-stream (.toByteArray baos))}))))

(defn parse-response
  [{::keys [target read-options]
    :keys  [body]
    :as    ring-response}]
  (with-open [rdr (io/reader (cond
                               (instance? InputStream body) body
                               :else (let [baos (ByteArrayOutputStream.)]
                                       (ring.proto/write-body-to-stream
                                         body ring-response baos)
                                       (.toByteArray baos))))]
    (assoc ring-response
      target (apply json/read rdr read-options))))

(defn client
  [client default-opts]
  (reify client/RingClient
    (send [this ring-request]
      (let [ring-request (parse-request (merge default-opts ring-request))
            ring-response (client/send client ring-request)]
        (parse-response (merge default-opts
                          (select-keys ring-request [::target ::read-options])
                          ring-response))))
    (send-async [this ring-request]
      (let [ex-handler (::client/ex-handler ring-request client/default-ex-handler)
            return (or (::client/return-chan ring-request)
                     (async/promise-chan nil ex-handler))
            ring-request (parse-request (merge default-opts ring-request))
            promise-ring-response (client/send-async client ring-request)]
        (async/go
          (let [ring-response (async/<! promise-ring-response)]
            (->> (parse-response (merge default-opts
                                   (select-keys ring-request [::target ::read-options])
                                   ring-response))
              (async/>!  return))))
        return))))
