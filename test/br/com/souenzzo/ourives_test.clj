(ns br.com.souenzzo.ourives-test
  (:require [clojure.test :refer [deftest]]
            [br.com.souenzzo.ourives :as ourives]
            [br.com.souenzzo.ourives.easy :as ourives.easy]
            [io.pedestal.http :as http]
            [midje.sweet :refer [fact =>]]
            [br.com.souenzzo.ourives.test :refer [response-for http-client]]
            [clojure.string :as string]
            [clojure.java.io :as io]
            [ring.util.mime-type :as mime]
            [io.pedestal.log :as log]
            [io.pedestal.interceptor :as interceptor])
  (:import (java.net URI ServerSocket Socket InetAddress SocketException)
           (java.net.http HttpClient
                          HttpRequest
                          HttpResponse$BodyHandlers HttpRequest$BodyPublishers)
           (javax.servlet ServletConfig ServletInputStream ServletOutputStream Servlet)
           (java.lang AutoCloseable)
           (javax.servlet.http HttpServletRequest HttpServletResponse)
           (java.util Collections)
           (java.nio.charset StandardCharsets)
           (java.io ByteArrayOutputStream)))

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
                      (string/split header-line #":\s?" 2))]
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
      (getServerPort [this] (.getLocalPort socket))
      (getRequestURI [this] path)
      (getContextPath [this] "")
      (getServerName [this] "localhost")
      (getQueryString [this] query)
      (getInputStream [this] (proxy [ServletInputStream] []))
      (getScheme [this] "http")
      (getMethod [this] method)
      (getContentLengthLong [this] 0)
      (getContentType [this] "text/plain")
      (getCharacterEncoding [this] (str StandardCharsets/UTF_8))
      (getAttribute [this k]))))

(defn socket->http-servlet-response
  [^Socket socket]
  (let [*status (atom false)
        *headers (atom [["Date" "Wed, 09 Jun 2021 00:42:16 GMT"]])
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
          (.write out (.getBytes "\r\n" StandardCharsets/UTF_8)))]
    (reify
      AutoCloseable
      (close [this] (.close out))
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
            (.write out b off len))))
      (flushBuffer [this]
        (.flush out)))))

(defn http:type [{::http/keys [^Servlet servlet]
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
                       (some-> *server deref ^AutoCloseable .close)
                       nil))))

(set! *warn-on-reflection* true)

(deftest hello-pedestal
  (let [*req (promise)
        server (-> {::http/routes         #{["/foo/:id" :post (fn [req]
                                                                (deliver *req req)
                                                                {:headers {"Other"        "one"
                                                                           "Content-Type" (mime/default-mime-types "edn")}
                                                                 :body    (pr-str {:hello "World"})
                                                                 :status  200})
                                             :route-name :hello]}
                    ::http/port           8080
                    ::http/type           http:type
                    ::http/chain-provider http/create-servlet}
                 http/default-interceptors
                 http/create-server
                 http/start)]
    (try
      (let [client (HttpClient/newHttpClient)
            uri (URI/create (str "http://localhost:8080/foo/123?foo=42"))
            response (.send client (.build (.POST (HttpRequest/newBuilder uri)
                                             (HttpRequest$BodyPublishers/ofString
                                               "bodies")))
                       (HttpResponse$BodyHandlers/ofString))]
        (fact
          (.body response)
          => "{:hello \"World\"}")
        (fact
          (.statusCode response)
          => 200)
        (fact
          (into {}
            (map (fn [[k v]]
                   [k (vec v)]))
            (.map (.headers response)))
          => {;; "content-length"                    ["16"]
              "content-security-policy"           ["object-src 'none'; script-src 'unsafe-inline' 'unsafe-eval' 'strict-dynamic' https: http:;"]
              "date"                              ["Wed, 09 Jun 2021 00:42:16 GMT"]
              "other"                             ["one"]
              "content-type"                      ["application/edn"]
              "strict-transport-security"         ["max-age=31536000; includeSubdomains"]
              "x-content-type-options"            ["nosniff"]
              "x-download-options"                ["noopen"]
              "x-frame-options"                   ["DENY"]
              "x-permitted-cross-domain-policies" ["none"]
              "x-xss-protection"                  ["1; mode=block"]}))
      (fact
        (select-keys (deref *req 100 {})
          [:async-supported? :character-encoding :content-length :content-type :context-path :headers
           :params :path-info :path-params :protocol :query-params :query-string :remote-addr
           :request-method :scheme :server-name :server-port :uri
           #_:servlet-response #_:servlet #_:body #_:servlet-request #_:url-for])
        => {:async-supported?   false
            :character-encoding "UTF-8"
            :content-length     0
            :query-params       {:foo "42"}
            :params             {:foo "42"}
            :query-string       "foo=42"
            :content-type       "text/plain"
            :context-path       ""
            :headers            {"connection"     "Upgrade, HTTP2-Settings"
                                 "upgrade"        "h2c"
                                 "http2-settings" "AAEAAEAAAAIAAAABAAMAAABkAAQBAAAAAAUAAEAA"
                                 "content-length" "0"
                                 "content-type"   "text/plain"
                                 "host"           "localhost:8080"
                                 "user-agent"     "Java-http-client/16.0.1"}
            :path-info          "/foo/123"
            :path-params        {:id "123"}
            :protocol           "HTTP/1.1"
            :remote-addr        "127.0.0.1"
            :request-method     :post
            :scheme             :http
            :server-name        "localhost"
            :server-port        8080
            :uri                "/foo/123"})
      (finally
        (http/stop server)))))

(def ring-request-keys
  [:ring.request/body
   :ring.request/headers
   :ring.request/method
   :ring.request/path
   :ring.request/protocol
   :ring.request/query
   :ring.request/remote-addr
   :ring.request/scheme
   :ring.request/server-name
   :ring.request/server-port
   :ring.request/ssl-client-cert])

(deftest integration
  (let [*request (promise)
        client (HttpClient/newHttpClient)]
    (with-open [server ^AutoCloseable (ourives.easy/start {::ourives/handler (fn [{:ring.request/keys [body]
                                                                                   :as                req}]
                                                                               (deliver *request (assoc req
                                                                                                   :ring.request/body (slurp body)))
                                                                               {:ring.response/body    "world!"
                                                                                :ring.response/headers {"hello" "123"}
                                                                                :ring.response/status  200})})]
      (let [server-port (::ourives.easy/port server)
            uri (URI/create (str "http://localhost:" server-port "/path?query=123"))
            response (.send client (.build (.POST (HttpRequest/newBuilder uri)
                                             (HttpRequest$BodyPublishers/ofString
                                               "bodies")))
                       (HttpResponse$BodyHandlers/ofString))]
        (fact
          (.body response)
          => "world!")
        (fact
          (.statusCode response)
          => 200)
        (fact
          (into {}
            (map (fn [[k vs]]
                   [k (vec vs)]))
            (.map (.headers response)))
          => {"hello" ["123"]})
        (fact
          (select-keys @*request ring-request-keys)
          => {:ring.request/body        "bodies"
              :ring.request/headers     {"connection"     "Upgrade, HTTP2-Settings"
                                         "content-length" "6"
                                         "host"           (str "localhost:" server-port)
                                         "http2-settings" "AAEAAEAAAAIAAAABAAMAAABkAAQBAAAAAAUAAEAA"
                                         "upgrade"        "h2c"
                                         "user-agent"     "Java-http-client/16.0.1"}
              :ring.request/method      :post
              :ring.request/path        "/path"
              :ring.request/query       "query=123"
              :ring.request/protocol    "HTTP/1.1"
              :ring.request/remote-addr "127.0.0.1"
              :ring.request/scheme      :http
              :ring.request/server-name "localhost"
              :ring.request/server-port server-port})))))
#_(.format java.time.format.DateTimeFormatter/RFC_1123_DATE_TIME (ZonedDateTime/now (ZoneId/of "UTC")))
(deftest response-for-test
  (let [*request (promise)
        handler (fn [{:ring.request/keys [body]
                      :as                req}]
                  (deliver *request (assoc req
                                      :ring.request/body (slurp body)))
                  {:ring.response/body    "world!"
                   :ring.response/headers {"hello" "123"}
                   :ring.response/status  200})]
    (fact
      (response-for handler
        {:ring.request/method :post
         :ring.request/body   "bodies"
         :ring.request/path   "/world"})
      => {:ring.response/body    "world!"
          :ring.response/headers {"hello" "123"}
          :ring.response/status  200})
    (fact
      (select-keys @*request ring-request-keys)
      => {:ring.request/headers     {"connection"     "Upgrade, HTTP2-Settings"
                                     "content-length" "6"
                                     "host"           "localhost"
                                     "http2-settings" "AAEAAEAAAAIAAAABAAMAAABkAAQBAAAAAAUAAEAA"
                                     "upgrade"        "h2c"
                                     "user-agent"     "Java-http-client/16.0.1"}
          :ring.request/method      :post
          :ring.request/body        "bodies"
          :ring.request/path        "/world"
          :ring.request/protocol    "HTTP/1.1"
          :ring.request/remote-addr "0.0.0.0"
          :ring.request/scheme      :http
          :ring.request/server-name "localhost"
          :ring.request/server-port -1})))


(defn app-handler
  [{:ring.request/keys [path]}]
  {:ring.response/body    (str "Hello from: " path)
   :ring.response/headers {"X-Custom" "Value"}
   :ring.response/status  200})


(deftest canonical-test
  (fact
    (response-for app-handler
      #:ring.request{:method :get :path "/world"})
    => {:ring.response/body    "Hello from: /world"
        :ring.response/headers {"X-Custom" "Value"}
        :ring.response/status  200}))
