(ns br.com.souenzzo.ourives.client.pedestal
  (:require [io.pedestal.http :as http]
            [br.com.souenzzo.ourives.client :as client]
            [clojure.string :as string])
  (:import (javax.servlet Servlet ServletOutputStream ServletInputStream)
           (javax.servlet.http HttpServletRequest HttpServletResponse)
           (java.util Collections)
           (java.io ByteArrayOutputStream InputStream)))
(set! *warn-on-reflection* true)

(defn ->http-servlet-request
  [{:keys [^InputStream body query-string server-name uri server-port headers scheme request-method]}]
  (let []
    (reify HttpServletRequest
      (getProtocol [this] #_version)
      (isAsyncSupported [this] false)
      (isAsyncStarted [this] false)
      (getRemoteAddr [this] #_"127.0.0.1")
      (getHeaderNames [this] (Collections/enumeration (keys headers)))
      (getHeaders [this name]
        (Collections/enumeration
          (keep (fn [[k v]]
                  (when (= k name)
                    v))
            headers)))
      (getServerPort [this] server-port)
      (getRequestURI [this] uri)
      (getContextPath [this] "")
      (getServerName [this] server-name)
      (getQueryString [this] query-string)
      (getInputStream [this]
        (proxy [ServletInputStream] []
          (read [b off len]
            (.read body b off len))))
      (getScheme [this] (name scheme))
      (getMethod [this] (string/upper-case (name request-method)))
      (getContentLengthLong [this] -1)
      (getContentType [this])
      (getCharacterEncoding [this])
      (getAttribute [this k]))))

(defn ->http-servlet-response
  [*response]
  (let [baos (ByteArrayOutputStream.)]
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
          (write [b off len]
            (let [x (.write baos b off len)]
              (swap! *response assoc :body (str baos))
              x))))
      (flushBuffer [this]
        (swap! *response assoc :body (str baos))))))

(defn client
  [service-map]
  (let [{::http/keys [^Servlet servlet service-fn]
         :as         service-map} (http/create-servlet service-map)]
    (assoc service-map
      ::client (reify client/IClient
                 (send [this req]
                   (let [*res (atom {})]
                     (service-fn servlet
                       (->http-servlet-request req)
                       (->http-servlet-response *res))
                     @*res))))))
