# ourives

> forging the ring

This is an *Work In Progress* pure-clojure implementation of a HTTP server following the ring spec.

# Priorities

- [ ] Make it work
- [ ] Make it right
- [ ] Make it fast

# Planned structure and API's

## Packages 

- `br.com.souenzzo.ourives/client` Ring HTTP Client protocol
- `br.com.souenzzo.ourives/client.java-net-http` Client Protocol implementation via java.net.http
- `br.com.souenzzo.ourives/client.java-net-socket` Client Protocol implementation via java.net.socket
- `br.com.souenzzo.ourives/client.node` Client Protocol implementation via nodejs http package.
- `br.com.souenzzo.ourives/pedestal` Pedestal integration tools
- `br.com.souenzzo.ourives/server` Pure Clojure HTTP server over java.net.socket
- `br.com.souenzzo.ourives/java.io` HTTP IO Utilities


# Current State 

- `br.com.souenzzo.ourives.test` should provide testing helpers, inspired by `io.pedestal.test` 
- `br.com.souenzzo.ourives.easy` should provide an easy way to start an server  
- Testing should have the same API as a "real" HTTP request
- Should flow ring2 spec
- Just blocking for now.
- 2x slower then reitit

# Simple test

Applications that use ourives should be easy to test

Here an example of testing:

```clojure 
(defn app-handler
  [{:ring.request/keys [path]}]
  {:ring.response/body    (str "Hello from: " path)
   :ring.response/headers {"X-Custom" "Value"}
   :ring.response/status  200})

(deftest simple-test
  (fact
    (response-for app-handler
      #:ring.request{:method :get :path "/world"})
    => {:ring.response/body    "Hello from: /world"
        :ring.response/headers {"X-Custom" "Value"}
        :ring.response/status  200}))
```

Despite to look like a simple call function call, what actually happens is:

- The `#:ring.request{:method :get :path "/world"}` request will be converted into an `java.net.HttpRequest`
- The `java.net.HttpRequest` will be serialized into a `java.net.Socket` with a `java.io.InputStream` 
- The `java.net.Socket` will be parsed as a "real" request, writing into a `OutputStream`
- This `OutputStream` will be parsed, turned into `java.net.HttpResponse`
- `HttpResponse` will be converted into `ring response map`
