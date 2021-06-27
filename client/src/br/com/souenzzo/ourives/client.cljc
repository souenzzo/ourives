(ns br.com.souenzzo.ourives.client
  (:refer-clojure :exclude [send send-async])
  (:require [clojure.spec.alpha :as s]))

(set! *warn-on-reflection* true)

(defn default-ex-handler
  [ex]
  #?(:clj (let [ct (Thread/currentThread)]
            (-> (.getUncaughtExceptionHandler ct)
              (.uncaughtException ct ex)))))

(s/def ::ex-handler fn?)
(s/def ::return-chan any?)

(defprotocol RingClient
  "Should allow many HTTP Clients be implemented over the ring protocol."
  (send [this ring-request]
    "ring-request should be a map ring spec

    https://github.com/ring-clojure/ring/blob/master/SPEC

    A minimal example:
    ```clojure
    {:method      :get
     :scheme      :http
     :server-name \"example.com\"}
    ```

    As ring has too many required keys, this interface will only require :method, :scheme and :server-name

    It should return at least :status.

    Each implementation can implement custom behavior using qualified keywords on both request and response.
    ")
  (-sendAsync [this ring-request]
    "Like send, but returns a implementation-specific future.

    It can return a promise, callback function, or any other native async interface

    Should be considered internal")
  (send-async [this ring-request]
    "Like send, but returns a clojure.core.async/promise-chan

    In case of exception, it should return the exception in the channel.
    In success case, should return the same as `send`.
    This one is usually implemented in terms of `-sendAsync`

    Extra keys in ring-request

    ::ex-handler must be a fn of one argument -
    if an exception occurs during transformation it will be called with
    the Throwable as an argument, and any non-nil return value will be
    placed in the channel.

    ::return-chan an optional promise channel that will be returned

    The channel will be closed after the exception.
    "))
