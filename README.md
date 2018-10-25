# Harmony

Harmony is a minimal Clojure wrapper for the Discord API.

## What Does Harmony Do?

* Maintain a connection to the discord gateway (heartbeat, automatically
  reconnect)
* Expose callback hooks for the user to respond to gateway events
* Provide wrapper functions for discord REST endpoints

## What Does Harmony Not Do?

Pretty much anything else. Harmony is designed to take care of the tedious
details of interacting with the Discord API while making as few design choices
for the user as possible.

## Example Usage

```clojure
(def rest-client (rest/map->Client {:auth "abcd"}))

(defn on-event [event]
  (when (and (= "MESSAGE_CREATE" (:t event))
             (= "!ping" (get-in event [:d :content])))
    (rest/create-message! rest-client {:channel-id (get-in event [:d :channel-id])
                                       :content "pong!"})))

(def gateway (gateway/connect! (gateway/map->Gateway {:auth "abcd"
                                                      :on-event on-event})))
```

## Philosophy

Harmony tries to be as unopinionated as possible, leaving choices open for the user to make. Gateway events are handled by a user-provided callback function, and the wrapper functions for REST endpoints provide both synchronous and asynchronous variants.

All network communication is done via a ring-like protocol, allowing the user to stub network components for testing.
