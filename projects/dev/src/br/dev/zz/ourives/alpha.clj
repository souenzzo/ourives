(ns br.dev.zz.ourives.alpha
  (:require [clojure.java.io :as io]
            [clojure.string :as string])
  (:import (java.io BufferedReader)
           (java.lang AutoCloseable)
           (java.net InetSocketAddress ServerSocket SocketException)))

(set! *warn-on-reflection* true)


(defprotocol IServer
  (local-port [this])
  (accept ^AutoCloseable [this])
  (closed? [this]))

(defn parse-request
  [x]
  (let [br (BufferedReader. (io/reader x))
        [request-method uri-and-query protocol] (string/split (.readLine br)
                                                  #" "
                                                  3)
        [uri query-string] (string/split uri-and-query #"\?" 2)
        headers (loop [headers {}]
                  (let [header-line (.readLine br)]
                    (if (string/blank? header-line)
                      headers
                      (let [[k v] (string/split header-line #":\s{0,}" 2)]
                        (recur (assoc headers (string/lower-case k) v))))))]
    {:body           x
     :headers        headers
     :protocol       protocol
     :query-string   query-string

     :remote-addr    "127.0.0.1"
     :request-method (keyword (string/lower-case request-method))
     :scheme         :http
     #_#_:server-name nil
     #_#_:server-port nil
     #_#_:ssl-client-cert nil
     :uri            uri}))

(defn start-accept!
  [{:keys [server-socket handler]
    :as   opts}]
  (Thread/startVirtualThread
    (fn []
      (try
        (with-open [client-socket (accept server-socket)]
          (start-accept! opts)
          (try
            (let [{:keys [headers status body]} (handler (parse-request (io/input-stream client-socket)))]
              (with-open [out (io/output-stream client-socket)]
                (.write out (.getBytes (string/join "\r\n" [(str "HTTP/1.1 " status " OK")
                                                            (str "Content-Length: " (count (.getBytes (str body))))
                                                            ""
                                                            body])))))))
        (catch SocketException ex
          (when-not (closed? server-socket)
            (throw ex)))))))

(extend-protocol IServer
  ServerSocket
  (local-port [this] (.getLocalPort this))
  (accept [this] (.accept this))
  (closed? [this] (.isClosed this)))

(defn ^ServerSocket start
  [{:keys [server-port] :as opts}]
  (let [server-socket (doto (ServerSocket.)
                        (.bind (InetSocketAddress. server-port)))]
    (start-accept! (assoc opts
                     :server-socket server-socket))
    server-socket))