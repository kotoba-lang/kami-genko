(ns kotobase.client-request-test
  "Locks the wire envelope the client sends to kotobase.net: URL, method,
  headers (CACAO / x-kotoba-did), and JSON body for q/datoms/pull/transact.
  A fake fetch captures the request; no network. Complements cacao-test (which
  proves the signature) by pinning the request shape the edge dispatches on."
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [clojure.string :as str]
            ["@noble/curves/ed25519.js" :refer [ed25519]]
            [kotobase.cid :as cid]
            [kotobase.client :as kc]))

(def seed (js/Uint8Array.from (clj->js (range 32))))
(def op-did (cid/did-key-from-ed25519-pub (.getPublicKey ed25519 seed)))

;; fake fetch returning a canned 200 JSON; records the last (url, opts).
(defn- capturing-fetch [sink]
  (fn [url opts]
    (reset! sink {:url url :opts (or opts #js {})})
    (js/Promise.resolve
     #js {:ok true :status 200
          :text (fn [] (js/Promise.resolve "{\"ok\":true,\"datoms\":[]}"))})))

(defn- body-of [sink]
  (js->clj (js/JSON.parse (or (some-> @sink :opts .-body) "{}"))
           :keywordize-keys true))
(defn- header-of [sink k] (aget (.-headers (:opts @sink)) k))
(defn- url-of [sink] (:url @sink))

(def endpoint "https://kotobase.net")

(deftest transact-request-envelope
  (async done
    (let [sink (atom nil)
          c (kc/make-client {:endpoint endpoint :secret-key seed :operator-did op-did
                             :fetch-fn (capturing-fetch sink)})]
      (-> (kc/transact c "yoro-social" "[{:db/id -1 :yoro.post/uri \"at://x\"}]")
          (.then (fn [_]
                   (let [b (body-of sink)]
                     (is (= (str endpoint "/xrpc/ai.gftd.apps.kotobase.datomic.transact") (url-of sink)))
                     (is (= "POST" (.-method (:opts @sink))))
                     (is (= "yoro-social" (:db_name b)))
                     (is (= "[{:db/id -1 :yoro.post/uri \"at://x\"}]" (:tx_edn b)))
                     (is (string? (:cacao_b64 b)) "write carries a CACAO in the body")
                     (is (str/starts-with? (header-of sink "authorization") "CACAO "))
                     (is (= (:did c) (header-of sink "x-kotoba-did")))
                     (done))))))))

(deftest q-and-pull-request-envelopes
  (async done
    (let [sink (atom nil)
          c (kc/make-client {:endpoint endpoint :did op-did :operator-did op-did
                             :public-reads? true :fetch-fn (capturing-fetch sink)})]
      (-> (kc/q c "yoro-social" "{:find [?u] :where [[?e :yoro.post/uri ?u]]}")
          (.then (fn [_]
                   (let [b (body-of sink)]
                     (is (str/ends-with? (url-of sink) "datomic.q"))
                     (is (= "{:find [?u] :where [[?e :yoro.post/uri ?u]]}" (:query_edn b)))
                     (is (= (cid/canonical-graph op-did "yoro-social") (:graph b))))))
          (.then (fn [_] (kc/pull c "yoro-social" "123" "[:db/id :yoro.post/text]")))
          (.then (fn [_]
                   (let [b (body-of sink)]
                     (is (str/ends-with? (url-of sink) "datomic.pull"))
                     (is (= "123" (:entity b)))
                     (is (= "[:db/id :yoro.post/text]" (:pattern_edn b)))
                     (done))))))))

(deftest transact-requires-write-client
  ;; a public/read-only client (no :secret-key) must refuse to transact.
  (let [c (kc/make-client {:endpoint endpoint :did op-did :operator-did op-did :public-reads? true})]
    (is (thrown? js/Error (kc/transact c "yoro-social" "[]")))))
