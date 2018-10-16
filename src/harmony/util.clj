(ns harmony.util
  (:require [clojure.string :as str]))

(defn- ->keybab [s]
  (-> s str/lower-case (str/replace \_ \-) keyword))

(defn keybabulate
  "Takes a map with string, snake-cased keys and returns that map with its keys
  as kebab-cased keywords."
  [m]
  (into {} (map (fn [[k v]] [(->keybab k) v])) m))

(defn deep-keybabulate
  "Takes a possibly nested map with string, snake-cased keys and returns that map
  with its keys and all keys of nested maps as kebab-cased keywords."
  [m]
  (into {}
        (map (fn [[k v]]
               [(->keybab k)
                (if (map? v) (deep-keybabulate v) v)]))
        m))
