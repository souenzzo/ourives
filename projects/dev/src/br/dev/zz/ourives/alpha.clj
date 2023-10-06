(ns br.dev.zz.ourives.alpha
  (:refer-clojure :exclude [type])
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [io.pedestal.http :as http]
            [io.pedestal.interceptor.chain :as chain])
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

(defn chain-provider
  [{::http/keys [interceptors]
    :as         service-map}]
  (letfn [(handler [request]
            (-> service-map
              (assoc #_#_::handler handler
                     :request (merge {:path-info (:uri request)}
                                request))
              (chain/execute interceptors)
              :response))]
    (assoc service-map ::handler handler)))


(defn type
  [{::keys [handler] :as x} {:keys [port]}]
  (def _x x)
  (let [*server (delay (doto (ServerSocket.)
                         (.bind (InetSocketAddress. port))))]
    {:server   *server
     :start-fn (fn []
                 (start-accept! {:server-socket @*server
                                 :handler       handler}))

     :stop-fn  (fn []
                 (when (realized? *server)
                   (.close ^AutoCloseable @*server)))}))

(def service-map
  {::http/chain-provider chain-provider
   ::http/type           type})
