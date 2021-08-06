(ns br.com.souenzzo.ourives.easy
  (:require [br.com.souenzzo.ourives :as ourives])
  (:import (java.net ServerSocket SocketException)
           (java.lang AutoCloseable)
           (java.util Map)))
(set! *warn-on-reflection* true)

(defn start
  [{::keys [port]
    :or    {port 0}
    :as    m}]
  (let [*socket-server (promise)
        running (future
                  (try
                    (with-open [server-socket (ServerSocket. port)]
                      (deliver *socket-server server-socket)
                      (loop []
                        (with-open [socket (.accept server-socket)]
                          (ourives/handle-socket
                            (assoc m ::ourives/socket socket)))
                        (recur)))
                    (catch SocketException ex)))]
    (let [^ServerSocket socket-server @*socket-server
          kv {::socket-server socket-server
              ::port          (.getLocalPort socket-server)}]
      (reify
        Map
        (size [this] (count kv))
        (containsKey [this k] (contains? kv k))
        (entrySet [this]
          (set (seq kv)))
        (get [this k]
          (get kv
            k))
        AutoCloseable
        (close [this]
          (.close socket-server)
          @running)))))
