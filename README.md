# ourives

> forging the ring

This is an *Work In Progress* pure-clojure implementation of a HTTP server following the ring2 spec.

# Priorities

- [ ] Make it work
- [ ] Make it right
- [ ] Make it fast

# Design issues

- How to handle bodies
- Handle chunked

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
