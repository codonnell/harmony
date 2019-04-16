(ns harmony.rest-test
  (:require [harmony.rest :as rest]
            [clojure.test :refer [deftest is]])
  (:import [clojure.lang ExceptionInfo]
           [java.time Instant]
           [java.time.temporal ChronoUnit]))

(deftest test-resolve-path
  (is (= "foo/1/bar"
         (rest/resolve-path ["foo" :id "bar"] {:id "1"})))
  (is (= "foo/1/bar"
         (rest/resolve-path ["foo" :id "bar"] {:id 1}))
      "Integer params are coerced to strings")
  (is (= "users/@me/guilds"
         (rest/resolve-path ["users" "@me" "guilds"]))
      "Paths with no route params can be resolved without passing params")
  (is (thrown? ExceptionInfo
               (rest/resolve-path ["foo" :id "bar"] {}))
      "An exception is thrown if a route param is not present in the params map")
  (is (= "" (rest/resolve-path [] {}))
      "Empty paths are resolved to the empty string"))

(deftest test-rate-limit-key
  (is (= ["channels" "1" "messages"]
         (rest/rate-limit-key ["channels" :channel-id "messages"]
                              {:channel-id "1"})))
  (is (= ["channels" "1" "messages"]
         (rest/rate-limit-key ["channels" :channel-id "messages"]
                              {:channel-id 1}))
      "Params are coerced to strings in rate limit keys")
  (is (= ["users" "@me" "guilds"]
         (rest/rate-limit-key ["users" "@me" "guilds"] {}))
      "Paths with no params are returned verbatim")
  (is (= ["users" "@me" "guilds"]
         (rest/rate-limit-key ["users" "@me" "guilds"]))
      "Params are optional for paths with no params")
  (is (= ["users"]
         (rest/rate-limit-key ["users" :user-id] {:user-id "1"}))
      "Routes starting with a minor parameter are truncated before the id")
  (is (nil? (rest/rate-limit-key [] {}))
      "`rate-limit-key` returns nil for empty paths"))

(defn- before [] (.minus (Instant/now) 10 ChronoUnit/SECONDS))
(defn- after [] (.plus (Instant/now) 10 ChronoUnit/SECONDS))

(deftest test-rate-limited?
  (is (false? (rest/rate-limited? {::rest/global {:remaining 1 :reset (after)}
                                   ["users"] {:remaining 1 :reset (after)}}
                                  ["users"] {}))
      "Not rate limited when there are requests remaining")
  (is (false? (rest/rate-limited? {::rest/global {:remaining 0 :reset (before)}
                                   ["users"] {:remaining 0 :reset (before)}}
                                  ["users"] {}))
      "Not rate limited when the limit has reset")
  (is (true? (rest/rate-limited? {::rest/global {:remaining 0 :reset (after)}
                                  ["users"] {:remaining 0 :reset (before)}}
                                 ["users"] {}))
      "Rate limited when global rate limit exceeded")
  (is (true? (rest/rate-limited? {::rest/global {:remaining 0 :reset (before)}
                                  ["users"] {:remaining 0 :reset (after)}}
                                 ["users"] {}))
      "Rate limited when route rate limit exceeded")
  (is (false? (rest/rate-limited? {::rest/global {:remaining 1 :reset (before)}}
                                  ["users"] {}))
      "Not rate limited when no route limit data exists")
  (is (false? (rest/rate-limited? {::rest/global {:remaining 1 :reset (before)}
                                   ["channels" "1" "messages"] {:remaining 0 :reset (after)}}
                                  ["channels" :channel-id "messages"]
                                  {:channel-id 2}))
      "Routes which have different major parameter values are not rate limited together")
  (is (true? (rest/rate-limited? {::rest/global {:remaining 1 :reset (before)}
                                  ["channels" "1" "messages"] {:remaining 0 :reset (after)}}
                                 ["channels" :channel-id "messages" :message-id]
                                 {:channel-id 1 :message-id 12}))
      "Routes which have the same major parameter value and different minor parameter values are rate limited together")
  (is (nil? (rest/rate-limited? {} [] {}))
      "`rate-limited?` returns nil for an empty path"))

(deftest test-with-request
  (let [aft (after)]
    (is (= {::rest/global {:remaining 0 :reset aft}}
           (rest/with-request {::rest/global {:remaining 1 :reset aft}} ["users"]))
        "Does not add a route limit if one does not exist")
    (is (= {} (rest/with-request {::rest/global {:remaining 1 :reset (before)}
                                  ["users"] {:remaining 1 :reset (before)}}
                ["users"]))
        "Removes a route limit when its reset time has passed")
    (is (= {::rest/global {:remaining 1 :reset aft}}
           (rest/with-request {::rest/global {:remaining 1 :reset aft}} []))
        "Requests with an empty path do not change rate limits")
    (is (= {::rest/global {:remaining 0 :reset aft}
            ["channels" "1" "messages"] {:remaining 0 :reset aft}}
           (rest/with-request {::rest/global {:remaining 1 :reset aft}
                               ["channels" "1" "messages"] {:remaining 1 :reset aft}}
             ["channels" :channel-id "messages"]
             {:channel-id 1}))
        "Paths with params will have their params resolved")))
