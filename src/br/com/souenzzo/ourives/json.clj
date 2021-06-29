(ns br.com.souenzzo.ourives.json
  (:require [br.com.souenzzo.ourives.client :as client]
            [ring.core.protocols :as ring.proto]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.core.async :as async])
  (:import (java.io InputStream PipedInputStream PipedOutputStream)))

(defn write-request
  [{::keys [source write-options]
    :as    ring-request}]
  (merge ring-request
    (when-let [[_ v] (find ring-request source)]
      (let [pos (PipedOutputStream.)
            pis (PipedInputStream. pos)]
        (with-open [w (io/writer pos)]
          (apply json/write v w write-options))
        {:body pis}))))

(defn write-response
  [{::keys [source]
    :as    ring-response}]
  (merge ring-response
    (when-let [[_ v] (find ring-response source)]
      {:body (reify ring.proto/StreamableResponseBody
               (write-body-to-stream [this {::keys [write-options]} output-stream]
                 (with-open [w (io/writer output-stream)]
                   (apply json/write v w write-options))))})))

(defn read-request
  [{::keys [target read-options]
    :keys  [body]
    :as    ring-request}]
  (merge ring-request
    (when (and target body)
      (with-open [rdr (io/reader body)]
        {target (apply json/read rdr read-options)}))))

(defn read-response
  [{::keys [target read-options]
    :keys  [body]
    :as    ring-response}]
  (merge ring-response
    (when (and target body)
      (with-open [rdr (-> (if (instance? InputStream body)
                            body
                            (let [pos (PipedOutputStream.)
                                  pis (PipedInputStream. pos)]
                              (ring.proto/write-body-to-stream body ring-response pos)
                              pis))
                        io/reader)]
        {target (apply json/read rdr read-options)}))))

(defn client
  [client default-opts]
  (reify client/RingClient
    (send [this ring-request]
      (let [ring-request (write-request (merge default-opts ring-request))
            ring-response (client/send client ring-request)]
        (read-response (merge default-opts
                         (select-keys ring-request [::target ::read-options])
                         ring-response))))
    (send-async [this ring-request]
      (let [ex-handler (::client/ex-handler ring-request client/default-ex-handler)
            return (or (::client/return-chan ring-request)
                     (async/promise-chan nil ex-handler))
            ring-request (write-request (merge default-opts ring-request))
            promise-ring-response (client/send-async client ring-request)]
        (async/go
          (let [ring-response (async/<! promise-ring-response)]
            (->> ring-response
              (merge default-opts (select-keys ring-request [::target ::read-options]))
              read-response
              (async/>! return))))
        return))))
