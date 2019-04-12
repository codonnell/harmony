(ns harmony.http
  (:require [clj-http.client :as http]
            [gniazdo.core :as ws]
            [cheshire.core :as json]))

(defprotocol IClient
  (GET [this url] [this url opts]
    "Makes a GET request with the given url and options map and returns a
    response map.")
  (POST [this url] [this url opts]
    "Makes a POST request with the given url and options map and returns a
    response map.")
  (PATCH [this url] [this url opts]
    "Makes a PATCH request with the given url and options map and returns a
    response map.")
  (DELETE [this url] [this url opts]
    "Makes a DELETE request with the given url and options map and returns a
    response map.")
  (ws-connect [this url opts]
    "Given a url and options map, connects to a server and returns a `IWSClient`
    that can be used to communicate over the WebSocket protocol."))

(defprotocol IWSClient
  (send-message [this message]
    "Sends a string message to the websocket.")
  (send-json [this json]
    "Sends a json-encoded message to the websocket.")
  (close [this]
    "Closes the websocket."))

(defrecord WSClient [websocket]
  IWSClient
  (send-message [this message]
    (ws/send-msg websocket message))
  (send-json [this json]
    (let [msg (json/encode json)]
      (println "Sending message" msg)
      (ws/send-msg websocket (json/encode json))))
  (close [this]
    (ws/close websocket)))

(defn ws-client [websocket]
  (->WSClient websocket))

(defrecord Client []
  IClient
  (GET [this url]
    (http/get url))
  (GET [this url opts]
    (http/get url opts))
  (POST [this url]
    (http/post url))
  (POST [this url opts]
    (http/post url opts))
  (PATCH [this url]
    (http/patch url))
  (PATCH [this url opts]
    (http/patch url opts))
  (DELETE [this url]
    (http/delete url))
  (DELETE [this url opts]
    (http/delete url opts))
  (ws-connect [this url opts]
    (ws-client (apply ws/connect url (into [] cat opts)))))

(def client (->Client))
