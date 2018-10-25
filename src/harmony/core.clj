(ns harmony.core
  (:require [harmony.http :as http]
            [harmony.util :as util]
            [cheshire.core :as json])
  (:import [java.util.concurrent Executors TimeUnit]))

(def base-url "https://discordapp.com/api/v6")

(defprotocol Connection
  (connect! [this])
  (reconnect! [this])
  (disconnect! [this]))

(defn heartbeat [seq-num]
  {:op 1 :d seq-num})

(defn send-heartbeat! [ws-client seq-num]
  (http/send-json ws-client (heartbeat seq-num)))

(defmulti handle-system-message (fn [_ {:keys [op]}] op))

(defmethod handle-system-message :default
  [_ message]
  (println "Unhandled system message" message))

(defmethod handle-system-message 10
  [{:keys [state executor] :as gateway} {:keys [d]}]
  (println "Starting heartbeat from" d)
  (let [heartbeat-interval (:heartbeat-interval d)
        {:keys [heartbeat-future]} @state]
    (when (and heartbeat-future (not (.isCancelled heartbeat-future)))
      (.cancel heartbeat-future true))
    (swap! state assoc
           :heartbeat-future
           (.scheduleAtFixedRate executor
                                 (fn []
                                   (let [{:keys [heartbeat-acked? ws-client seq-num
                                                 heartbeat-future]} @state]
                                     (if heartbeat-acked?
                                       (do (swap! state assoc :heartbeat-acked? false)
                                           (send-heartbeat! ws-client seq-num))
                                       (do (.cancel heartbeat-future true)
                                           (http/close ws-client)))))
                                 0 heartbeat-interval TimeUnit/MILLISECONDS)
           :heartbeat-interval heartbeat-interval
           :heartbeat-acked? true)))

(defmethod handle-system-message 1
  [{:keys [state]} _]
  (println "Sending heartbeat")
  (let [{:keys [seq-num ws-client]} @state]
    (send-heartbeat! ws-client seq-num)))

(defmethod handle-system-message 0
  [{:keys [state on-event]} {:keys [s t d] :as event}]
  (println "Setting seq num" s)
  (swap! state
         #(cond-> %
            true (assoc :seq-num s)
            (= "READY" t) (assoc :session-id (:session-id d))))
  (on-event event))

(defmethod handle-system-message 11
  [{:keys [state]} _]
  (println "Received heartbeat ack")
  (swap! state assoc :heartbeat-acked? true))

;; Leaving out other options here for now
(defn identify-body [{:keys [token os browser device]
                      :or {os "linux"
                           browser "harmony"
                           device "harmony"}}]
  {:op 2
   :d {:token token
       :properties {:$os os
                    :$browser browser
                    :$device device}}})

(defn send-identify! [ws-client token]
  (http/send-json ws-client (identify-body {:token token})))

(defmethod handle-system-message 9
  [{:keys [state token]} _]
  (let [wait-time (+ 1000 (rand 4000))]
    (future
      (Thread/sleep wait-time)
      (send-identify! (:ws-client @state) token))))

(defn resume-body [{:keys [token session-id seq-num]}]
  {:token token
   :session-id session-id
   :seq seq-num})

(defn send-resume! [ws-client config]
  (http/send-json ws-client (resume-body config)))

(defn- ws-connect!
  "Given a disconnected gateway, makes a websocket connection and returns the
  gateway."
  [{:keys [http-client url token state] :as gateway}]
  (let [ws-client (http/ws-connect http-client
                                   url
                                   {:on-receive #(let [json (util/parse-json %)]
                                                   (prn "Received message:" json)
                                                   (handle-system-message gateway json))
                                    :on-close (fn [status reason]
                                                (println "Disconnected:" gateway status reason)
                                                (when-not (:disconnect? @state)
                                                  (reconnect! gateway)))})]
    (swap! state assoc :ws-client ws-client)
    gateway))

(defn request-gateway-url
  "Requests a websocket url from the bot gateway endpoint and returns it."
  [{:keys [token http-client]}]
  (let [headers {"Authorization" (str "Bot " token)}
        endpoint "/gateway/bot"]
    (-> http-client
        (http/GET (str base-url endpoint) {:headers headers})
        (update :body util/parse-json)
        (get-in [:body :url])
        (str "/?v=6&encoding=json"))))

(defrecord Gateway [state token url http-client on-event]
  Connection
  (connect! [this]
    (let [url (request-gateway-url this)
          new-gateway (assoc this :url url)]
      (ws-connect! new-gateway)
      (send-identify! (:ws-client @state) token)
      new-gateway))
  (reconnect! [this]
    (http/close (:ws-client @state))
    (let [{:keys [session-id seq-num ws-client]} (ws-connect! this)]
      (send-resume! ws-client {:session-id session-id
                               :seq-num seq-num
                               :token token})
      this))
  (disconnect! [this]
    (when (and state (:ws-client @state))
      (swap! state assoc :disconnect? true)
      (http/close (:ws-client @state)))
    (assoc this :state (atom {}))))

(defn init-gateway [{:keys [token http-client executor] :as opts}]
  (map->Gateway
   (cond-> (merge {:http-client http/client
                   :state (atom {})}
                  opts)
     (nil? executor) (assoc :executor (Executors/newScheduledThreadPool 1)))))
