(ns br.dev.zz.ourives.alpha-test
  (:require [br.dev.zz.ourives.alpha :as ourives]
            [clojure.test :refer [deftest is]])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)))

(set! *warn-on-reflection* true)

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
