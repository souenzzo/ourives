(ns br.com.souenzzo.ourives.easy-test
  (:require [clojure.test :refer [deftest]]
            [midje.sweet :refer [fact =>]]
            [clojure.core.async :as async]
            [ring.core.protocols]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.data.json :as json])
  (:import (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)
           (java.net URI ServerSocket Socket)
           (java.io Closeable BufferedReader)
           (java.nio.charset StandardCharsets)))

(set! *warn-on-reflection* true)


(defn ^Closeable start
  [{:keys [server-port handler]
    :as   base}]
  (let [server-socket (ServerSocket. server-port)
        sockets (async/chan 10)
        output (async/chan (async/sliding-buffer 1))]
    (async/pipeline-blocking 3
      output
      (map (fn [socket]
             (with-open [^Socket socket socket
                         body (.getInputStream socket)
                         out (.getOutputStream socket)
                         brdr (BufferedReader. (io/reader body))]
               (let [request-line (.readLine brdr)
                     [method path protocol] (string/split request-line #"\s" 3)
                     [uri query-string] (string/split path #"\?" 2)
                     headers (loop [headers {}]
                               (let [line (.readLine brdr)]
                                 (if (string/blank? line)
                                   headers
                                   (let [[k v] (string/split line #":[\s]{0,}")
                                         vs (get headers k [])]
                                     (recur (assoc headers
                                              k (conj vs v)))))))
                     {:keys [status headers body]
                      :as   response} (handler (assoc base
                                                 :body body
                                                 #_:character-encoding
                                                 #_:content-length
                                                 #_:content-type
                                                 :headers headers
                                                 :protocol protocol
                                                 :query-string query-string
                                                 #_#_:remote-addr remote-addr
                                                 :request-method (keyword (string/lower-case method))
                                                 :scheme :http
                                                 #_#_:server-name server-name
                                                 :server-port (.getLocalPort socket)
                                                 #_:ssl-client-cert
                                                 :uri uri))]
                 (.write out (.getBytes (str "HTTP/1.1 " status " OK\r\n") StandardCharsets/UTF_8))
                 (doseq [[k v] headers]
                   (.write out (.getBytes (str k ": " v "\r\n") StandardCharsets/UTF_8)))
                 (.write out (.getBytes "\r\n" StandardCharsets/UTF_8))
                 (ring.core.protocols/write-body-to-stream body response out)
                 (.flush out)))
             socket))
      sockets)
    (future
      (loop []
        (when (async/>!! sockets (.accept server-socket))
          (recur))))
    (reify Closeable
      (close [this]
        (async/close! sockets)
        (.close server-socket)))))

(deftest hello
  (with-open [server (start {:handler     (fn [_]
                                            {:body    (json/write-str {})
                                             :headers {"Hello" "42"}
                                             :status  200})
                             :server-port 8080})]
    (let [client (HttpClient/newHttpClient)
          res (.send client (-> "http://127.0.0.3:8080/hello"
                              URI/create
                              HttpRequest/newBuilder
                              .build)
                (HttpResponse$BodyHandlers/ofString))]
      (fact
        {:body    (.body res)
         :headers (into {}
                    (map (fn [[k vs]]
                           [k (if (next vs)
                                (vec vs)
                                (first vs))]))
                    (.map (.headers res)))
         :status  (.statusCode res)}
        => {}))))

