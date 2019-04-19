(ns harmony.core
  (:require [harmony.http :as http]
            [harmony.util :as util]
            [harmony.rest :as rest]
            [cheshire.core :as json])
  (:import [java.util.concurrent Executors TimeUnit]))

(def base-url "https://discordapp.com/api/v6")

(def op-codes {:dispatch 0
               :heartbeat 1
               :identify 2
               :status-update 3
               :voice-state-update 4
               :resume 6
               :reconnect 7
               :request-guild-members 8
               :invalid-session 9
               :hello 10
               :heartbeat-ack 11})

(defprotocol Connection
  (connect! [this])
  (reconnect! [this])
  (disconnect! [this]))

(defn heartbeat [seq-num]
  {:op (op-codes :heartbeat) :d seq-num})

(defn send-heartbeat! [ws-client seq-num]
  (http/send-json ws-client (heartbeat seq-num)))

(defmulti handle-system-message (fn [_ {:keys [op]}] op))

(defmethod handle-system-message :default
  [_ message]
  (println "Unhandled system message" message))

(defmethod handle-system-message (op-codes :hello)
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

(defmethod handle-system-message (op-codes :heartbeat)
  [{:keys [state]} _]
  (println "Sending heartbeat")
  (let [{:keys [seq-num ws-client]} @state]
    (send-heartbeat! ws-client seq-num)))

(defmethod handle-system-message (op-codes :dispatch)
  [{:keys [state on-event]} {:keys [s t d] :as event}]
  (println "Setting seq num" s)
  (swap! state
         #(cond-> %
            true (assoc :seq-num s)
            (= "READY" t) (assoc :session-id (:session-id d))))
  (try (when on-event
         (on-event event))
       (catch Throwable e
         (println "Error processing event" {:event event :error e}))))

(defmethod handle-system-message (op-codes :heartbeat-ack)
  [{:keys [state]} _]
  (println "Received heartbeat ack")
  (swap! state assoc :heartbeat-acked? true))

;; Leaving out other options here for now
(defn identify-body [{:keys [token os browser device]
                      :or {os "linux"
                           browser "harmony"
                           device "harmony"}}]
  {:op (op-codes :identify)
   :d {:token token
       :properties {:$os os
                    :$browser browser
                    :$device device}}})

(defn send-identify! [ws-client token]
  (http/send-json ws-client (identify-body {:token token})))

(defmethod handle-system-message (op-codes :invalid-session)
  [{:keys [state token executor]} _]
  (let [wait-time (+ 1000 (rand-int 4000))]
    (.schedule executor
               #(send-identify! (:ws-client @state) token)
               wait-time TimeUnit/MILLISECONDS)))

(defn resume-body [{:keys [token session-id seq-num]}]
  {:op (op-codes :resume)
   :d  {:token      token
        :session_id session-id
        :seq        seq-num}})

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
                                                (.cancel (:heartbeat-future @state) true)
                                                (swap! state dissoc
                                                       :heartbeat-future
                                                       :heartbeat-interval
                                                       :heartbeat-acked?)
                                                (println "Disconnected:" gateway status reason)
                                                (when-not (:disconnect? @state)
                                                  (println "Reconnecting...")
                                                  (reconnect! gateway)))})]
    (swap! state assoc :ws-client ws-client :disconnect? false)
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

(defrecord Gateway [state token url http-client executor on-event]
  Connection
  (connect! [this]
    (let [url (request-gateway-url this)
          new-gateway (assoc this :url url)]
      (ws-connect! new-gateway)
      (send-identify! (:ws-client @state) token)
      new-gateway))
  (reconnect! [this]
    (when-not (:disconnect? @state)
      (try
        (when-let [ws-client (:ws-client @state)]
          (println "Closing stale websocket client")
          (http/close ws-client)
          (swap! state dissoc :ws-client))
        (println "Establishing new websocket connection...")
        (ws-connect! this)
        (let [{:keys [session-id seq-num ws-client]} @state]
          (println "Sending resume...")
          (send-resume! ws-client {:session-id session-id
                                   :seq-num seq-num
                                   :token token})
          (println "Reconnected!")
          (swap! state dissoc ::reconnect-delay)
          this)
        (catch Throwable e
          (println "Error reconnecting" e)
          (let [{::keys [reconnect-delay]} (swap! state update ::reconnect-delay
                                                  (fnil #(min (* % 2) (* 1000 60 5)) 250))]
            (println "Reconnect failed. Attempting to reconnect again in " reconnect-delay " milliseconds.")
            (.schedule executor #(reconnect! this) reconnect-delay TimeUnit/MILLISECONDS))))))
  (disconnect! [this]
    (when (and state (:ws-client @state))
      (swap! state assoc :disconnect? true)
      (http/close (:ws-client @state)))
    (assoc this :state (atom {}))))

(defn init-gateway [{:keys [token http-client executor] :as opts}]
  (map->Gateway
   (cond-> (merge {:http-client http/client
                   :state (atom {})
                   :on-event (constantly nil)}
                  opts)
     (nil? executor) (assoc :executor (Executors/newScheduledThreadPool 1)))))

(defrecord Bot [gateway rest-client on-event]
  Connection
  (connect! [this]
    (update this :gateway connect!))
  (reconnect! [this]
    (update this :gateway reconnect!))
  (disconnect! [this]
    (update this :gateway disconnect!)))

(defn init-bot [{:keys [token on-event gateway rest-client] :as opts}]
  (let [bot (map->Bot (merge {:gateway (init-gateway {:token token})
                              :rest-client (rest/map->Client {:token token
                                                              :state (atom nil)
                                                              :http-client http/client})}
                             (dissoc opts :token)))]
    (cond-> bot
      on-event (assoc-in [:gateway :on-event] (partial on-event bot)))))
