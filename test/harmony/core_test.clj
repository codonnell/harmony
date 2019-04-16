(ns harmony.core-test
  (:require [harmony.core :as h]
            [harmony.http :as http]
            [clojure.test :refer [deftest is]]))

;; What do we test?
;; * Successfully connect + identify
;; * Fail to connect + reconnect after
;; * Successfully connect + identify + disconnect + reconnect + send resume
;; * Successfully connect + identify + disconnect + reconnect + send resume + invalid session + wait and identify

(defn- actions->http-client [actions]
  (let [http-actions (filterv (comp #{:send :receive :ws-close} :type) actions)
        counter (atom 0)
        ws-client
        (reify http/IWSClient
          (send-message [this message]
            (let [{:keys [type method value]} (nth actions @counter)]
              (testing action
                (is (= :send type))
                (is (= :ws method))
                (is (= value message)))
              (swap! counter inc)))
          (send-json [this message]
            (let [{:keys [type method value]} (nth actions @counter)]
              (testing action
                (is (= :send type))
                (is (= :ws method))
                (is (= value message)))
              (swap! counter inc)))
          (close [this]
            (let [{:keys [type]} (nth actions @counter)]
              (testing action
                (is (= :ws-close type)))
              (swap! counter inc))))]
    (reify http/IClient
      (GET [this url]
        (GET this url {}))
      (GET [this url opts]
        (let [{:keys [type method response] url* :url :as action} (nth actions @counter)]
          (testing action
            (is (= :send type))
            (is (= :get method))
            (is (= url* url)))
          (swap! counter inc)
          response)))))

(deftest successful-connect-and-identify
  (assert-actions [:connect-success
                   :identify-success])
  (let [gateway (h/init-gateway {:token "fake"
                                 :http-client TODO})]
    ))
