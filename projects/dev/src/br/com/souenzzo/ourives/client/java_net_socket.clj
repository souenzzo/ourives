(ns br.com.souenzzo.ourives.client.java-net-socket
  (:require [br.com.souenzzo.ourives.client :as client]
            [clojure.string :as string]
            [br.com.souenzzo.ourives.java.io :as http.io])
  (:import (java.net Socket InetSocketAddress InetAddress)
           (java.nio.charset StandardCharsets)
           (java.io InputStream OutputStream)))

(set! *warn-on-reflection* true)

(defn write-ring-request
  [^OutputStream out {:keys [^InputStream body #_character-encoding #_content-length #_content-type headers protocol
                             query-string #_remote-addr request-method scheme server-name server-port #_ssl-client-cert
                             uri]
                      :or   {request-method :get
                             scheme         :http
                             protocol       "HTTP/1.1"
                             uri            "/"}}]
  #_["GET /foo/?abc=123 HTTP/1.1"
     "Content-Length: 0"
     "Host: app.localhost:8080"
     "User-Agent: Java-http-client/16.0.1"
     "foo: 42"]
  (when-not (contains? #{"HTTP/1.1"} protocol)
    (throw (ex-info (str "Protocol " (pr-str protocol) " not supported")
             {:cognitect.anomalies/category :unsupported})))
  (when-not (contains? #{:http} scheme)
    (throw (ex-info (str "Scheme " (pr-str scheme) " not supported")
             {:cognitect.anomalies/category :unsupported})))
  (.write out (.getBytes (str
                           (string/upper-case (name request-method))
                           " "
                           uri
                           (when query-string
                             (str "?" query-string))
                           " " protocol)
                StandardCharsets/UTF_8))
  (doseq [[k v] (into [["Host" (str server-name
                                 (when (pos-int? server-port)
                                   (str ":" server-port)))]]
                  cat
                  [(when-not body
                     [["Content-Length" "0"]])
                   headers])]
    (.write out
      (.getBytes (str "\r\n"
                   k ": " v)
        StandardCharsets/UTF_8)))
  (.write out (.getBytes (str "\r\n\r\n")
                StandardCharsets/UTF_8))
  (when body
    (.transferTo body
      (if-let [content-length (get headers "Content-Length")]
        (http.io/bounded-output-stream out (Long/parseLong content-length))
        (http.io/chunked-output-stream out))))
  out)

(defn parse-ring-response
  [^InputStream in]
  (let [[_ version status message] (re-find #"([^\s]+)\s([^\s]+)\s(.+)"
                                     (http.io/is-read-line in))
        headers (loop [headers []]
                  (let [line (http.io/is-read-line in)]
                    (if (string/blank? line)
                      (into {} headers)
                      (recur (conj headers (string/split line #":\s{0,}" 2))))))]
    {:headers headers
     :body    (slurp (if-let [n (get headers "Content-Length")]
                       (http.io/bounded-input-stream in (Long/parseLong n))
                       (http.io/chunked-input-stream in)))
     :status  (Long/parseLong (str status))}))


(defn client
  [{::keys [ring-request->socket]
    :or    {ring-request->socket (fn [{:keys [server-name server-port remote-addr scheme]
                                       :or   {server-port -1
                                              scheme      :http}}]
                                   (let [address (InetAddress/getByName (or remote-addr server-name))
                                         socket-address (InetSocketAddress. address
                                                          (int (if (neg? server-port)
                                                                 ({:http  80
                                                                   :https 443} server-port)
                                                                 server-port)))]
                                     (doto (Socket.)
                                       (.connect socket-address))))}}]
  (reify client/RingClient
    (send [this ring-request]
      (with-open [socket ^Socket (ring-request->socket ring-request)
                  in (.getInputStream socket)
                  out (.getOutputStream socket)]
        (write-ring-request out ring-request)
        (parse-ring-response in)))))
