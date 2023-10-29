(ns br.dev.zz.ourives.alpha
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [io.pedestal.log :as log])
  (:import (jakarta.ws.rs.core Response$Status)
           (java.io BufferedReader OutputStream)
           (java.net InetSocketAddress ServerSocket SocketException)))

(set! *warn-on-reflection* true)

(defn parse-ring-request
  [x]
  (let [br (BufferedReader. (io/reader x))
        [request-method uri-and-query protocol] (string/split (.readLine br)
                                                  #" " 3)
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

     #_#_:remote-addr "127.0.0.1"
     :request-method (keyword (string/lower-case request-method))
     :scheme         :http
     #_#_:server-name nil
     #_#_:server-port nil
     #_#_:ssl-client-cert nil
     :uri            uri}))

(defn write-ring-response
  [^OutputStream output-stream {:keys [headers status body]}]
  (.write output-stream (.getBytes (string/join "\r\n" (concat
                                                         [(str "HTTP/1.1 " status " " (str (Response$Status/fromStatusCode 200)))
                                                          (str "Content-Length: " (count (.getBytes (str body))))]
                                                         (for [[k v] headers]
                                                           (str k ": " v))
                                                         [""
                                                          body])))))

(defn start-accept!
  [^ServerSocket server-socket ring-handler]
  (Thread/startVirtualThread
    (fn []
      (try
        (with-open [socket (.accept server-socket)]
          (start-accept! server-socket ring-handler)
          (try
            (let [ring-request (parse-ring-request (.getInputStream socket))
                  ring-response (ring-handler ring-request)]
              (with-open [out (.getOutputStream socket)]
                (write-ring-response out ring-response)))))
        (catch SocketException ex
          (when-not (.isClosed server-socket)
            (throw ex)))))))

(defn ^ServerSocket start
  [{:keys [server-port ring-handler]}]
  (let [server-socket (doto (ServerSocket.)
                        (.bind (InetSocketAddress. server-port)))]
    (start-accept! server-socket ring-handler)
    (log/info :message "Started")
    server-socket))

