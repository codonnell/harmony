(ns harmony.rest
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [harmony.util :as util]
            [harmony.http :as http])
  (:import [java.time Instant]))

(s/def ::path-fragment (s/or :route-param keyword?
                             :resource-name string?))
(s/def ::path (s/coll-of ::path-fragment :kind vector? :gen-max 5))

(defn- resolve-ids [path params]
  (mapv (fn [fragment]
          (if (keyword? fragment)
            (str (or (get params fragment)
                     (throw (ex-info "Route param missing from params map"
                                     {:path path
                                      :route-param fragment
                                      :params params}))))
            fragment))
        path))

(defn resolve-path
  ([path]
   (resolve-path path {}))
  ([path params]
   (str/join "/" (resolve-ids path params))))

(s/fdef resolve-path
  :args (s/cat :path ::path :params (s/map-of keyword? string?))
  :ret string?)

(defn rate-limit-key
  ([path]
   (rate-limit-key path {}))
  ([path params]
   (cond (empty? path) nil
         ;; Not supporting webhook api yet
         ;; These paths all have the form ["channels"/"guilds" :id "minor-resource"]
         (#{"channels" "guilds"} (first path)) (resolve-ids (take 3 path) params)
         ;; Non-major ids do not get separate rate limits
         :else (into [] (take-while string?) path))))

(defn- route-limited? [{:keys [remaining reset] :as route-limit}]
  (if route-limit
    (and (zero? remaining) (.isAfter reset (Instant/now)))
    false))

(defn rate-limited?
  ([rate-limits path]
   (rate-limited? rate-limits path {}))
  ([rate-limits path params]
   (or (route-limited? (::global rate-limits))
       (when-let [k (rate-limit-key path params)]
         (route-limited? (get rate-limits k))))))

(defn- update-route-limit [rate-limits k]
  (let [{:keys [reset] :as route-limit} (get rate-limits k)]
    (if (and route-limit (.isAfter reset (Instant/now)))
      (update-in rate-limits [k :remaining] dec)
      (dissoc rate-limits k))))

(defn with-request
  ([rate-limits path]
   (with-request rate-limits path {}))
  ([rate-limits path params]
   (if-let [k (rate-limit-key path params)]
     (-> rate-limits
         (update-route-limit ::global)
         (update-route-limit k))
     rate-limits)))

(defrecord Client [state token http-client])

(def base-url "https://discordapp.com/api/v6")

(defn- auth-headers [token]
  {"Authorization" (str "Bot " token)})

(defn create-message! [{:keys [http-client token]} channel-id message]
  (let [url (str base-url "/" (resolve-path ["channels" :channel-id "messages"]
                                            {:channel-id channel-id}))]
    (http/POST http-client url {:headers (assoc (auth-headers token)
                                                :content-type "application/json")
                                :body (util/encode-json {:content message
                                                         :tts false})})))

(defn create-dm-channel! [{:keys [http-client token]} user-id]
  (let [url (str base-url "/" (resolve-path ["users" "@me" "channels"]))]
    (http/POST http-client url {:headers (assoc (auth-headers token)
                                                :content-type "application/json")
                                :body (util/encode-json {:recipient-id user-id})})))

(defn create-dm! [rest-client user-id message]
  (let [channel-id (-> rest-client (create-dm-channel! user-id) :body util/parse-json :id)]
    (create-message! rest-client channel-id message)))
