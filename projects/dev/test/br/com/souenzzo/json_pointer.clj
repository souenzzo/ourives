(ns br.com.souenzzo.json-pointer
  "

  https://tools.ietf.org/html/rfc6901
  "
  (:require [clojure.string :as string]))

;; from https://github.com/everit-org/json-schema/blob/master/core/src/main/java/org/everit/json/schema/JSONPointer.java#L142

(defn escape
  [s]
  (-> (cond
        (qualified-ident? s) (str (namespace s) "/" (name s))
        (ident? s) (name s)
        :else (str s))
    (string/replace "~" "~0")
    (string/replace "/" "~1")
    (string/replace "\\" "\\\\")
    (string/replace "\"" "\\\"")))

(defn unescape
  [s]
  (-> s
    (string/replace "~1" "/")
    (string/replace "~0" "~")
    (string/replace "\\\"" "\"")
    (string/replace "\\\\" "\\")))
