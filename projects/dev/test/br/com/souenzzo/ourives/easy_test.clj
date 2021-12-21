(ns br.com.souenzzo.ourives.easy-test
  (:require [clojure.test :refer [deftest]]
            [midje.sweet :refer [fact =>]]
            [clojure.core.async :as async]
            [ring.core.protocols]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.tools.reader.edn :as edn]
            [ring.util.mime-type :as mime])
  (:import (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers HttpClient$Version)
           (java.net URI ServerSocket Socket)
           (java.io Closeable BufferedReader)
           (java.nio.charset StandardCharsets)
           (java.time Clock ZonedDateTime ZoneOffset)
           (java.time.format DateTimeFormatter)
           (org.apache.commons.io.input BoundedInputStream)
           (org.apache.commons.io.output ChunkedOutputStream)))

(set! *warn-on-reflection* true)
(def code->reason-phrase
  {100 "Continue",
   101 "Switching Protocols",
   200 "OK",
   201 "Created",
   202 "Accepted",
   203 "Non-Authoritative Information",
   204 "No Content",
   205 "Reset Content",
   206 "Partial Content",
   300 "Multiple Choices",
   301 "Moved Permanently",
   302 "Found",
   303 "See Other",
   304 "Not Modified",
   305 "Use Proxy",
   307 "Temporary Redirect",
   400 "Bad Request",
   401 "Unauthorized",
   402 "Payment Required",
   403 "Forbidden",
   404 "Not Found",
   405 "Method Not Allowed",
   406 "Not Acceptable",
   407 "Proxy Authentication Required",
   408 "Request Timeout",
   409 "Conflict",
   410 "Gone",
   411 "Length Required",
   412 "Precondition Failed",
   413 "Payload Too Large",
   414 "URI Too Long",
   415 "Unsupported Media Type",
   416 "Range Not Satisfiable",
   417 "Expectation Failed",
   426 "Upgrade Required",
   500 "Internal Server Error",
   501 "Not Implemented",
   502 "Bad Gateway",
   503 "Service Unavailable",
   504 "Gateway Timeout",
   505 "HTTP Version Not Supported"})

(def ring-keys
  #{:body :character-encoding :content-length :content-type :headers :protocol :query-string :remote-addr
    :request-method :scheme :server-name :server-port :ssl-client-cert :uri})

(defn ^Closeable start
  [{:keys  [server-port handler]
    ::keys [^Clock clock]
    :or    {clock (Clock/systemUTC)}
    :as    env}]
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
                                   (let [[k v] (string/split line #":[\s]{0,}")]
                                     (recur (assoc headers
                                              k v))))))
                     size (some-> headers
                            (get "Content-Length")
                            Long/parseLong)
                     {:keys [status headers body]
                      :as   response} (handler (assoc env
                                                 :body (if (number? size)
                                                         (doto (BoundedInputStream. body
                                                                 ^Long size)
                                                           (.setPropagateClose false))
                                                         body)
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
                 (.write out (.getBytes (str "HTTP/1.1 " status " "
                                          (code->reason-phrase status)
                                          "\r\n")
                               StandardCharsets/UTF_8))
                 (doseq [[k v] (merge {;; https://httpwg.org/specs/rfc7231.html#header.date
                                       "Date"              (.format DateTimeFormatter/RFC_1123_DATE_TIME
                                                             (ZonedDateTime/now clock))
                                       "Server"            "ourives/dev"}
                                 headers)]
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
  (with-open [server (start {::clock      (proxy [Clock] []
                                            (instant []
                                              (.toInstant #inst"2000"))
                                            (getZone [] ZoneOffset/UTC))
                             :handler     (fn [request]
                                            {:body    (pr-str
                                                        {:ring-keys  (select-keys request (disj ring-keys :body))
                                                         :slurp-body (slurp (:body request))})
                                             :headers {"Hello"        "42"
                                                       "Content-Type" (mime/default-mime-types "edn")}
                                             :status  200})
                             :server-port 8080})]
    (let [client (HttpClient/newHttpClient)
          res (.send client (-> "http://127.0.0.3:8080/hello?world=42"
                              URI/create
                              HttpRequest/newBuilder
                              (.version HttpClient$Version/HTTP_1_1)
                              .build)
                (HttpResponse$BodyHandlers/ofString))]
      (fact
        {:body    (edn/read-string (.body res))
         :headers (into (sorted-map)
                    (map (fn [[k vs]]
                           [k (if (next vs)
                                (vec vs)
                                (first vs))]))
                    (.map (.headers res)))
         :status  (.statusCode res)}
        => {:body    {:ring-keys  {:headers        {"Content-Length" "0"
                                                    "Host"           "127.0.0.3"
                                                    "User-Agent"     "Java-http-client/17.0.1"}
                                   :protocol       "HTTP/1.1"
                                   :query-string   "world=42"
                                   :request-method :get
                                   :scheme         :http
                                   :server-port    8080
                                   :uri            "/hello"}
                      :slurp-body ""}
            :headers {"content-type" "application/edn"
                      "date"         "Sat, 1 Jan 2000 00:00:00 GMT"
                      "hello"        "42"
                      "server"       "ourives/dev"}
            :status  200}))))

