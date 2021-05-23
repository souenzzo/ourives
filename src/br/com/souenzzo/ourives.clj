(ns br.com.souenzzo.ourives
  (:require [clojure.string :as string]
            [ring.core.protocols :as ring.proto]
            [clojure.java.io :as io])
  (:import (java.io InputStream)
           (java.net URI Socket)
           (java.nio.charset StandardCharsets)))

(set! *warn-on-reflection* true)

;; from https://tools.ietf.org/html/rfc7231#section-6.1
(comment
  (->> "| 200 | OK | |"
    string/split-lines
    (map string/trim)
    (map #(string/split % #"\|"))
    (map #(map string/trim %))
    (map rest)
    (map (juxt (comp read-string first)
           second))
    (into (sorted-map))))

(def status->message
  {100 "Continue"
   101 "Switching Protocols"
   200 "OK"
   201 "Created"
   202 "Accepted"
   203 "Non-Authoritative Information"
   204 "No Content"
   205 "Reset Content"
   206 "Partial Content"
   300 "Multiple Choices"
   301 "Moved Permanently"
   302 "Found"
   303 "See Other"
   304 "Not Modified"
   305 "Use Proxy"
   307 "Temporary Redirect"
   400 "Bad Request"
   401 "Unauthorized"
   402 "Payment Required"
   403 "Forbidden"
   404 "Not Found"
   405 "Method Not Allowed"
   406 "Not Acceptable"
   407 "Proxy Authentication Required"
   408 "Request Timeout"
   409 "Conflict"
   410 "Gone"
   411 "Length Required"
   412 "Precondition Failed"
   413 "Payload Too Large"
   414 "URI Too Long"
   415 "Unsupported Media Type"
   416 "Range Not Satisfiable"
   417 "Expectation Failed"
   426 "Upgrade Required"
   500 "Internal Server Error"
   501 "Not Implemented"
   502 "Bad Gateway"
   503 "Service Unavailable"
   504 "Gateway Timeout"
   505 "HTTP Version Not Supported"})

(defn ^String is-read-line
  [^InputStream is]
  (loop [sb (StringBuffer.)]
    (let [c (.read is)]
      (case c
        10 (str sb)
        13 (recur sb)
        (recur (.append sb (char c)))))))

(defn bounded-input-stream
  "Worst impl ever!"
  [^InputStream is n]
  (let [*x (atom n)]
    (proxy [InputStream] []
      (read [buffer start end]
        (if (neg? (swap! *x dec))
          -1
          (.read is buffer 0 1))))))

(defn handle-socket
  [{::keys [^Socket socket handler]
    :as    m}]
  (with-open [body (io/input-stream socket)
              out (io/output-stream socket)]
    (let [remote-addr (.getHostAddress (.getLocalAddress socket))
          server-port (.getLocalPort socket)
          req-line (is-read-line body)
          s1 (.indexOf req-line 32)
          s2 (.lastIndexOf req-line 32)
          method (subs req-line 0 s1)
          path (subs req-line (inc s1) s2)
          version (subs req-line (inc s2) (count req-line))
          headers (loop [acc {}]
                    (let [line (is-read-line body)]
                      (if (empty? line)
                        acc
                        (recur (let [[k v] (string/split line #":\s{0,}" 2)
                                     k (string/lower-case k)
                                     sep (if (= "cookie" line)
                                           ";"
                                           ",")]
                                 (update acc k #(if %
                                                  (str % sep v)
                                                  v)))))))
          path-uri (URI/create path)
          host (get headers "host")
          content-length (try
                           (some-> headers
                             (get "content-length")
                             (Long/parseLong))
                           (catch Throwable NumberFormatException))
          server-name (if host
                        (.getHost (URI/create (str "http://" host)))
                        remote-addr)
          query (.getQuery path-uri)
          request (cond-> (assoc m
                            :ring.request/method (keyword (string/lower-case method))
                            :ring.request/path (.getPath path-uri)
                            :ring.request/server-port server-port
                            :ring.request/remote-addr remote-addr
                            :ring.request/scheme :http
                            :ring.request/body (if content-length
                                                 (bounded-input-stream body content-length)
                                                 body)
                            :ring.request/protocol version)
                    (seq headers) (assoc :ring.request/headers headers)
                    server-name (assoc :ring.request/server-name server-name)
                    query (assoc :ring.request/query query))
          {:ring.response/keys [body headers status]
           :as                 response} (handler request)]
      (.write out (.getBytes (str "HTTP/1.1 "
                               status
                               " "
                               (status->message status)
                               "\r\n")
                    StandardCharsets/UTF_8))
      (doseq [[k vs] headers
              v (if (coll? vs)
                  vs
                  [vs])]
        (.write out (.getBytes (str k ": " v "\r\n")
                      StandardCharsets/UTF_8)))
      (.write out (.getBytes "\r\n" StandardCharsets/UTF_8))
      (ring.proto/write-body-to-stream body response out))))

(comment
  ;; This will start an server that handle a single request, then stop
  ;; useful to develop/debug
  (with-open [server-socket (java.net.ServerSocket. 8080)
              client (.accept server-socket)]
    (handle-socket
      {::client  client
       ::handler (fn [req]
                   (def _req req)
                   {:ring.response/body    "world!"
                    :ring.response/headers {"hello" "123"}
                    :ring.response/status  200})}))
  ;; then run
  ;; curl http://localhost:8080/ -s -D -


  _)
