(ns br.com.souenzzo.ourives.client.pedestal
  (:require [io.pedestal.http :as http]
            [br.com.souenzzo.ourives.client :as client]
            [clojure.string :as string]
            [clojure.java.io :as io])
  (:import (javax.servlet Servlet ServletOutputStream ServletInputStream AsyncContext)
           (javax.servlet.http HttpServletRequest HttpServletResponse)
           (java.util Collections)
           (java.io ByteArrayOutputStream InputStream)
           (java.nio.charset StandardCharsets)))
(set! *warn-on-reflection* true)

(defn ^HttpServletRequest ->http-servlet-request
  "Keep in sync with

  io.pedestal.test/test-servlet-request
  "
  [{:keys [^InputStream body query-string server-name uri server-port headers scheme request-method]
    :or   {server-port -1
           body        (InputStream/nullInputStream)
           uri         "/"}}]
  (let [async-context (atom nil)
        completion (promise)
        meta-data {:completion completion}]
    (with-meta
      (reify HttpServletRequest
        (getMethod [this] (string/upper-case (name request-method)))
        #_(getRequestURL [this] (StringBuffer. (str uri)))
        (getServerPort [this] server-port)
        (getServerName [this] server-name)
        (getRemoteAddr [this] "127.0.0.1")
        #_(getRemotePort [this] 0)
        (getRequestURI [this] uri)
        (getServletPath [this] (.getRequestURI this))
        (getContextPath [this] "")
        (getQueryString [this] query-string)
        (getScheme [this] (some-> scheme name))
        (getInputStream [this]
          (proxy [ServletInputStream]
                 []
            (available ([] (.available body)))
            (read ([] (.read body))
              ([^bytes b] (.read body b))
              ([^bytes b ^Integer off ^Integer len] (.read body b off len)))))
        (getProtocol [this] "HTTP/1.1")
        (isAsyncSupported [this] true)
        (isAsyncStarted [this] (some? @async-context))
        (getAsyncContext [this] @async-context)
        (startAsync [this]
          (compare-and-set! async-context
            nil
            (reify AsyncContext
              (complete [this]
                (deliver completion true)
                nil)
              (setTimeout [this n]
                nil)
              (start [this r]
                nil)))
          @async-context)
        (getHeaderNames [this] (Collections/enumeration (vec (keys headers))))
        #_(getHeader [this k] (get headers k))
        (getHeaders [this name]
          (Collections/enumeration
            (keep (fn [[k v]]
                    (when (= k name)
                      v))
              headers)))
        #_(getContentLength [this] (Integer/parseInt (get headers "Content-Length" "0")))
        (getContentLengthLong [this] (Long/parseLong (get headers "Content-Length" "0")))
        (getContentType [this] (get headers "Content-Type" ""))
        (getCharacterEncoding [this] (str StandardCharsets/UTF_8))
        (getAttribute [this k])
        (setAttribute [this k v] nil))
      meta-data)))

(defn ->http-servlet-response
  [*response]
  (let [baos (ByteArrayOutputStream.)
        #_#_*body (delay
                    (io/input-stream (.toByteArray baos)))]
    (reify HttpServletResponse
      (isCommitted [this] (boolean (:status @*response)))
      (setHeader [this k v]
        (swap! *response assoc-in [:headers k] v))
      (setStatus [this status]
        (swap! *response assoc :status status))
      (setContentType [this content-type]
        (.setHeader this "Content-Type" content-type))
      (getOutputStream [this]
        (proxy [ServletOutputStream] []
          (write
            ([arg] (let [x (if (number? arg)
                             (.write baos (int arg))
                             (.write baos (bytes arg)))]
                     (swap! *response assoc :body (str baos))
                     x))
            ([b off len]
             (let [x (.write baos b off len)]
               (swap! *response assoc :body (str baos))
               x)))))
      (flushBuffer [this]
        (swap! *response assoc :body (str baos))))))

(defn client
  [{::http/keys [^Servlet servlet service-fn]}]
  (reify client/RingClient
    (send [this req]
      (let [*res (atom {})
            servlet-request (->http-servlet-request req)]
        (service-fn servlet
          servlet-request
          (->http-servlet-response *res))
        (when (.isAsyncStarted servlet-request)
          (-> servlet-request meta :completion deref))
        @*res))))


(defn create-client
  [service-map]
  (assoc service-map
    ::client (client service-map)))
