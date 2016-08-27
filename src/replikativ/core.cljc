(ns replikativ.core
  "Replication related pub-sub protocols."
  (:require [replikativ.crdt.materialize :refer [key->crdt get-crdt]]
            [replikativ.environ :refer [*id-fn*]]
            [replikativ.protocols :refer [-downstream -handshake]]
            [kabel.peer :refer [drain]]
            [konserve.core :as k]
            [kabel.platform-log :refer [debug info warn error]]
            [clojure.set :as set]
            [clojure.data :refer [diff]]
            #?(:clj [full.async :refer [<? <<? <?? go-try go-loop-try alt?]])
            #?(:clj [full.lab :refer [go-for go-loop-super go-super]])
            #?(:clj [clojure.core.async :as async
                     :refer [>! timeout chan put! pub sub unsub close!]]
               :cljs [cljs.core.async :as async
                      :refer [>! timeout chan put! pub sub unsub close!]]))
  #?(:cljs (:require-macros [cljs.core.async.macros :refer (go go-loop alt!)]
                            [full.async :refer [<<? <? go-try go-loop-try go-loop-super alt?]]
                            [full.lab :refer [go-for go-super go-loop-super]])))


(defn- initial-handshake [cold-store mem-store identities out]
  (go-for [[user crdts] identities
           id crdts
           :let [{:keys [crdt state]} (<? (get-crdt cold-store mem-store [user id]))]
           :when state
           :let [state (-handshake state)]]
          (do
            (debug "sending handshake" [user id] state)
            (>! out {:user user
                     :crdt-id id
                     :type :pub/downstream
                     :id (*id-fn*)
                     :downstream {:crdt crdt
                                  :method :handshake
                                  :op state}}))))


(defn- publish-out
  "Reply to publications by sending an update value filtered to subscription."
  [cold-store mem-store pub-ch out identities remote-pn]
  (go-super
   (debug "pub-out handshake:" identities)
   (<<? (initial-handshake cold-store mem-store identities out))

   (debug "starting to publish ops")
   (go-loop-super [{:keys [downstream id user crdt-id] :as p} (<? pub-ch)]
                  (if-not p
                    (info "publication-out ended for " identities)
                    (do
                      (when (get-in identities [user crdt-id])
                        (info "publication-out: sending " (:id p) "to" remote-pn)
                        (>! out p))
                      (recur (<? pub-ch)))))))


(defn subscribe-out [remote-pn remote-subs sub-out-ch out extend?]
  (go-loop-super [{:keys [identities] :as s} (<? sub-out-ch)
                  old-subs nil]
                 (when s
                   (let [new-subs (if extend? identities
                                      (let [[_ _ common-subs] (diff identities remote-subs)]
                                        (or common-subs {})))]
                     (when-not (= new-subs old-subs)
                       (debug remote-pn "subscribing to " new-subs)
                       (>! out (assoc s :identities new-subs)))
                     (recur (<? sub-out-ch) new-subs)))))

(defn subscribe-in
  "Adjust publication stream and propagate subscription requests."
  [peer sub-ch out]
  (let [{:keys [chans log mem-store cold-store]} (-> @peer :volatile)
        [bus-in bus-out] chans
        pn (:id @peer)
        sub-out-ch (chan)]
    (go-loop-super [{:keys [identities id extend?] :as s} (<? sub-ch)
                    old-identities nil
                    old-pub-ch nil
                    old-sub-ch nil]
                   (if s
                     (if (= old-identities identities)
                       (do (info "redundant subscription: " identities)
                           (>! out {:type :sub/identities-ack :id id})
                           (recur (<? sub-ch) old-identities old-pub-ch old-sub-ch))
                       (let [[old-subs new-subs]
                             (<? (k/update-in cold-store
                                              [:peer-config :sub :subscriptions]
                                              ;; TODO filter here
                                              (fn [old] (merge-with set/union old identities))))

                             remote-pn (:sender s)
                             pub-ch (chan)
                             sub-out-ch (chan)
                             extend-me? (true? (<? (k/get-in cold-store [:peer-config :sub :extend?])))]
                         (info pn "subscribe: starting subscription " id " from " remote-pn)
                         (debug pn "subscribe: subscriptions " identities)

                         (when old-pub-ch
                           (unsub bus-out :pub/downstream old-pub-ch)
                           (close! old-pub-ch))
                         (sub bus-out :pub/downstream pub-ch)
                         (publish-out cold-store mem-store pub-ch out identities remote-pn)

                         (when old-sub-ch
                           (unsub bus-out :sub/identities old-sub-ch)
                           (close! old-sub-ch))
                         (sub bus-out :sub/identities sub-out-ch)
                         (subscribe-out remote-pn identities sub-out-ch out extend?)

                         (let [msg {:type :sub/identities
                                    :identities new-subs
                                    :id id
                                    :extend? extend-me?}]
                           (when (= new-subs old-subs)
                             (debug "ensure back-subscription")
                             (>! sub-out-ch msg))
                           (when (not (= new-subs old-subs))
                             (debug "notify all peers of changed subscription")
                             (alt? [[bus-in msg]]
                                   :wrote

                                   (timeout (* 60 1000))
                                   ;; TODO disconnect peer
                                   (throw (ex-info "bus-in was blocked for a long time. Peer broken."
                                                   {:type :bus-in-block
                                                    :failed-put msg
                                                    :was-blocked-by (<? bus-in)})))))

                         (>! out {:type :sub/identities-ack :id id})
                         (info pn "subscribe: finishing " id)

                         (recur (<? sub-ch) identities pub-ch sub-out-ch)))
                     (do (info "subscribe: closing old-pub-ch")
                         (unsub bus-out :pub/downstream old-pub-ch)
                         (unsub bus-out :sub/identities out)
                         (when old-pub-ch (close! old-pub-ch)))))))


(defn commit-pub [cold-store mem-store [user crdt-id] pub]
  (go-try
   ;; ensure that we have a copy in memory! and don't append something before
   ;; we can update the in memory datastructure
   (<? (get-crdt cold-store mem-store [user crdt-id]))
   (<? (k/append cold-store [user crdt-id :log] pub))
   (<? (k/update-in mem-store [[user crdt-id]]
                    (fn [{:keys [description public state crdt]}]
                      (let [state (or state (key->crdt (:crdt pub)))]
                        {:crdt (or crdt (:crdt pub))
                         :description (or description
                                          (:description pub))
                         :public (or (:public pub) public false)
                         :state (-downstream state (:op pub))}))))
   #_(let [[old new]
         (<? (k/update-in mem-store [[user crdt-id]]
                          (fn [{:keys [description public state crdt]}]
                            (let [state (or state (key->crdt (:crdt pub)))]
                              {:crdt (or crdt (:crdt pub))
                               :description (or description
                                                (:description pub))
                               :public (or (:public pub) public false)
                               :state (-downstream state (:op pub))}))))]
     (when-not (= old new)
       (<? (k/append cold-store [user crdt-id :log] pub)))
     [old new])))


(defn publish-in
  "Synchronize downstream publications."
  [peer pub-ch bus-in out]
  (go-loop-super [{:keys [downstream id crdt-id user] :as p} (<? pub-ch)]
                 (when p
                   (let [{pn :id {:keys [mem-store cold-store]} :volatile} @peer]
                     (info pn "publish-in: " (:id p))
                     (let [[old-state new-state] (<? (commit-pub cold-store mem-store
                                                                 [user crdt-id] downstream))]
                       (>! out {:type :pub/downstream-ack
                                :user user
                                :crdt-id crdt-id
                                :id id})
                       (when (not= old-state new-state)
                         (info pn "publish: downstream ops" (:id p))
                         (alt? [[bus-in p]]
                               (debug pn "publish: sent new downstream ops")

                               (timeout (* 60 1000)) ;; TODO make tunable
                               (throw (ex-info "bus-in was blocked for a long time. Peer broken."
                                               {:type :bus-in-block
                                                :failed-put p
                                                :was-blocked-by (<? bus-in)}))))))
                   (recur (<? pub-ch)))))



(defn wire
  "Wire a peer to an output (response) channel and a publication by :type of the input."
  [[peer [in out]]]
  (let [new-in (chan)]
    (go-try (let [p (pub in (fn [{:keys [type]}]
                              (or ({:sub/identities :sub/identities
                                    :pub/downstream :pub/downstream} type)
                                  :unrelated)))
                  {{:keys [store chans log]} :volatile
                   name :name} @peer
                  [bus-in bus-out] chans
                  pub-in-ch (chan)
                  sub-in-ch (chan)]

              (sub p :sub/identities sub-in-ch)
              (subscribe-in peer sub-in-ch out)

              (sub p :pub/downstream pub-in-ch)
              (publish-in peer pub-in-ch bus-in out)

              (sub p :unrelated new-in)))
    [peer [new-in out]]))
