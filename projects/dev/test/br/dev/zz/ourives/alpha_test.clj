(ns br.dev.zz.ourives.alpha-test
  (:require [br.dev.zz.ourives.pedestal :as op]
            [br.dev.zz.ourives.alpha :as ourives]
            [clojure.test :refer [deftest is]]
            [io.pedestal.http :as http]
            [io.pedestal.http.route :as route])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)))

(set! *warn-on-reflection* true)

(deftest pedestal-provider
  (let [{::op/keys [ring-handler]} (-> {::http/routes         #{["/" :get [{:enter (fn [ctx]
                                                                                     (assoc ctx :response {:body   "hello"
                                                                                                           :status 200}))}]
                                                                 :route-name :hello]}
                                        ::http/chain-provider op/chain-provider}
                                     http/create-provider)]
    (is (= {:body    "hello"
            :headers {"Content-Security-Policy"           "object-src 'none'; script-src 'unsafe-inline' 'unsafe-eval' 'strict-dynamic' https: http:;"
                      "Strict-Transport-Security"         "max-age=31536000; includeSubdomains"
                      "X-Content-Type-Options"            "nosniff"
                      "X-Download-Options"                "noopen"
                      "X-Frame-Options"                   "DENY"
                      "X-Permitted-Cross-Domain-Policies" "none"
                      "X-XSS-Protection"                  "1; mode=block"}
            :status  200}
          (ring-handler {:request-method :get
                         :uri            "/"})))))

(deftest pedestal-type
  (let [http-client (HttpClient/newHttpClient)
        {::http/keys [routes]
         :as         service-map} (-> op/service-map
                                    (assoc ::http/port 8080
                                           ::http/routes #{["/" :get (fn [_]
                                                                       {:body   "hello"
                                                                        :status 200})
                                                            :route-name :hello]}
                                           ::http/join? false)
                                    http/default-interceptors
                                    http/create-server
                                    http/start)
        url-for (route/url-for-routes (route/expand-routes routes)
                  :scheme :http
                  :host "127.0.0.1"
                  :port 8080)]
    (try
      (is (= "hello"
            (.body (.send http-client
                     (.build (HttpRequest/newBuilder (URI/create (url-for :hello))))
                     (HttpResponse$BodyHandlers/ofString)))))
      (finally
        (http/stop service-map)))))

(deftest hello
  (let [http-client (HttpClient/newHttpClient)]
    (with-open [server (ourives/start {:server-port 0
                                       :ring-handler (fn [_]
                                                       {:body   "hello"
                                                        :status 200})})]
      (let [uri (URI. "http" nil "127.0.0.1" (.getLocalPort server) nil nil nil)]
        (is (= "hello"
              (.body (.send http-client
                       (.build (HttpRequest/newBuilder uri))
                       (HttpResponse$BodyHandlers/ofString)))))))))



(deftest pedestal-with-headers-type
  (let [http-client (HttpClient/newHttpClient)
        {::http/keys [routes]
         :as         service-map} (-> op/service-map
                                    (assoc ::http/port 8080
                                           ::http/routes #{["/" :get (fn [_]
                                                                       {:body    "hello"
                                                                        :headers {"wow" "123"}
                                                                        :status  200})
                                                            :route-name :hello]}
                                           ::http/join? false)
                                    http/default-interceptors
                                    http/create-server
                                    http/start)
        url-for (route/url-for-routes (route/expand-routes routes)
                  :scheme :http
                  :host "127.0.0.1"
                  :port 8080)]
    (try
      (is (= {"content-length"                    "5"
              "content-security-policy"           "object-src 'none'; script-src 'unsafe-inline' 'unsafe-eval' 'strict-dynamic' https: http:;"
              "strict-transport-security"         "max-age=31536000; includeSubdomains"
              "wow"                               "123"
              "x-content-type-options"            "nosniff"
              "x-download-options"                "noopen"
              "x-frame-options"                   "DENY"
              "x-permitted-cross-domain-policies" "none"
              "x-xss-protection"                  "1; mode=block"}
            (into {}
              (map (fn [[k v]]
                     (if (next v)
                       [k (vec v)]
                       [k (first v)])))
              (.map (.headers (.send http-client
                                (.build (HttpRequest/newBuilder (URI/create (url-for :hello))))
                                (HttpResponse$BodyHandlers/ofString)))))))
      (finally
        (http/stop service-map)))))


