(ns ^:shared geschichte.sync
    "Synching related pub-sub protocols."
    (:require [geschichte.meta :refer [update]]
              [konserve.protocols :refer [IAsyncKeyValueStore -assoc-in -get-in -update-in]]
              [geschichte.debug-channels :as debug]
              [clojure.set :as set]
              [geschichte.platform-data :refer [diff]]
              [geschichte.platform :refer [client-connect!]]
              #+clj [clojure.core.async :as async
                     :refer [<! >! timeout chan alt! go put! filter< map< go-loop]]
              #+cljs [cljs.core.async :as async
                     :refer [<! >! timeout chan put! filter< map<]])
    #+cljs (:require-macros [cljs.core.async.macros :refer (go go-loop alt!)]))


(declare wire)
(defn client-peer
  "Creates a client-side peer only."
  [name store]
  (let [log (atom {})
        in (debug/chan log [name :in])
        out (async/pub in :topic)]
    (atom {:volatile {:log log
                      :chans [in out]
                      :store store}
           :name name
           :meta-sub {}})))


(defn server-peer
  "Constructs a listening peer.
You need to integrate returned :handler to run it."
  [handler store]
  (let [{:keys [new-conns url]} handler
        log (atom {})
        in (debug/chan log [url :in])
        out (async/pub in :topic)
        peer (atom {:volatile (merge handler
                                     {:store store
                                      :log log
                                      :chans [in out]})
                    :name (:url handler)
                    :meta-sub {}})]
    (go-loop [[in out] (<! new-conns)]
             (wire peer [out (async/pub in :topic)])
             (recur (<! new-conns)))
    peer))


;; TODO remove
(defn- new-commits [meta-sub old-meta]
  (set/difference (set (keys (:causal-order meta-sub)))
                  (set (keys (:causal-order old-meta)))))


(defn possible-commits [meta]
  (reduce set/union
          (set (keys (:causal-order meta)))
          (->> meta :branches vals (mapcat (comp vals :indexes))
               (map set))))


(defn- new-commits! [store meta-sub old-meta]
  (->> (map #(go [(not (<!(-get-in store [%]))) %])
            (set/difference (possible-commits meta-sub)
                            (possible-commits old-meta)))
       async/merge
       (filter< first)
       (map< second)
       (async/into #{})))


; by Chouser:
(defn deep-merge-with
  "Like merge-with, but merges maps recursively, applying the given fn
   only when there's a non-map at a particular level.

   (deepmerge + {:a {:b {:c 1 :d {:x 1 :y 2}} :e 3} :f 4}
                {:a {:b {:c 2 :d {:z 9} :z 3} :e 100}})
   -> {:a {:b {:z 3, :c 3, :d {:z 9, :x 1, :y 2}}, :e 103}, :f 4}"
  [f & maps]
  (apply
    (fn m [& maps]
      (if (every? map? maps)
        (apply merge-with m maps)
        (apply f maps)))
    maps))


(defn- filter-subs [subs new old]
  (let [diff (first (diff new old))
        {:keys [branches]} diff
        full-branch-keys (->> subs
                              (filter (fn [[k v]] (empty? v)))
                              first
                              (into #{}))
        partial-branches (reduce (fn [acc k]
                                   (-> acc
                                       (update-in [k :indexes] select-keys (get subs k))
                                       (update-in [k] dissoc :heads)))
                                 (:branches diff)
                                 (set/difference (set (keys branches)) full-branch-keys))
        new-meta (assoc diff
                   :branches (merge partial-branches (select-keys branches full-branch-keys))
                   :id (:id new)
                   :last-update (:last-update new))]
    (if (empty? full-branch-keys)
      (dissoc new-meta :causal-order)
      new-meta)))



#_(filter-subs {"master" #{:politics}}
             {:branches {"master" {:indexes {:economy [1]
                                             :ecology [2]
                                             :politics [2 4]}}}}
             nil
             #_{:branches {"master" {:indexes {:economy [1 4]
                                             :politics [2]}}}})


(defn subscribe
  "Store and propagate subscription requests."
  [peer sub-ch out]
  (let [[bus-in bus-out] (-> @peer :volatile :chans)
        pn (:name @peer)]
    (async/sub bus-out :meta-sub out)
    (go-loop [{:keys [metas] :as s} (<! sub-ch)
              old-subs nil
              prev-ch nil]
             (when s
               (let [new-subs (:meta-sub (swap! peer ;; TODO propagate own subs separately (PUSH)
                                                update-in
                                                [:meta-sub]
                                                (partial deep-merge-with set/union) metas))
                     pubs-ch (chan)
                     pub-pub (async/pub pubs-ch (fn [{{r :id} :meta u :user}] [u r]))]
                 (async/sub bus-out :meta-pub pubs-ch)
                 (when prev-ch (async/close! prev-ch))
                 (doseq [user (keys metas)
                         repo (keys (get metas user))]
                   (let [pub-ch (chan)]
                     (async/sub pub-pub [user repo] pub-ch)
                     (go-loop [{:keys [meta] :as p} (<! pub-ch)
                               old nil]
                              (when p
                                (>! out (assoc p
                                          :meta (filter-subs (get-in metas [user repo])
                                                             meta old)
                                          :user user
                                          :peer pn))
                                (recur (<! pub-ch) meta)))))

                 (when-not (= new-subs old-subs)
                   (>! out {:topic :meta-sub :metas new-subs :peer pn})
                   (let [[new] (diff new-subs old-subs)] ;; pull all new repos
                     (doseq [[user repo] new
                             [id subs] repo]
                       (>! out {:topic :meta-pub-req
                                :depth 1
                                :user user
                                :repo id
                                :peer pn
                                :metas subs}))))

                 (>! out {:topic :meta-subed :metas metas :peer (:peer s)})
                 ;; propagate that the remote has subscribed (for connect)
                 (>! bus-in {:topic :meta-subed :metas metas :peer (:peer s)})

                 (recur (<! sub-ch) new-subs pubs-ch))))))



(defn publish
  "Synchronize metadata publications by fetching missing repository values."
  [peer pub-ch fetched-ch store bus-in out]
  (go-loop [{:keys [user meta] :as p} (<! pub-ch)]
           (when p
             (let [pn (:name @peer)
                   repo (:id meta)
                   nc (<! (new-commits! store meta (<! (-get-in store [user repo]))))]
               (when-not (empty? nc)
                 (>! out {:topic :fetch
                          :ids nc
                          :peer pn})
                 ;; TODO check hash
                 (doseq [[trans-id val] (:values (<! fetched-ch))] ;; TODO timeout
                   (<! (-assoc-in store [trans-id] val))))

               (>! out {:topic :meta-pubed
                        :peer (:peer p)})
               (let [[old-meta up-meta]
                     (<! (-update-in store [user repo] #(if % (update % meta)
                                                            (update meta meta))))]
                 (when (not= old-meta up-meta)
                   (>! bus-in (assoc p :meta up-meta :peer pn))))

               (recur (<! pub-ch))))))


(defn connect
  "Service connection requests."
  [peer conn-ch out]
  (go-loop [{:keys [url] :as c} (<! conn-ch)]
           (when c
             (let [[bus-in bus-out] (:chans (:volatile @peer))
                   pn (:name @peer)
                   log (:log (:volatile @peer))
                   [c-in c-out] (<! (client-connect! url))
                   p (async/pub c-in :topic)
                   subs (:meta-sub @peer)
                   subed-ch (chan)]
               ;; handshake
               (async/sub bus-out :meta-subed subed-ch)
               (<! (wire peer [c-out p]))
               (>! c-out {:topic :meta-sub :metas subs :peer pn})
               ;; wait for ack on backsubscription
               (<! (go-loop [{u :peer :as c} (<! subed-ch)]
                            (when (and c (not= u url))
                                  (recur (<! subed-ch)))))
               (async/close! subed-ch)

               (>! out {:topic :connected
                        :url url
                        :peer (:peer c)})
               (recur (<! conn-ch))))))


(defn fetch
  "Service (remote) fetch requests."
  [peer fetch-ch out]
  (go-loop [{:keys [user repo ids] :as m} (<! fetch-ch)]
           (when m
             (let [pn (:name @peer)
                   store (:store (:volatile @peer))
                   fetched (->> ids
                                (map (fn [id] (go [id (<! (-get-in store [id]))])))
                                async/merge
                                (async/into {})
                                <!)]
               (>! out {:topic :fetched
                        :values fetched
                        :peer (:peer m)})
               (recur (<! fetch-ch))))))

#_(filter-subs {"master" #{:politics}}
             {:branches {"master" {:indexes {:economy [1]
                                             :ecology [2]
                                             :politics [2 4]}}}}
             nil
             #_{:branches {"master" {:indexes {:economy [1 4]
                                             :politics [2]}}}})



(defn publish-requests [peer pub-req-ch out]
  (let [[bus-in bus-out] (-> @peer :volatile :chans)]
    (async/sub bus-out :meta-pub-req out)
    (go-loop [{:keys [user repo metas depth] :as pr} (<! pub-req-ch)]
             (when pr
               (when-let [meta (<! (-get-in (-> @peer :volatile :store) [user repo]))]
                 (>! out {:topic :meta-pub
                          :peer (:name @peer)
                          :user user
                          :meta (filter-subs metas meta nil)})
                 (if (pos? depth)
                   (>! bus-in (assoc pr
                                :depth (min (dec depth) 2)
                                :peer (:name @peer)))))
               (recur (<! pub-req-ch))))))



(defn wire
  "Wire a peer to an output (response) channel and a publication by :topic of the input."
  [peer [out p]]
  (go (let [{:keys [store chans log]} (:volatile @peer)
            name (:name @peer)
            [bus-in bus-out] chans
            ;; unblock on fast publications, newest always superset
            pub-ch (debug/chan log [name :pub] 1)
            pub-req-ch (debug/chan log [name :pub-req])
            conn-ch (debug/chan log [name :conn])
            sub-ch (debug/chan log [name :sub])
            fetch-ch (debug/chan log [name :fetch])
            fetched-ch (debug/chan log [name :fetched])]
        (async/sub p :meta-sub sub-ch)
        (subscribe peer sub-ch out)

        (async/sub p :meta-pub pub-ch)
        (async/sub p :fetched fetched-ch)
        (publish peer pub-ch fetched-ch store bus-in out)

        (async/sub p :meta-pub-req pub-req-ch)
        (publish-requests peer pub-req-ch out)

        (async/sub p :fetch fetch-ch)
        (fetch peer fetch-ch out)

        (async/sub p :connect conn-ch)
        (connect peer conn-ch out)

        (async/sub p nil (debug/chan log [name :unsupported]) false))))