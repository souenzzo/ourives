(ns br.com.souenzzo.ourives.test
  (:require [br.com.souenzzo.ourives :as ourives]
            [clojure.string :as string]
            [clojure.java.io :as io]
            [br.com.souenzzo.ourives.io :as oio])
  (:import (java.net Socket URI)
           (java.io ByteArrayOutputStream InputStream SequenceInputStream StringWriter)
           (java.nio.charset StandardCharsets)
           (java.net.http HttpClient HttpHeaders HttpResponse$ResponseInfo HttpResponse HttpResponse$BodyHandler HttpRequest HttpRequest$BodyPublisher HttpRequest$BodyPublishers HttpClient$Version HttpResponse$BodyHandlers)
           (java.util.function BiPredicate)
           (java.nio ByteBuffer)
           (java.util.concurrent Flow$Subscription Flow$Publisher Flow$Subscriber)))

(defn flow-publisher->result
  [^Flow$Publisher p n]
  (let [w (StringWriter.)
        result (promise)]
    (.subscribe p
      (reify Flow$Subscriber
        (onComplete [this]
          (deliver result (str w)))
        (onError [this ex]
          (deliver result ex))
        (onNext [this i]
          (dotimes [_ n]
            (.write w (.get ^ByteBuffer i))))
        (onSubscribe [this s]
          (.request s n))))
    result))

(defn body-publisher->str
  [^HttpRequest$BodyPublisher p]
  (let [n (.contentLength p)]
    (if (pos? n)
      (let [result @(flow-publisher->result p n)]
        (if (instance? Throwable result)
          (throw result)
          result))
      "")))

(defn str-over-body-handler
  [^HttpResponse$BodyHandler body-handler ^HttpResponse$ResponseInfo response-info body]
  (let [bs (.apply body-handler response-info)]
    (.onSubscribe bs (reify Flow$Subscription
                       (request [this n]
                         (.onNext bs [(ByteBuffer/wrap (.getBytes (str body)
                                                         StandardCharsets/UTF_8))])
                         (.onComplete bs))))
    (deref (.toCompletableFuture (.getBody bs)))))

(defn ^Socket socket-for
  [^HttpRequest req baos]
  (let [body (.orElse (.bodyPublisher req)
               (HttpRequest$BodyPublishers/ofString ""))
        uri (.uri req)
        port (.getPort uri)
        body-bytes (.getBytes (body-publisher->str body)
                     StandardCharsets/UTF_8)
        headers (into [["connection" "Upgrade, HTTP2-Settings"]
                       ["content-length" (str (count body-bytes))]
                       ["host" (str (.getHost uri)
                                 (when (and (number? port)
                                         (pos? port))
                                   (str ":" port)))]
                       ["http2-settings" "AAEAAEAAAAIAAAABAAMAAABkAAQBAAAAAAUAAEAA"]
                       ["upgrade" "h2c"]
                       ["user-agent" "Java-http-client/16.0.1"]]
                  (mapcat (fn [[k vs]]
                            (for [v vs]
                              [k v])))
                  (.map (.headers req)))
        query (.getQuery uri)]
    (proxy [Socket] []
      (getInputStream []
        (-> (string/join "\r\n"
              (concat [(str
                         (.method req)
                         " "
                         (str (.getPath uri)
                           (when query
                             (str "?" query)))
                         " " "HTTP/1.1")]
                (for [[k v] headers]
                  (str k ": " v))
                [""
                 ""]))
          (.getBytes StandardCharsets/UTF_8)
          (io/input-stream)
          (SequenceInputStream. (io/input-stream body-bytes))))
      (getOutputStream []
        baos))))

(defn http-response-for
  [^InputStream is ^HttpResponse$BodyHandler body-handler]
  (let [[_ version status-str] (re-find #"([^\s]+)\s([0-9]+)" (oio/is-read-line is))
        status (Long/parseLong status-str)
        headers (loop [acc {}]
                  (let [line (oio/is-read-line is)]
                    (if (empty? line)
                      (HttpHeaders/of acc
                        (reify BiPredicate
                          (test [this t u] true)))
                      (recur (let [[k v] (string/split line #":\s{0,}" 2)]
                               (update acc k (fnil conj []) v))))))
        http-body (str-over-body-handler body-handler (reify HttpResponse$ResponseInfo
                                                        (headers [this] headers)
                                                        (statusCode [this] status))
                    (slurp is))]
    (reify HttpResponse
      (statusCode [this] status)
      (headers [this] headers)
      (body [this] http-body)
      (version [this]
        (if (= version "HTTP/1.1")
          HttpClient$Version/HTTP_1_1
          HttpClient$Version/HTTP_2)))))

(defn ^HttpClient http-client
  [handler]
  (proxy [HttpClient] []
    (send [^HttpRequest req ^HttpResponse$BodyHandler body-handler]
      (let [baos (ByteArrayOutputStream.)
            socket (socket-for req baos)]
        (ourives/handle-socket
          {::ourives/socket  socket
           ::ourives/handler handler})
        (http-response-for
          (io/input-stream (.toByteArray baos))
          body-handler)))))


(defn ^HttpRequest request-for
  [{:ring.request/keys [headers query body path scheme server-name server-port protocol method]
    :or                {server-port -1
                        method      :get
                        server-name "localhost"
                        scheme      :http}}]
  (let [user-info nil
        fragment nil
        method (string/upper-case (name method))
        uri (URI. (name scheme)
              user-info server-name server-port path query fragment)]
    (cond-> (HttpRequest/newBuilder uri)
      :always (.method method (if body
                                (HttpRequest$BodyPublishers/ofString body)
                                (HttpRequest$BodyPublishers/noBody)))
      (seq headers) (.headers (into-array String (sequence
                                                   (comp (map (fn [[k vs]]
                                                                (for [v vs]
                                                                  [k v])))
                                                     cat cat)
                                                   headers)))
      (= protocol "HTTP/1.1") (.version HttpClient$Version/HTTP_1_1)
      (= protocol "HTTP/2.0") (.version HttpClient$Version/HTTP_2)
      :always .build)))


(defn response-for
  [handler request]
  (let [res (.send (http-client handler)
              (request-for request)
              (HttpResponse$BodyHandlers/ofString))]
    #:ring.response{:body    (.body res)
                    :headers (into {}
                               (map (fn [[k v]]
                                      [k (if (== 1 (count v))
                                           (first v)
                                           (vec v))]))
                               (.map (.headers res)))
                    :status  (.statusCode res)}))
