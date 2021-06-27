(ns br.com.souenzzo.ourives.client
  (:refer-clojure :exclude [send send-async]))

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

    It can return a promise, callback function, or any other native async interface")
  (send-async [this ring-request]
    "Like send, but returns a clojure.core.async/promise

    In case of exception, it should return the exception in the channel.
    In success case, should return the same as `send`.
    This one is usually implemented in terms of `-sendAsync`
    "))
