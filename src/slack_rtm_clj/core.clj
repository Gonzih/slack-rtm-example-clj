(ns slack-rtm-clj.core
  (:require [aleph.http :as http]
            [byte-streams :as bs]
            [cheshire.core :as json]
            [environ.core :refer [env]]
            [manifold.stream :as ms]
            [clojure.spec.alpha :as s]))

(defn explain-or-valid? [spec x]
  (if (s/valid? spec x)
    true
    (do
      (s/explain spec x)
      false)))

(s/def ::type #{"hello" "message" "pong" "reconnect_url" "user_typing" "ping" "presence_change"})
(s/def ::id number?)
(s/def ::user string?)
(s/def ::reply_to number?)
(s/def ::url string?)
(s/def ::channel string?)
(s/def ::user string?)
(s/def ::text string?)
(s/def ::ts string?)
(s/def ::source_team string?)
(s/def ::team string?)
(s/def ::edited (s/keys :req-un [::user ::ts]))
(s/def ::subtype string?)
(s/def ::hidden boolean?)
(s/def ::presence #{"away"})

(defmulti incoming-message :type)
(defmethod incoming-message "hello" [_]
  (s/keys :req-un [::type]))
(defmethod incoming-message "message" [_]
  (s/keys :req-un [::type ::channel ::ts]
          :opt-un [::user ::text ::team ::source_team ::edited ::hidden ::message]))
(defmethod incoming-message "pong" [_]
  (s/keys :req-un [::type ::reply_to]))
(defmethod incoming-message "reconnect_url" [_]
  (s/keys :req-un [::type ::url]))
(defmethod incoming-message "user_typing" [_]
  (s/keys :req-un [::type ::channel ::user]))
(defmethod incoming-message "presence_change" [_]
  (s/keys :req-un [::type ::presence ::user]))

(defmulti outgoing-message :type)
(defmethod outgoing-message "ping" [_]
  (s/keys :req-un [::type ::id]))

(s/def ::incoming-message (s/multi-spec incoming-message :type))
(s/def ::outgoing-message (s/multi-spec outgoing-message :type))

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

(defn put-msg! [conn message]
  {:pre [(explain-or-valid? ::outgoing-message message)]}
  (ms/put! conn (->json message)))

(defn ping-loop [conn]
  (put-msg! conn {:id 0 :type "ping"})
  (Thread/sleep (* 10 1000))
  (recur conn))

(defn handle-message [message]
  {:pre [(explain-or-valid? ::incoming-message message)]}
  (println message))

(defn slack-loop []
  (let [{:keys [url id]} (websocket-connection-info!)
        _ (println url id)
        conn @(http/websocket-client url {:headers {:origin "https://api.slack.com/"}})]
    (println "Connected to slack websocket")
    (future (ping-loop conn))
    (loop [conn conn]
      (let [msg @(ms/take! conn)
            message (json/parse-string msg true)]
        (handle-message message))
      (recur conn))))

(defn -main []
  (slack-loop))
