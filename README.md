# ourives

> forging the ring

This is an *Work In Progress* pure-clojure implementation of a HTTP client and server following the ring spec.

# HTTP Client API

The main client protocol is `br.com.souenzzo.ourives.client/RingClient`.

This protocol can be extended by many HTTP Clients.

The current implementations are:

## br.com.souenzzo.ourives/client.java-net-http

This one extend the JVM native `java.net.HttpClient`

Usage example:

```clojure
#_(require '[br.com.souenzzo.ourives.client :as client]
    ;; require just to extend the protocol
    '[br.com.souenzzo.ourives.client.java-net-http])
#_(import '(java.net HttpClient))
(client/send (HttpClient/newHttpClient)
  {:request-method :get
   :scheme         :https
   :server-name    "example.com"})
#_#_=> {:status  200
        :body    #object [InputStream]
        :headers {"key" "value"}}
```

## br.com.souenzzo.ourives/client.pedestal

This one is a replacement for `io.pedestal.test`. You can turn your pedestal app into a client and run your tests on it.

```clojure
;; main namespace
#_(require '[io.pedestal.http :as http])

(def routes
  #{["/" :get (fn [_]
                {:status 202})
     :route-name ::hello]})

(def service-map
  (-> {::http/routes routes}
    http/default-interceptors))

;; test namespace
#_(require '[clojure.test :refer [deftest is]]
    '[br.com.souenzzo.ourives.client :as client]
    '[br.com.souenzzo.ourives.client.pedestal :as ocp])

(deftest hello
  (let [client (-> service-map
                 http/create-servlet
                 ocp/client)]
    (client/send client
      {:request-method :get
       :uri            "/"})
    => {:status 202}))
```

## [WIP] br.com.souenzzo.ourives/json

Handle JSON on both request/response. Should be possible to use on client and server.

Usage example:

```clojure
#_(require '[br.com.souenzzo.ourives.client :as client]
    ;; require just to extend the protocol
    '[br.com.souenzzo.ourives.client.java-net-http]
    '[br.com.souenzzo.ourives.json :as json])
#_(import '(java.net HttpClient))
(client/send (-> (HttpClient/newHttpClient)
               ;; just compose a client with another
               (json/client {;; where the JSON will be on request
                             ::json/source ::value
                             ;; where it should be placed on response
                             ::json/target ::value}))
  {:request-method :get
   ::value         {:hello "json"}
   :scheme         :https
   :server-name    "example.com"})
#_#_=> {:status  200
        :body    #object [InputStream]
        ::value  {"world" "json"}
        :headers {"key" "value"}}
```

# Future plans

## Clients

- `br.com.souenzzo.ourives/client.java-net-socket`: A pure-clojure HTTP client from java.net.Socket's
- `br.com.souenzzo.ourives/client.nodejs`: An implementation over `http` and `https` packages in nodejs.
- `br.com.souenzzo.ourives/client.fetch`: An implementation over `window.fetch` browser API
- `br.com.souenzzo.ourives/client.apache-http-client`: Why not?!

Some implementations, like nodejs and browser, will not have the `send` sync method

## Client Utils

- `cookie-store`: A generic cookie store that will work with any client
- `body-handlers`: A generic client-over-client implementation to handle body params and parse responses
- `metrics`: A generic client-over-client impl to log/metrics

## Server

- `br.com.souenzzo.ourives/server.java-net-socket`: A pure-clojure HTTP server over java.net.Socket's
- `br.com.souenzzo.ourives/server.pedestal`: An adapter of `server.java-net-socket` to pedestal.
- `br.com.souenzzo.ourives/server.nodejs`: Why not an wrap over nodejs `https/http` packages

## Common utils

- `br.com.souenzzo.ourives/java.io`: Pure-clojure HTTP IO Components over Java IO interfaces.

# Other ideias

- Should be possible to implement a generic cookie-store over the protocol

```clojure
(-> client ;; any implementation: java, nodejs... 
  ourives.cookie-store/with-cookie-store)
```

- Should be possible to implement a generic client-wide metrics/logs

- Once every client flows ring spec and ring spec says that the response `:body` should satisfies
  `ring.core.protocols/StreamableResponseBody` we can have a generic implementation that parse the `:body` into JSON or
  a String

```clojure
(-> client
  ourives.response/with-json-response)
```

- Same for request bodies

# Community

Interested in a maven release? In some planned/missing feature?  
Feel free to open an Issue, do a PR or talk with me in any network.

Know that there is ppl interested will make me more interested to work on this library 
