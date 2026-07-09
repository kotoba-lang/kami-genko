(ns kami.mangaka.genko-query-test
  (:require [clojure.test :refer [deftest is testing]]
            [datascript.core :as d]
            [kami.mangaka.genko :as g]
            [kami.mangaka.genko-query :as gq]))

;; ── sample genko doc (2 pages) ───────────────────────────────────────────
;; page p1: panel n1 { fukidashi n2 { text n5 } , prompt n3 (agent "shonen")
;;                     { ai-image n4 (agent "shonen") } }
;; page p2: panel n6 { prompt n7 (agent "director", via g/prompt-node) ,
;;                     fukidashi n8 (square) }
;;
;; The prompt→ai-image parent/child pair on page p1 mirrors the shape
;; app-aozora's manga-chat feature produces (yoro-ui.state.manga-chat's
;; `ai-image-node`: :_genImage/:_genPrompt/:_agent on an "ai-image" node) —
;; here nested directly under its originating prompt node (rather than as a
;; sibling under the same panel, which is what manga-chat actually does) so
;; the sample doc also exercises a real parent/child edge for `children-of`.
(def sample
  (let [n1 (g/panel-node "n1" {:x1 0 :y1 0 :x2 100 :y2 100})
        n2 (g/fukidashi-node "n2" {:x1 10 :y1 10 :x2 60 :y2 40 :fukiType "oval" :parent "n1"})
        n3 (g/wrap-node "n3" "prompt" {:prompt "draw a hero" :_agent "shonen"} "n1")
        n4 (g/wrap-node "n4" "ai-image"
                        {:_genImage "QUJD" :_genPrompt "a shonen hero" :_agent "shonen"}
                        "n3")
        n5 (g/text-node "n5" {:x 5 :y 5 :text "やあ" :parent "n2"})
        p1 (g/page "p1" "Page 1" (g/youshi "y1") [n1 n2 n3 n4 n5])

        n6 (g/panel-node "n6" {:x1 0 :y1 0 :x2 200 :y2 200})
        n7 (g/prompt-node "n7" {:prompt "narrate" :parent "n6"}) ; hardcodes :_agent "director"
        n8 (g/fukidashi-node "n8" {:x1 20 :y1 20 :x2 80 :y2 60 :fukiType "square" :parent "n6"})
        p2 (g/page "p2" "Page 2" (g/youshi "y2") [n6 n7 n8])]
    {:name "Sample" :pages [p1 p2] :activePageIdx 0}))

(def db (gq/doc->db sample))

(deftest doc->db-round-trips-count-and-types
  (testing "8 nodes total across both pages"
    (is (= 8 (d/q '[:find (count ?e) . :where [?e :node/nid _]] db))))
  (testing "per-type counts match the source doc"
    (is (= {"panel" 2 "fukidashi" 2 "prompt" 2 "ai-image" 1 "text" 1}
           (->> (d/q '[:find ?type (count ?e) :where [?e :node/type ?type]] db)
                (into {} (map (fn [[t c]] [t c])))))))
  (testing "every entity carries :node/nid correlating back to the source nid"
    (is (= #{"n1" "n2" "n3" "n4" "n5" "n6" "n7" "n8"}
           (into #{} (map first) (d/q '[:find ?nid :where [_ :node/nid ?nid]] db))))))

(deftest page->db-projects-a-single-page
  (let [pdb (gq/page->db (first (:pages sample)))]
    (is (= 5 (d/q '[:find (count ?e) . :where [?e :node/nid _]] pdb))
        "only p1's 5 nodes, not p2's")
    (is (= #{"n1"} (gq/nodes-of-type pdb "panel")))
    (is (= #{"n4"} (gq/children-of pdb "n3")) "prompt→ai-image edge preserved in the single-page projection")))

(deftest nodes-of-type-query
  (is (= #{"n1" "n6"} (gq/nodes-of-type db "panel")))
  (is (= #{"n2" "n8"} (gq/nodes-of-type db "fukidashi")))
  (is (= #{"n3" "n7"} (gq/nodes-of-type db "prompt")))
  (is (= #{"n4"} (gq/nodes-of-type db "ai-image")))
  (is (= #{} (gq/nodes-of-type db "stroke")) "no stroke nodes in the sample doc"))

(deftest children-of-query
  (testing "panel n1's direct children: the oval-fukidashi and the prompt (not their grandchildren)"
    (is (= #{"n2" "n3"} (gq/children-of db "n1"))))
  (testing "prompt n3's child: the ai-image (the prompt→ai-image edge under test)"
    (is (= #{"n4"} (gq/children-of db "n3"))))
  (testing "fukidashi n2's child: the text node"
    (is (= #{"n5"} (gq/children-of db "n2"))))
  (testing "a leaf node (ai-image n4) has no children"
    (is (= #{} (gq/children-of db "n4"))))
  (testing "an unknown nid has no children"
    (is (= #{} (gq/children-of db "does-not-exist")))))

(deftest nodes-by-agent-query
  (is (= #{"n3" "n4"} (gq/nodes-by-agent db "shonen")))
  (is (= #{"n7"} (gq/nodes-by-agent db "director")))
  (is (= #{} (gq/nodes-by-agent db "seinen")) "no seinen-agent nodes in the sample doc"))

;; ── raw ad-hoc `d/q` smoke test — NOT via any of the convenience fns above,
;; to prove the schema/entities are genuinely queryable on their own terms. ──
(deftest raw-adhoc-query-smoke-test
  (testing "join across :node/page (ref) → :page/id: all node nids on page p2"
    (is (= #{"n6" "n7" "n8"}
           (into #{} (map first)
                 (d/q '[:find ?nid
                        :where [?pg :page/id "p2"]
                               [?e :node/page ?pg]
                               [?e :node/nid ?nid]]
                      db)))))
  (testing "multi-clause join: agent \"shonen\" node nids whose :node/parent-nid is \"n1\" or \"n3\""
    (is (= #{"n3" "n4"}
           (into #{} (map first)
                 (d/q '[:find ?nid
                        :where [?e :node/agent "shonen"]
                               [?e :node/nid ?nid]
                               [?e :node/parent-nid ?p]
                               [(contains? #{"n1" "n3"} ?p)]]
                      db)))))
  (testing "pass-through attribute survives verbatim: ai-image's :_genPrompt → :node/genPrompt"
    (is (= "a shonen hero"
           (d/q '[:find ?v .
                  :where [?e :node/nid "n4"] [?e :node/genPrompt ?v]]
                db))))
  (testing "root node's :node/parent-nid is the empty-string sentinel, and it has no :node/parent ref"
    (is (= "" (d/q '[:find ?p . :where [?e :node/nid "n1"] [?e :node/parent-nid ?p]] db)))
    (is (= #{} (d/q '[:find ?e :where [?e :node/nid "n1"] [?e :node/parent _]] db))))
  (testing "d/entity lookup ref by :node/nid resolves to the right entity"
    (is (= "panel" (:node/type (d/entity db [:node/nid "n1"]))))))
