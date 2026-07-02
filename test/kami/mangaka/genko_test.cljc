(ns kami.mangaka.genko-test
  (:require [clojure.test :refer [deftest is testing]]
            [kami.mangaka.genko :as g]
            [kami.mangaka.expression :as expr]))

;; ── sample genko page (panel > {oval-fuki>text, square-fuki>text, tone}) ──────
(def sample
  (let [pn (g/panel-node     "n1" {:x1 0 :y1 0 :x2 100 :y2 100})
        fk (g/fukidashi-node "n2" {:x1 10 :y1 10 :x2 60 :y2 40 :fukiType "oval" :parent "n1"})
        tx (g/text-node      "n3" {:x 20 :y 20 :text "やあ" :parent "n2"})
        sq (g/fukidashi-node "n4" {:x1 10 :y1 60 :x2 90 :y2 90 :fukiType "square" :parent "n1"})
        nt (g/text-node      "n5" {:x 15 :y 65 :text "その頃…" :parent "n4"})
        tn (g/tone-node      "n6" {:x1 0 :y1 0 :x2 100 :y2 100 :tonePattern "grad" :parent "n1"})]
    {:name "S" :pages [(g/page "p1" "Page 1" (g/youshi "y1") [pn fk tx sq nt tn])] :activePageIdx 0}))

(def snodes (:nodes (first (:pages sample))))

(deftest vocab-consistency
  (testing "genko fukidashi 形は expression の bubble 語彙の部分集合"
    (is (every? (comp expr/bubbles keyword) g/fukidashi-types)))
  (testing "tone-pattern → expression 背景トーンは既知の tone"
    (is (every? expr/tones (vals g/tone->expression))))
  (is (= "#e06060" (g/agent-color "shonen")))
  (is (= "#888" (g/agent-color "unknown")))
  (is (= "SH" (g/agent-initials "shonen"))))

(deftest constructors+accessors
  (let [p (g/panel-node "n1" {:x1 1 :y1 2 :x2 3 :y2 4 :parent "root"})]
    (is (= "panel" (g/type-of p)))
    (is (= "n1" (g/nid-of p)))
    (is (= "root" (g/parent-of p)))
    (is (g/self-visible? p))
    (is (= "n1" (get-in p [:data :_nid])) "wrapper と data の _nid が一致")
    (is (= "root" (get-in p [:data :_layer])) "_parent/_layer 同期")))

(deftest all-nodes-derived
  (let [rows (g/all-nodes snodes)
        by (into {} (map (juxt :nid identity)) rows)]
    (is (= "Panel 1" (:nm (by "n1"))))
    (is (= "Fukidashi" (:nm (by "n2"))))
    (is (= "Text: やあ" (:nm (by "n3"))))
    (is (:has-children (by "n1")) "panel は子を持つ")
    (is (:has-children (by "n2")) "oval fuki は text 子を持つ")
    (is (not (:has-children (by "n3"))) "text は子なし")
    (is (= "n1" (:par (by "n2"))))
    (testing ":kind/:idx (可視トグル data-tv 用; strokes-then-overlays の per-kind index)"
      (is (= "o" (:kind (by "n1"))) "panel は overlay")
      (is (= 0 (:idx (by "n1")))) (is (= 1 (:idx (by "n2"))))
      (let [rows (g/all-nodes (into [(g/wrap-node "s0" "stroke" {:points []})] snodes))
            b2 (into {} (map (juxt :nid identity)) rows)]
        (is (= ["s" 0] [(:kind (b2 "s0")) (:idx (b2 "s0"))]) "stroke は kind s, idx 0")
        (is (= ["o" 0] [(:kind (b2 "n1")) (:idx (b2 "n1"))]) "overlay idx は stroke と独立")))))

(deftest tree-and-cycle
  (is (g/would-cycle? snodes "n1" "n3") "n1 を n3 の下 → 循環")
  (is (not (g/would-cycle? snodes "n3" "n1")) "n3 を n1 直下 → 循環しない")
  (testing "node-tree のネスト"
    (let [tree (g/node-tree snodes)
          root (first tree)]
      (is (= "n1" (get-in root [:node :nid])))
      (is (= #{"n2" "n4" "n6"} (set (map (comp :nid :node) (:children root))))))))

(deftest visibility-propagates
  (let [hidden (mapv #(if (= "n1" (g/nid-of %)) (assoc % :visible false) %) snodes)]
    (is (not (g/node-visible? hidden "n3")) "祖先 panel を隠すと子孫 text も不可視"))
  (is (g/node-visible? snodes "n3") "既定は可視"))

(deftest panel-preset
  (testing "\"1\" は基本枠いっぱいの1枚"
    (is (= [g/youshi-inner-bounds] (g/panel-preset-rects "1" g/youshi-inner-bounds 0))))
  (testing "\"2h\" は上下2枚、gutter 分の隙間を挟んで境界に接する"
    (let [{:keys [y1 y2]} g/youshi-inner-bounds
          [top bot] (g/panel-preset-rects "2h" g/youshi-inner-bounds 10)]
      (is (= y1 (:y1 top))) (is (= y2 (:y2 bot)))
      (is (== 10 (- (:y1 bot) (:y2 top))) "隣接パネル間の総隙間 = gutter(両辺 gutter/2 ずつ)")
      (is (= (:x1 g/youshi-inner-bounds) (:x1 top) (:x1 bot)) "外周の辺は寄らない")))
  (testing "\"2x2\" は4枚、未知キーは全面1枚にフォールバック"
    (is (= 4 (count (g/panel-preset-rects "2x2" g/youshi-inner-bounds 10))))
    (is (= 1 (count (g/panel-preset-rects "no-such-preset" g/youshi-inner-bounds 10))))))

(deftest toggle-visible
  (is (not (g/self-visible? (g/find-by-nid (g/toggle-node-visible snodes "n1") "n1"))))
  (testing "2回反転で元に戻る"
    (is (= snodes (g/toggle-node-visible (g/toggle-node-visible snodes "n1") "n1")))))

(deftest reorder+reparent
  (testing "inside = 親付け替え(循環はガード)"
    (let [r (g/reorder-nodes snodes "n3" "n1" "inside")]
      (is (= "n1" (g/parent-of (g/find-by-nid r "n3")))))
    (is (= snodes (g/reorder-nodes snodes "n1" "n3" "inside")) "循環 inside は不変"))
  (testing "before/after = 兄弟へ移動(親は対象の親)"
    (let [r (g/reorder-nodes snodes "n6" "n2" "before")
          ids (mapv g/nid-of r)]
      (is (< (.indexOf ids "n6") (.indexOf ids "n2")))
      (is (= "n1" (g/parent-of (g/find-by-nid r "n6")))))))

(deftest serialize-roundtrip
  (let [d (g/normalize sample)]
    (is (g/valid-doc? d))
    (is (= d (g/read-doc (g/write-doc d))) "JSON round-trip は正規化 doc と一致")
    (is (nil? (g/read-doc (g/write-doc {:name "x" :pages []}))) "pages 空は不正")))

(deftest oplog-replay
  (testing "stroke/addOverlay/reparent/move/toggle/addPage/switch を再構築"
    (let [ops [{:type "stroke"     :data {:stroke {:_nid "s1" :points [{:x 0 :y 0}] :color [0 0 0 1]}}}
               {:type "addOverlay" :data {:overlay {:_nid "o1" :type "panel" :x1 0 :y1 0 :x2 10 :y2 10}}}
               {:type "addOverlay" :data {:overlay {:_nid "o2" :type "fukidashi" :fukiType "oval" :_parent "o1"}}}
               {:type "reparent"   :data {:childNid "s1" :parentNid "o1"}}
               {:type "toggleVis"  :data {:nid "o2"}}
               {:type "moveNode"   :data {:nid "o1" :dx 5 :dy 5}}
               {:type "addPage"    :data {:pageId "p2" :name "P2"}}
               {:type "switchPage" :data {:pageIdx 0}}]
          doc (g/replay-oplog ops {:name "R"})
          p0 (:nodes (first (:pages doc)))]
      (is (= 2 (count (:pages doc))))
      (is (= 0 (:activePageIdx doc)))
      (is (= 3 (count p0)))
      (is (= "o1" (g/parent-of (g/find-by-nid p0 "s1"))) "reparent 反映")
      (is (= 5 (:x1 (g/node-data (g/find-by-nid p0 "o1")))) "moveNode でrect移動")
      (is (not (g/self-visible? (g/find-by-nid p0 "o2"))) "toggleVis 反映")))
  (testing "deleteNode は子の _parent を切り、対象を除去"
    (let [doc (g/replay-oplog [{:type "addOverlay" :data {:overlay {:_nid "o1" :type "panel"}}}
                               {:type "addOverlay" :data {:overlay {:_nid "o2" :type "fukidashi" :_parent "o1"}}}
                               {:type "deleteNode" :data {:nid "o1"}}])
          p0 (:nodes (first (:pages doc)))]
      (is (nil? (g/find-by-nid p0 "o1")))
      (is (= "" (g/parent-of (g/find-by-nid p0 "o2"))) "孤児の親は空に")))
  (testing "undo/redo は最後の stroke を出し入れ"
    (let [base [{:type "stroke" :data {:stroke {:_nid "s1" :points []}}}
                {:type "stroke" :data {:stroke {:_nid "s2" :points []}}}]
          undone (g/replay-oplog (conj base {:type "undo"}))
          redone (g/replay-oplog (conj base {:type "undo"} {:type "redo"}))]
      (is (= ["s1"] (mapv g/nid-of (:nodes (first (:pages undone))))))
      (is (= ["s1" "s2"] (mapv g/nid-of (:nodes (first (:pages redone)))))))))

(deftest storyboard-bridge
  (testing "genko page → storyboard(kami.mangaka.text 形)"
    (let [sb (g/page->storyboard (first (:pages sample)))
          p  (first (:panels sb))]
      (is (= 1 (count (:panels sb))))
      (is (= [0 0 100 100] (:rect p)))
      (is (= :gradient (:tone p)) "grad tone → expression :gradient")
      (is (= "その頃…" (:narration p)) "square fuki 内の text は narration")
      (is (= [{:kind :dialogue :text {:ja "やあ"} :bubble :oval}] (:dialogue p))
          "oval fuki 内の text は dialogue(:oval)")))
  (testing "bridge 出力を analyzeExpression(kami.mangaka.expression) が消費できる"
    (let [P (expr/load-patterns)
          sb (g/page->storyboard (first (:pages sample)))
          enr (expr/analyze-page P {} sb)
          d0 (first (:dialogue (first (:panels enr))))]
      (is (contains? d0 :weight))
      (is (contains? d0 :scale))
      (is (= :oval (:bubble d0))))))
