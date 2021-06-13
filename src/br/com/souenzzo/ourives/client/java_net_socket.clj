(ns br.com.souenzzo.ourives.client.java-net-socket
  (:require [br.com.souenzzo.ourives.client :as client]
            [clojure.string :as string])
  (:import (java.net Socket InetSocketAddress InetAddress)
           (java.nio.charset StandardCharsets)))

(set! *warn-on-reflection* true)

(defn write-ring-request
  [out {:keys [request-method uri query-string headers]
        :or   {request-method :get
               uri            "/"}}]
  (.write out (.getBytes (str
                           (string/upper-case (name request-method))
                           " "
                           uri
                           (when query-string
                             (str "?" query-string))
                           " HTTP/1.1")


                StandardCharsets/UTF_8))
  (.write out (.getBytes (str "\r\n")
                StandardCharsets/UTF_8))
  (doseq [[k v] headers]
    (.write out
      (.getBytes (str "\r\n"
                   k ":" v)
        StandardCharsets/UTF_8)))
  (.write (.getBytes (str "\r\n")
            StandardCharsets/UTF_8))
  out)

(defn parse-ring-response
  [in]
  {:headers {}
   :body    ""
   :status  200})

(defn client
  []
  (reify client/RingClient
    (send [this ring-request]
      (let [{:keys [server-name server-port]} ring-request
            address (InetAddress/getByName server-name)
            socket-address (new InetSocketAddress address server-port)]
        (with-open [socket (doto (Socket.)
                             (.connect socket-address))
                    in (.getInputStream socket)
                    out (.getOutputStream socket)]
          (write-ring-request out ring-request)
          (parse-ring-response in))))))
