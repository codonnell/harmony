(ns harmony.util
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [cheshire.core :as json]))

(defn- ->keybab [s]
  (-> s str/lower-case (str/replace \_ \-) keyword))

(defn keybabulate
  "Takes a map with string, snake-cased keys and returns that map with its keys
  as kebab-cased keywords."
  [m]
  (into {} (map (fn [[k v]] [(->keybab k) v])) m))

(defn- transform-keys
  "Recursively transforms all map keys in coll with t."
  [t coll]
  (letfn [(transform [[k v]] [(t k) v])]
    (walk/postwalk (fn [x] (if (map? x) (into {} (map transform) x) x)) coll)))

(defn deep-keybabulate
  "Takes a possibly nested map with string, snake-cased keys and returns that map
  with its keys and all keys of nested maps as kebab-cased keywords."
  [m]
  (transform-keys ->keybab m))

(defn parse-json
  "Parses and `deep-keybabulate`s a json-encoded string."
  [s]
  (-> s json/decode deep-keybabulate))

(defn- keybab->snake [k]
  (-> k name (str/replace \- \_)))

(defn snakeulate
  "Takes a map with keyword, kebab-cased keys and returns that map with its keys
  as snake-cased strings."
  [m]
  (into {} (map (fn [[k v]] [(keybab->snake k) v]))))

(defn deep-snakeulate
  "Takes a possibly nested map with keyword, kebab-cased keys and returns that map
  with its keys and all keys of nested maps as snake-cased strings."
  [m]
  (transform-keys keybab->snake m))

(defn encode-json
  "JSON serializes and `deep-snakeulate`'s a clojure map"
  [m]
  (-> m deep-snakeulate json/encode))

(defn easy-template
  "Replaces substrings in `template-string` surrounded by curly braces with
  their entries in `replacement-map`.

  Example:
  => (easy-template \"{a}, {b}, {a}, and {bar}\" {:a \"1\" :b \"2\" :bar \"3\"})
  \"1, 2, 1, and 3\""
  [template-string replacement-map]
  (reduce-kv (fn [s k v] (str/replace s (format "{%s}" (name k)) v))
             template-string replacement-map))
