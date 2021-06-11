(ns br.com.souenzzo.ourives.pedestal
  (:refer-clojure :exclude [type])
  (:require [clojure.string :as string]
            [clojure.java.io :as io]
            [io.pedestal.http :as http]
            [br.com.souenzzo.ourives :as ourives]
            [io.pedestal.log :as log]
            [br.com.souenzzo.ourives.io :as oio])
  (:import (javax.servlet Servlet ServletConfig ServletInputStream ServletOutputStream)
           (java.net ServerSocket SocketException Socket)
           (java.lang AutoCloseable)
           (javax.servlet.http HttpServletResponse HttpServletRequest)
           (java.nio.charset StandardCharsets)
           (java.util Collections)))

(set! *warn-on-reflection* true)

(defn ^AutoCloseable socket->http-servlet-request
  [^Socket socket]
  (let [in (.getInputStream socket)
        [req-line & header-lines] (binding [*in* (io/reader in)]
                                    (loop [lines []]
                                      (let [line (read-line)]
                                        (if (string/blank? line)
                                          lines
                                          (recur (conj lines line))))))
        [_ method raw-path version] (re-find #"^([A-Z]+)\s(.+)\s([^\s]+)$"
                                      req-line)
        [path query] (string/split raw-path #"\?" 2)
        headers-seq (for [header-line header-lines]
                      (string/split header-line #":\s?" 2))
        content-length (some->> headers-seq
                         (filter (comp #{"Content-Length"} first))
                         first
                         last
                         Long/parseLong)
        content-type (some->> headers-seq
                       (filter (comp #{"Content-Type"} first))
                       first
                       last)
        charset (some-> content-type
                  (string/split #";\{0,}" 2)
                  (->> (map #(string/split % #"=" 2))
                    (filter (comp #{"charset"} first)))
                  first)
        cin (oio/bounded-input-stream in content-length)]
    (reify
      AutoCloseable
      (close [this] (.close in))
      HttpServletRequest
      (getProtocol [this] version)
      (isAsyncSupported [this] false)
      (isAsyncStarted [this] false)
      (getRemoteAddr [this] "127.0.0.1")
      (getHeaderNames [this] (Collections/enumeration (map first headers-seq)))
      (getHeaders [this name]
        (Collections/enumeration
          (keep (fn [[k v]]
                  (when (= k name)
                    v))
            headers-seq)))
      (getServerPort [this]
        (or #_header-host-after-double-dot
          (.getLocalPort socket)))
      (getRequestURI [this] path)
      (getContextPath [this] "")
      (getServerName [this]
        ;; (or host-header server-ip-address)
        "localhost")
      (getQueryString [this] query)
      (getInputStream [this] (proxy [ServletInputStream] []
                               (read [b off len]
                                 (.read cin b off len))))
      (getScheme [this] "http")
      (getMethod [this] method)
      (getContentLengthLong [this] (or content-length -1))
      (getContentType [this] content-type)
      (getCharacterEncoding [this] charset)
      (getAttribute [this k]))))

(defn ^AutoCloseable socket->http-servlet-response
  [^Socket socket]
  (let [*status (atom false)
        *headers (atom [["Date" "Wed, 09 Jun 2021 00:42:16 GMT"]
                        ["transfer-encoding" "chunked"]])
        out (.getOutputStream socket)
        !write-status-line
        (delay
          (let [status @*status]
            (.write out (.getBytes (str "HTTP/1.1 " status " " (ourives/status->message status) "\r\n")
                          StandardCharsets/UTF_8))))
        !write-headers
        (delay
          @!write-status-line
          (doseq [[k v] @*headers]
            (.write out (.getBytes (str k ": " v "\r\n")
                          StandardCharsets/UTF_8)))
          (.write out (.getBytes "\r\n" StandardCharsets/UTF_8)))
        cout (oio/chunked-output-stream out)]
    (reify
      AutoCloseable
      (close [this]
        (.close cout))
      HttpServletResponse
      (isCommitted [this] (boolean @*status))
      (setHeader [this k v]
        (swap! *headers conj [k v]))
      (setStatus [this status]
        (reset! *status status))
      (setContentType [this content-type]
        (swap! *headers conj ["Content-Type" content-type]))
      (getOutputStream [this]
        (proxy [ServletOutputStream] []
          (write [b off len]
            @!write-headers
            (.write cout b off len))))
      (flushBuffer [this]
        (.flush out)))))

(defn type
  [{::http/keys [^Servlet servlet]
    :as         service-map}
   {:keys [port host]}]
  (let [*server (atom nil)]
    (assoc service-map
      ::http/server *server
      ::http/start-fn (fn []
                        (future
                          (with-open [server-socket (ServerSocket. port
                                                      #_(InetAddress/getByName host))]
                            (reset! *server server-socket)
                            (.init servlet (reify ServletConfig))
                            (try
                              (loop []
                                (with-open [socket (.accept server-socket)
                                            req (socket->http-servlet-request socket)
                                            res (socket->http-servlet-response socket)]
                                  (.service servlet req res))
                                (recur))
                              (catch SocketException _)
                              (catch Throwable ex
                                (log/error :exception ex))))
                          (.destroy servlet)))
      ::http/stop-fn (fn []
                       (when-let [server ^AutoCloseable @*server]
                         (.close server))
                       nil))))

(defn chain-provider
  [service-map]
  (http/create-servlet service-map))
