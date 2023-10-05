(ns br.dev.zz.ourives.alpha-test
  (:require [br.dev.zz.ourives.alpha :as ourives]
            [clojure.test :refer [deftest is]]
            [io.pedestal.http :as http])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)))

(set! *warn-on-reflection* true)


(deftest pedestal-provider
  (let [{::ourives/keys [handler]} (-> {::http/port           8080
                                        ::http/routes         #{["/" :get (fn [_]
                                                                            {:body   "hello"
                                                                             :status 200})
                                                                 :route-name :hello]}
                                        ::http/join?          false
                                        ::http/chain-provider ourives/chain-provider}
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
          (handler {:request-method :get
                    :uri            "/"})))))


(deftest hello
  (let [http-client (HttpClient/newHttpClient)]
    (with-open [server (ourives/start {:server-port 0
                                       :handler     (fn [req]
                                                      (def _req req)
                                                      {:body   "hello"
                                                       :status 200})})]
      (let [uri (URI. "http" nil "127.0.0.1" (ourives/local-port server) nil nil nil)]
        (is (= "hello"
              (.body (.send http-client
                       (.build (HttpRequest/newBuilder uri))
                       (HttpResponse$BodyHandlers/ofString)))))))))
