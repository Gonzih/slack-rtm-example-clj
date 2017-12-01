(ns slack-rtm-clj.core
  (:require [aleph.http :as http]
            [byte-streams :as bs]
            [cheshire.core :as json]
            [manifold.stream :as s]
            [environ.core :refer [env]]))

(defn bs->string [response-stream]
  (bs/to-string response-stream))

(defn string->map [string]
  (json/parse-string string true))

(def ->json json/generate-string)

(defn bs->map [response-stream]
  (string->map (bs->string response-stream)))

(defn parse-response [aleph-response]
  (let [{:keys [body status]} aleph-response
        body (bs->map body)]
    {:status status
     :body body}))

(defn websocket-connection-info! []
  (let [token (env :token)
        handshake-url (format "https://slack.com/api/rtm.start?token=%s" token)
        response (-> handshake-url
                     http/get
                     deref
                     parse-response
                     :body)]
    (when-not (:ok response)
      (throw (Exception. (format "Wrong response %s for %s" response handshake-url))))
    {:url (:url response)
     :id (-> response :self :id)}))

(defn ping-loop [conn]
  (s/put! conn (->json {:id 0 :type "ping"}))
  (Thread/sleep (* 10 1000))
  (recur conn))

(defn handle-message [message]
  (println message))

(defn slack-loop []
  (let [{:keys [url id]} (websocket-connection-info!)
        _ (println url id)
        conn @(http/websocket-client url {:headers {:origin "https://api.slack.com/"}})]
    (println "Connected to slack websocket")
    (future (ping-loop conn))
    (loop [conn conn]
      (let [msg @(s/take! conn)
            message (json/parse-string msg true)]
        (handle-message message))
      (recur conn))))

(defn -main []
  (slack-loop))
