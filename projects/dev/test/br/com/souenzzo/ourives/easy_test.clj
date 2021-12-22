(ns br.com.souenzzo.ourives.easy-test
  (:refer-clojure :exclude [send])
  (:require [clojure.test :refer [deftest]]
            [midje.sweet :refer [fact =>]]
            [clojure.core.async :as async]
            [ring.core.protocols]
            [br.com.souenzzo.ourives.java.io :as ourives.io]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.tools.reader.edn :as edn]
            [ring.util.mime-type :as mime])
  (:import (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers HttpClient$Version HttpRequest$BodyPublishers)
           (java.net URI ServerSocket Socket InetSocketAddress)
           (java.io Closeable BufferedReader BufferedOutputStream OutputStream ByteArrayOutputStream InputStream)
           (java.nio.charset StandardCharsets)
           (java.time Clock ZonedDateTime ZoneOffset)
           (java.time.format DateTimeFormatter)
           (org.apache.http.impl.io ChunkedOutputStream)
           (org.apache.commons.io.input BoundedInputStream)
           (org.apache.commons.io.output TeeOutputStream)
           (java.util.function Supplier)))

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

(defn request-line->ring-request
  [line]
  (let [[method path protocol] (string/split line #"\s" 3)
        [uri query-string] (string/split path #"\?" 2)]
    {:request-method (keyword (string/lower-case method))
     :uri            uri
     :query-string   query-string
     :protocol       protocol}))

(defn wrap-body
  [{:keys [headers body]
    :as   request}]
  (let [request-content-length (some-> headers
                                 (get "Content-Length")
                                 Long/parseLong)
        request-transfer-encoding (some-> (get headers "Transfer-Encoding")
                                    (string/split #",[\s]{0,}")
                                    set)]
    #_(cond-> body
        (contains? request-transfer-encoding
          (number? request-content-length)
          (-> (BoundedInputStream. ^Long request-content-length)
            (doto (.setPropagateClose false)))))
    (assoc request :body body)))

(defn wrap-out-response
  [response out]
  out)

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
                         out (.getOutputStream socket)]
               (let [request-line (ourives.io/is-read-line body)
                     headers (loop [headers {}]
                               (let [line (ourives.io/is-read-line body)]
                                 (if (string/blank? line)
                                   headers
                                   (let [[k v] (string/split line #":[\s]{0,}")]
                                     (recur (assoc headers
                                              k v))))))
                     {:keys [status headers body]
                      :as   response} (-> env
                                        (merge (request-line->ring-request request-line)
                                          {:body        body
                                           :headers     headers
                                           :remote-addr (.getHostAddress (.getAddress ^InetSocketAddress (.getRemoteSocketAddress socket)))
                                           :scheme      :http
                                           :server-name (get headers "Host" (str (.getInetAddress socket)))
                                           :server-port (.getLocalPort socket)
                                           #_:character-encoding
                                           #_:content-length
                                           #_:content-type
                                           #_:ssl-client-cert})
                                        wrap-body
                                        handler)
                     response-content-length (when-let [content-length (get headers "Content-Length")]
                                               (if (number? content-length)
                                                 content-length
                                                 (Long/parseLong content-length)))]
                 (.write out (.getBytes (str "HTTP/1.1 " status " "
                                          (code->reason-phrase status)
                                          "\r\n")
                               StandardCharsets/UTF_8))
                 (doseq [[k v] (merge {;; https://httpwg.org/specs/rfc7231.html#header.date
                                       "Date"   (.format DateTimeFormatter/RFC_1123_DATE_TIME
                                                  (ZonedDateTime/now clock))
                                       "Server" "ourives/dev"}
                                 (when-not (number? response-content-length)
                                   #_{"Transfer-Encoding" "chunked"})
                                 headers)]
                   (.write out (.getBytes (str k ": " v "\r\n") StandardCharsets/UTF_8)))
                 (.write out (.getBytes "\r\n" StandardCharsets/UTF_8))
                 (with-open [^OutputStream outt (wrap-out-response response out)]
                   (ring.core.protocols/write-body-to-stream body response outt))
                 ;; write trailers
                 #_()))
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

(def *http-client
  (delay (HttpClient/newHttpClient)))

(defn send
  [{:keys [uri server-port scheme server-name query-string request-method body]
    :or   {request-method :get
           body           (InputStream/nullInputStream)
           scheme         :http}}]
  (let [res (.send ^HttpClient @*http-client (-> (str (name scheme) "://" server-name ":" server-port uri (when query-string
                                                                                                            (str "?" query-string)))
                                               URI/create
                                               HttpRequest/newBuilder
                                               (.version HttpClient$Version/HTTP_1_1)
                                               (.method (string/upper-case (name request-method))
                                                 (HttpRequest$BodyPublishers/ofInputStream (reify Supplier
                                                                                             (get [this] body))))
                                               .build)
              (HttpResponse$BodyHandlers/ofString))]
    {:body    (.body res)
     :headers (into (sorted-map)
                (map (fn [[k vs]]
                       [k (if (next vs)
                            (vec vs)
                            (first vs))]))
                (.map (.headers res)))
     :status  (.statusCode res)}))


(deftest simple-get
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
    (fact
      "Simple HTTP get"
      (-> (send {:server-name    "app.localhost"
                 :server-port    8080
                 :request-method :get
                 :uri            "/hello"
                 :query-string   "world=42"})
        (update :body edn/read-string))
      => {:body    {:ring-keys  {:headers        {"Content-Length" "0"
                                                  "Host"           "app.localhost"
                                                  "User-Agent"     "Java-http-client/17.0.1"}
                                 :protocol       "HTTP/1.1"
                                 :query-string   "world=42"
                                 :request-method :get
                                 :scheme         :http
                                 :server-name    "app.localhost"
                                 :server-port    8080
                                 :uri            "/hello"}
                    :slurp-body ""}
          :headers {"content-type" "application/edn"
                    "date"         "Sat, 1 Jan 2000 00:00:00 GMT"
                    "hello"        "42"
                    "server"       "ourives/dev"}
          :status  200})))


(deftest simple-post
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
    (fact
      "Simple HTTP get"
      (-> (send {:server-name    "app.localhost"
                 :server-port    8080
                 :request-method :post
                 :headers        {"Content-Type" (mime/default-mime-types "txt")}
                 :body           (io/input-stream (.getBytes "Hello World!"))
                 :uri            "/hello"
                 :query-string   "world=42"})
        (update :body edn/read-string))
      => {:body    {:ring-keys  {:headers        {"Content-Length" "0"
                                                  "Host"           "app.localhost"
                                                  "User-Agent"     "Java-http-client/17.0.1"}
                                 :protocol       "HTTP/1.1"
                                 :query-string   "world=42"
                                 :remote-addr    "127.0.0.1"
                                 :request-method :post
                                 :scheme         :http
                                 :server-name    "app.localhost"
                                 :server-port    8080
                                 :uri            "/hello"}
                    :slurp-body ""}
          :headers {"content-type" "application/edn"
                    "date"         "Sat, 1 Jan 2000 00:00:00 GMT"
                    "hello"        "42"
                    "server"       "ourives/dev"}
          :status  200})))


