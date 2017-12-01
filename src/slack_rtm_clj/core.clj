(ns slack-rtm-clj.core
  (:require [aleph.http         :as    http]
            [byte-streams       :as    bs]
            [cheshire.core      :as    json]
            [environ.core       :refer [env]]
            [manifold.stream    :as    ms]
            [manifold.deferred  :as    d]
            [clojure.spec.alpha :as    s]))

(def ansi-reset  "\u001B[0m")
(def ansi-red    "\u001B[31m")
(def ansi-green  "\u001B[32m")
(def ansi-yellow "\u001B[33m")

(defn- paint [color & xs]
  (println (str color (reduce str (interpose " " xs)) ansi-reset)))

(def red (partial paint ansi-red))
(def green (partial paint ansi-green))
(def yellow (partial paint ansi-yellow))

(defn rand-id [] (rand-int 100000))

(defn explain-or-valid? [spec x]
  (if (s/valid? spec x)
    true
    (do
      (red (s/explain-str spec x))
      false)))

(s/def ::type string?)
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
(defmethod incoming-message "desktop_notification" [_]
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
(defmethod incoming-message nil [_]
  (s/keys :req-un [::ok ::ts ::reply_to]))

(defmulti outgoing-message :type)
(defmethod outgoing-message "ping" [_]
  (s/keys :req-un [::type ::id]))
(defmethod outgoing-message "message" [_]
  (s/keys :req-un [::type ::channel ::text ::id]))

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
  (yellow "<-" message)
  (ms/put! conn (->json message)))

(defn reply! [conn message reply]
  (put-msg! conn {:id (rand-id) :type "message" :channel (:channel message) :text reply}))

(defn ping-loop [conn]
  (ms/consume #(put-msg! conn {:id % :type "ping"})
              (ms/periodically 10000 rand-id)))

(defn message-handler [conn message]
  {:pre [(explain-or-valid? ::incoming-message message)]}
  (green "->" message)
  (let [reply (cond
                (= "!hello" (:text message)) "hello world"
                :else nil)]
    (when reply (reply! conn message reply))))

(defn slack-loop []
  (d/let-flow [{:keys [url id]} (websocket-connection-info!)
               conn (http/websocket-client url {:headers {:origin "https://api.slack.com/"}})]
    (println "Connected to slack websocket")
    (ping-loop conn)
    (d/loop []
      (d/let-flow [msg (ms/take! conn)
                   message (string->map msg)]
        (d/future (message-handler conn message))
        (d/recur)))))

(defn -main []
  (slack-loop)
  @(promise))
