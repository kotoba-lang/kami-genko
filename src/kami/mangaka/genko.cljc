(ns kami.mangaka.genko
  "原稿 (genko) manga-editor DOCUMENT MODEL + pure logic — cljc SSoT
  (ADR-2607020200). genko-embed.ts (kami-engine-sdk の WebGPU pentab エディタ) が
  inline JS に持っていた doc/page/node モデル・node-tree ops・oplog(event-sourcing)・
  serialize を、忠実に純 cljc へ切り出したもの。

  仕様の忠実点(genko-embed.ts と一致):
  - doc = {:name :pages [page …] :activePageIdx …(:docId :convoId 省略可)}。JSON の
    キー名 (camelCase / 先頭 `_`) を verbatim keyword で保持し round-trip する
    (:activePageIdx :_nid :fukiType :x1 …)。
  - page = {:id :name :youshi {:id :type :visible} :nodes [wrapper …]}。
  - node wrapper = {:id :type :visible :data {…}}; :data は runtime payload で
    :_nid(=id) :_visible(=visible) :_parent(親の nid, \"\"=root) :_layer(旧別名) を持つ。
  - 親子は :_parent 文字列ポインタ、順序は :nodes ベクタの並び。可視判定は `!==false`
    (欠損=可視) を再現。tone と fukidashi は同一生成リテラル由来で相互のフィールドを持つ。
  - text node は 3 スキーム (size/dir/float-color, fontSize/vertical/fontFamily,
    fontSize/font/hex-color) が併存 — 移植でも rename せず共存させる。

  WebGPU 描画・DOM・B2/PDS I/O は host(TS/Svelte ランタイム, langgraph host-fn) に残す。
  純データ/純関数のみ — babashka-safe / JVM・cljs・WASM 可搬。tone/fukidashi は
  kami.mangaka.expression の語彙を共有し、`page->storyboard` で kami.mangaka.text /
  analyzeExpression へ橋渡しする。Sibling of kami-mangaka-{text,expression}-clj。"
  (:require [clojure.string :as str]
            #?(:clj [clojure.data.json :as json])))

;; ---------------------------------------------------------------------------
;; Vocabulary (genko-embed.ts と一致)
;; ---------------------------------------------------------------------------

(def node-types
  #{"stroke" "panel" "tone" "fukidashi" "text" "prompt" "ai-image" "ai-desc"
    "group" "link" "layer"})

(def fukidashi-types  #{"oval" "jagged" "cloud" "square" "wavy"})   ; = expression bubbles ∩
(def fukidashi-tails  #{"bottom" "left" "right" "top" "none"})
(def tone-patterns    #{"dot" "line" "cross" "grad"})
(def tools            #{"draw" "select" "panel" "tone" "fukidashi" "text"})
(def brush-types      #{"fine" "pen" "marker" "brush" "flat" "eraser"})
(def youshi-types     #{"b4manga" "b4koma" "none"})

;; youshi(原稿用紙) の固定ジオメトリ(world px; portrait B4 比率)。外=裁ち落とし(tachikiri)、
;; 内=基本枠(kihonwaku) — genko-render の youshi-draws とパネルプリセット生成が共有する SSoT。
(def youshi-outer-bounds {:x1 250 :y1 20 :x2 750 :y2 700})
(def youshi-inner-bounds {:x1 285 :y1 70 :x2 715 :y2 650})

;; コマ割りプリセット: 基本枠を 0..1 の正規化 [x1 y1 x2 y2] 矩形の列に分割する語彙。
;; cljs に Ratio 型が無いため分数リテラル(1/2 等)は使わず double 演算で表す。
(def ^:private half 0.5)
(def ^:private third (/ 1.0 3))
(def panel-presets
  {"1"   [[0 0 1 1]]
   "2h"  [[0 0 1 half] [0 half 1 1]]
   "2v"  [[0 0 half 1] [half 0 1 1]]
   "3h"  [[0 0 1 third] [0 third 1 (* 2 third)] [0 (* 2 third) 1 1]]
   "2x2" [[0 0 half half] [half 0 1 half] [0 half half 1] [half half 1 1]]})

(def agent-colors
  {"shonen" "#e06060" "shojo" "#e060c0" "seinen" "#6060e0" "yonkoma" "#60c060"
   "mecha" "#6090e0" "horror" "#904090" "background" "#609060" "genga" "#c07020"
   "director" "#c0a020" "" "#888"})

(defn agent-color [a] (get agent-colors a (agent-colors "")))
(defn agent-initials [a] (let [s (str/upper-case (subs (str a) 0 (min 2 (count (str a)))))]
                           (if (str/blank? s) "" s)))

;; genko tone-pattern → kami.mangaka.expression の背景トーン語彙
(def tone->expression {"dot" :dot "line" :hatching "cross" :hatching "grad" :gradient})

;; ---------------------------------------------------------------------------
;; ids (純: 明示 id を渡す。host 用の非純 gen-* は reader-conditional)
;; ---------------------------------------------------------------------------

#?(:clj  (def ^:private nid-counter (atom 0)))
#?(:clj  (defn gen-nid [] (str "n" (swap! nid-counter inc)))
   :cljs (defn gen-nid [] (str "n" (.toString (js/Math.floor (* (js/Math.random) 1e9)) 36))))

;; ---------------------------------------------------------------------------
;; Constructors (serialized wrapper 形。id は呼び手が渡す = 純粋)
;; ---------------------------------------------------------------------------

(defn youshi
  ([id] (youshi id "b4manga" true))
  ([id type visible] {:id id :type type :visible visible}))

(defn page
  ([id name youshi] (page id name youshi []))
  ([id name youshi nodes] {:id id :name name :youshi youshi :nodes (vec nodes)}))

(defn new-doc
  "空の genko doc。`ids` = {:doc :page :youshi} の id を渡す(純)。"
  [name {:keys [doc page-id youshi-id]}]
  (cond-> {:name name
           :pages [(page page-id "Page 1" (youshi youshi-id))]
           :activePageIdx 0}
    doc (assoc :docId doc)))

(defn wrap-node
  "node data を serialized wrapper へ。data には :type / :_nid / :_visible /
  :_parent が同期される(loadPage 相当)。"
  ([id type data] (wrap-node id type data ""))
  ([id type data parent]
   (let [data (merge {:type type :_nid id :_visible true :_parent parent :_layer parent} data)]
     {:id id :type type :visible true :data data})))

(defn panel-node
  [id {:keys [x1 y1 x2 y2 borderW panelName parent unit] :or {borderW 0.8}}]
  (wrap-node id "panel"
             (cond-> {:x1 x1 :y1 y1 :x2 x2 :y2 y2 :borderW borderW}
               panelName (assoc :panelName panelName)
               unit (assoc :_unit unit))
             (or parent "")))

(defn panel-preset-rects
  "コマ割りプリセット(panel-presets のキー)→ `bounds`(基本枠 {:x1 :y1 :x2 :y2})を
  分割した {:x1 :y1 :x2 :y2} の列。隣接パネルの内側の辺だけ `gutter`(コマ間の隙間, px)の
  半分ずつ内側へ寄せる(外周は基本枠に接する)。未知の preset-key は \"1\"(全面 1 コマ)。"
  ([preset-key bounds] (panel-preset-rects preset-key bounds 12))
  ([preset-key {:keys [x1 y1 x2 y2]} gutter]
   (let [fracs (get panel-presets preset-key (get panel-presets "1"))
         w (- x2 x1) h (- y2 y1) half (/ gutter 2)
         edge (fn [origin len frac side]
                (let [px (+ origin (* frac len))]
                  (cond (and (= side :min) (pos? frac)) (+ px half)
                        (and (= side :max) (< frac 1))  (- px half)
                        :else px)))]
     (mapv (fn [[fx1 fy1 fx2 fy2]]
             {:x1 (edge x1 w fx1 :min) :y1 (edge y1 h fy1 :min)
              :x2 (edge x1 w fx2 :max) :y2 (edge y1 h fy2 :max)})
           fracs))))

(defn fukidashi-node
  [id {:keys [x1 y1 x2 y2 fukiType fukiTail parent] :or {fukiType "oval" fukiTail "bottom"}}]
  (wrap-node id "fukidashi"
             {:x1 x1 :y1 y1 :x2 x2 :y2 y2 :fukiType fukiType :fukiTail fukiTail}
             (or parent "")))

(defn tone-node
  [id {:keys [x1 y1 x2 y2 tonePattern toneDensity toneLPI parent]
       :or {tonePattern "dot" toneDensity "30" toneLPI "32.5"}}]
  (wrap-node id "tone"
             {:x1 x1 :y1 y1 :x2 x2 :y2 y2 :tonePattern tonePattern
              :toneDensity toneDensity :toneLPI toneLPI}
             (or parent "")))

(defn text-node
  "text node (interactive scheme 1: :size :font :dir :color[r g b a])."
  [id {:keys [x y text size font dir color parent]
       :or {size 24 font "sans" dir "vertical" color [0.0 0.0 0.0 1.0]}}]
  (wrap-node id "text"
             {:x x :y y :text text :size size :font font :dir dir :color color}
             (or parent "")))

(defn prompt-node
  [id {:keys [prompt parent]}]
  (assoc-in (wrap-node id "prompt" {:prompt prompt :_agent "director"} (or parent ""))
            [:data :_agent] "director"))

(defn group-node
  [id {:keys [groupName parent]}]
  (wrap-node id "group" {:groupName groupName} (or parent "")))

;; ---------------------------------------------------------------------------
;; Node accessors (wrapper 優先、親/agent は :data から)
;; ---------------------------------------------------------------------------

(defn node-data [n] (:data n))
(defn nid-of  [n] (or (:id n) (get-in n [:data :_nid])))
(defn type-of [n] (or (:type n) (get-in n [:data :type])))
(defn agent-of [n] (or (get-in n [:data :_agent]) ""))
(defn self-visible?
  "この node 自身の可視 (`!==false`)。祖先は見ない。"
  [n] (not (or (false? (:visible n)) (false? (get-in n [:data :_visible])))))
(defn parent-of [n] (or (get-in n [:data :_parent]) (get-in n [:data :_layer]) ""))

(defn- set-parent* [n pid]
  (-> n (assoc-in [:data :_parent] pid) (assoc-in [:data :_layer] pid)))

(defn active-page [doc] (nth (:pages doc) (:activePageIdx doc 0)))

;; ---------------------------------------------------------------------------
;; Pure node-tree functions (operate on a `nodes` vector)
;; ---------------------------------------------------------------------------

(defn find-by-nid [nodes id] (first (filter #(= id (nid-of %)) nodes)))

(defn- node-name [n panel-count]
  (let [d (node-data n)]
    (case (type-of n)
      "panel"    (str "Panel " (or (:panelName d) panel-count))
      "ai-image" (str "AI Image" (when-let [p (:_genPrompt d)] (str " (" (subs p 0 (min 12 (count p))) ")")))
      "ai-desc"  (str "AI Desc"  (when-let [p (:_genPrompt d)] (str " (" (subs p 0 (min 12 (count p))) ")")))
      "prompt"   (str "Prompt: " (subs (str (:prompt d)) 0 (min 16 (count (str (:prompt d))))))
      "text"     (str "Text: " (subs (str (:text d)) 0 (min 8 (count (str (:text d))))))
      "link"     (or (:linkTitle d) (:text d) "Link")
      "group"    (or (:groupName d) "Group")
      "tone"     "Tone"
      "fukidashi" "Fukidashi"
      "stroke"   nil        ; stroke name handled by caller (index-based)
      (type-of n))))

(defn all-nodes
  "genko-embed.ts の allNodes 相当: 派生フィールド付きのフラット list
  [{:gi :nid :par :vis :type :kind :idx :nm :ref :agent :has-children} …]。
  :kind (\"s\"/\"o\") と :idx は strokes-then-overlays 順の per-kind index で、genko の
  live strokes[idx]/overlays[idx] と一致する (可視トグルの data-tv 用)。"
  [nodes]
  (let [pc (atom 0) sc (atom -1) oc (atom -1)
        base (vec (map-indexed
                   (fn [gi n]
                     (let [t (type-of n)
                           stroke? (= t "stroke")
                           kind (if stroke? "s" "o")
                           idx  (if stroke? (swap! sc inc) (swap! oc inc))
                           nm (if stroke?
                                (str "Stroke " (inc gi))
                                (do (when (= t "panel") (swap! pc inc))
                                    (node-name n @pc)))]
                       {:gi gi :nid (nid-of n) :par (parent-of n) :vis (self-visible? n)
                        :type t :kind kind :idx idx :nm nm :ref n :agent (agent-of n)
                        :has-children false}))
                   nodes))
        ids (set (map :nid base))]
    (mapv (fn [row]
            (assoc row :has-children
                   (boolean (some #(and (= (:nid row) (:par %)) (contains? ids (:par %))) base))))
          base)))

(defn would-cycle?
  "child を parent の下に置くと循環するか (parent 側の祖先鎖に child が居るか)。"
  [nodes child-id parent-id]
  (if (str/blank? parent-id)
    false
    (loop [cur parent-id seen #{}]
      (cond
        (= cur child-id) true
        (str/blank? cur) false
        (contains? seen cur) false
        :else (recur (parent-of (find-by-nid nodes cur)) (conj seen cur))))))

(defn node-visible?
  "祖先鎖のどれかが不可視なら false。循環時は true (genko と同じ)。"
  [nodes id]
  (loop [cur id seen #{}]
    (cond
      (str/blank? cur) true
      (contains? seen cur) true
      :else (let [n (find-by-nid nodes cur)]
              (cond (nil? n) true
                    (not (self-visible? n)) false
                    :else (recur (parent-of n) (conj seen cur)))))))

(defn- replace-node [nodes id f]
  (mapv #(if (= id (nid-of %)) (f %) %) nodes))

(defn set-node-parent
  "child を parent-id の子に (循環時は不変)。:_parent/:_layer 両方を書く。"
  [nodes child-id parent-id]
  (if (would-cycle? nodes child-id parent-id)
    nodes
    (replace-node nodes child-id #(set-parent* % parent-id))))

(defn toggle-node-visible
  "id の可視を反転(:visible と :data :_visible 両方に同じ値を書く)。"
  [nodes id]
  (replace-node nodes id (fn [n] (let [v (not (self-visible? n))]
                                    (-> n (assoc :visible v) (assoc-in [:data :_visible] v))))))

(defn- move-vec
  "from-id を to-id の before/after へ移す(親は変えない)。"
  [nodes from-id to-id position]
  (let [src (find-by-nid nodes from-id)
        without (filterv #(not= from-id (nid-of %)) nodes)
        ti (first (keep-indexed #(when (= to-id (nid-of %2)) %1) without))]
    (if (nil? ti)
      nodes
      (let [at (if (= position "after") (inc ti) ti)]
        (vec (concat (subvec without 0 at) [src] (subvec without at)))))))

(defn reorder-nodes
  "genko reorderNode 相当。position ∈ \"before\"/\"after\"/\"inside\"。"
  [nodes from-id to-id position]
  (let [src (find-by-nid nodes from-id) tgt (find-by-nid nodes to-id)]
    (if (or (nil? src) (nil? tgt) (= from-id to-id))
      nodes
      (if (= position "inside")
        (set-node-parent nodes from-id to-id)
        (let [par (parent-of tgt)]
          (if (would-cycle? nodes from-id par)
            nodes
            (-> nodes (set-node-parent from-id par) (move-vec from-id to-id position))))))))

(defn node-tree
  "nodes → 親子ネスト木 [{:node row :children [...]}]。renderTree の背後のデータ。"
  ([nodes] (node-tree (all-nodes nodes) ""))
  ([rows parent-id]
   (->> rows
        (filter #(= parent-id (:par %)))
        (mapv (fn [row] {:node row :children (node-tree rows (:nid row))})))))

;; ---------------------------------------------------------------------------
;; serialize / deserialize
;; ---------------------------------------------------------------------------

(defn valid-doc? [d] (boolean (and (map? d) (seq (:pages d)))))

(defn normalize
  "deserialize の正規化: pages 非空を保証し activePageIdx を 0..n-1 に収める。
  node wrapper の :id/:visible を :data の :_nid/:_visible に同期(loadPage 相当)。"
  [d]
  (when (valid-doc? d)
    (let [n (count (:pages d))
          api (let [i (:activePageIdx d 0)] (if (and (integer? i) (< -1 i n)) i 0))]
      (-> d
          (assoc :activePageIdx api)
          (update :pages
                  (fn [ps]
                    (mapv (fn [pg]
                            (update pg :nodes
                                    (fn [ns]
                                      (mapv (fn [w]
                                              (-> w
                                                  (assoc-in [:data :_nid] (:id w))
                                                  (assoc-in [:data :_visible] (not (false? (:visible w))))))
                                            (or ns [])))))
                          ps)))))))

#?(:clj
   (defn read-doc
     "JSON 文字列(または既に parse 済み map) → 正規化された genko doc(不正なら nil)。"
     [json-or-map]
     (let [d (if (string? json-or-map) (json/read-str json-or-map :key-fn keyword) json-or-map)]
       (normalize d)))
   :cljs
   (defn read-doc [json-or-map]
     (let [d (if (string? json-or-map)
               (js->clj (js/JSON.parse json-or-map) :keywordize-keys true)
               json-or-map)]
       (normalize d))))

#?(:clj
   (defn write-doc [doc] (json/write-str doc))
   :cljs
   (defn write-doc [doc] (js/JSON.stringify (clj->js doc))))

;; ---------------------------------------------------------------------------
;; Oplog (event-sourcing) — record は純(append)、replay は純(doc 再構築)
;; ---------------------------------------------------------------------------

(defn record-op
  "op を oplog に積む(純)。record shape = {:t :type :page :data}。`t` は呼び手が渡す
  (host が現在時刻を注入)。OPLOG_MAX(5000) を超えたら末尾を保持。"
  ([oplog type data page] (record-op oplog type data page 0))
  ([oplog type data page t]
   (let [op {:t t :type type :page page :data (or data {})}
         v (conj (vec oplog) op)]
     (if (> (count v) 5000) (vec (take-last 5000 v)) v))))

(defn- rp-update-nodes [doc f]
  (update-in doc [:pages (:activePageIdx doc) :nodes] (fnil f [])))

(defn- move-data [data dx dy]
  (cond-> data
    (:points data)      (update :points (fn [ps] (mapv #(-> % (update :x + dx) (update :y + dy)) ps)))
    (some? (:x1 data))  (-> (update :x1 + dx) (update :y1 + dy) (update :x2 + dx) (update :y2 + dy))
    (some? (:x data))   (-> (update :x + dx) (update :y + dy))))

(defn replay-oplog
  "genko replayOplog 相当: ops から新しい doc を純粋に再構築する。`base` は
  name/docId の引き継ぎ元と初期 page/youshi id を供給する(省略時 gen-nid)。
  注: aiGenImage/aiGenDesc/scaleNode は op に payload が無く決定的に再現できないため
  genko と同じく no-op。"
  ([ops] (replay-oplog ops {}))
  ([ops {:keys [name docId page-id youshi-id]}]
   (let [fresh {:name (or name "") :docId docId
                :pages [(page (or page-id (gen-nid)) "Page 1" (youshi (or youshi-id (gen-nid))))]
                :activePageIdx 0}]
     (:doc
      (reduce
       (fn [{:keys [doc redo] :as st} op]
         (let [d (:data op) t (:type op)
               nodes-> (fn [f] (assoc st :doc (rp-update-nodes doc f)))]
           (case t
             "stroke"     (if-let [s (:stroke d)]
                            (let [id (or (:_nid s) (gen-nid))]
                              (assoc (nodes-> #(conj % (wrap-node id "stroke" s (or (:_parent s) ""))))
                                     :redo []))
                            st)
             "addOverlay" (if-let [o (:overlay d)]
                            (nodes-> #(conj % (wrap-node (or (:_nid o) (gen-nid)) (:type o) o (or (:_parent o) ""))))
                            st)
             "addGroup"   (if-let [o (:overlay d)]
                            (nodes-> #(conj % (wrap-node (or (:_nid o) (gen-nid)) (or (:type o) "group") o (or (:_parent o) ""))))
                            st)
             "panelPreset" (if-let [ps (:panels d)]
                             (nodes-> #(into % (map (fn [o] (wrap-node (or (:_nid o) (gen-nid)) (or (:type o) "panel") o (or (:_parent o) ""))) ps)))
                             st)
             "deleteNode" (nodes-> (fn [ns]
                                     (->> ns
                                          (mapv #(if (= (:nid d) (parent-of %)) (set-parent* % "") %))
                                          (filterv #(not= (:nid d) (nid-of %))))))
             "moveNode"   (if (and (:nid d) (some? (:dx d)))
                            (nodes-> (fn [ns] (replace-node ns (:nid d) #(update % :data move-data (:dx d) (:dy d)))))
                            st)
             "reparent"   (if (some? (:childNid d))
                            (nodes-> (fn [ns] (replace-node ns (:childNid d) #(set-parent* % (or (:parentNid d) "")))))
                            st)
             "toggleVis"  (nodes-> #(toggle-node-visible % (:nid d)))
             "youshiVis"  (assoc st :doc (update-in doc [:pages (:activePageIdx doc) :youshi :visible] not))
             "youshiType" (if (:type d)
                            (assoc st :doc (assoc-in doc [:pages (:activePageIdx doc) :youshi :type] (:type d)))
                            st)
             "addPage"    (let [pg (page (or (:pageId d) (gen-nid)) (or (:name d) "Page") (youshi (gen-nid)))
                                doc' (-> doc (update :pages conj pg))]
                            (assoc st :doc (assoc doc' :activePageIdx (dec (count (:pages doc'))))))
             "deletePage" (if (and (some? (:pageIdx d)) (> (count (:pages doc)) 1))
                            (let [doc' (update doc :pages (fn [ps] (vec (concat (subvec ps 0 (:pageIdx d)) (subvec ps (inc (:pageIdx d)))))))
                                  api (min (:activePageIdx doc') (dec (count (:pages doc'))))]
                              (assoc st :doc (assoc doc' :activePageIdx api)))
                            st)
             "switchPage" (if (some? (:pageIdx d))
                            (assoc st :doc (assoc doc :activePageIdx (:pageIdx d)))
                            st)
             "undo"       (let [ns (get-in doc [:pages (:activePageIdx doc) :nodes])
                                li (last (keep-indexed #(when (= "stroke" (type-of %2)) %1) ns))]
                            (if li
                              (-> (nodes-> (fn [v] (vec (concat (subvec v 0 li) (subvec v (inc li))))))
                                  (update :redo conj (nth ns li)))
                              st))
             "redo"       (if-let [n (peek (:redo st))]
                            (-> (nodes-> #(conj % n)) (update :redo pop))
                            st)
             ;; aiGenImage / aiGenDesc / scaleNode: op に再現用 payload が無く決定的に
             ;; replay できないため no-op (genko-embed.ts と同じ)。
             st)))
       {:doc fresh :redo []}
       ops)))))

;; ---------------------------------------------------------------------------
;; storyboard bridge — genko page → kami.mangaka.text / analyzeExpression
;; ---------------------------------------------------------------------------

(defn- text->element
  "genko text node → kami.mangaka.text 要素。親が fukidashi ならその形を :bubble に、
  square(ナレーション枠) は :narration に。"
  [nodes n]
  (let [d (node-data n)
        par (find-by-nid nodes (parent-of n))
        fuki (when (= "fukidashi" (type-of par)) (get-in par [:data :fukiType]))
        bub (keyword (or fuki "oval"))]
    (if (= :square bub)
      {:kind :narration :text {:ja (:text d)}}
      {:kind :dialogue :text {:ja (:text d)} :bubble bub})))

(defn page->storyboard
  "genko page の best-effort projection → {:panels [{:rect :tone :dialogue :narration
  :prompt} …]}。panel node ごとに、その子孫の text(fukidashi 経由で :bubble)・tone
  (→ expression 背景トーン)・prompt を集める。kami.mangaka.text/expression が消費できる。"
  [page]
  (let [nodes (:nodes page)
        descendants (fn [pid]
                      (loop [ids #{pid}]
                        (let [more (set (keep #(when (contains? ids (parent-of %)) (nid-of %)) nodes))
                              nx (into ids more)]
                          (if (= nx ids) (disj ids pid) (recur nx)))))
        panel->p (fn [pn]
                   (let [pid (nid-of pn) d (node-data pn)
                         dset (descendants pid)
                         desc (filterv #(contains? dset (nid-of %)) nodes)
                         texts (filterv #(= "text" (type-of %)) desc)
                         tones (filterv #(= "tone" (type-of %)) desc)
                         prompts (filterv #(= "prompt" (type-of %)) desc)
                         els (mapv #(text->element nodes %) texts)
                         narr (some #(when (= :narration (:kind %)) (get-in % [:text :ja])) els)]
                     (cond-> {:rect [(:x1 d) (:y1 d) (:x2 d) (:y2 d)]
                              :dialogue (filterv #(= :dialogue (:kind %)) els)}
                       (seq tones)   (assoc :tone (tone->expression (:tonePattern (node-data (first tones))) :dot))
                       (seq prompts) (assoc :prompt (:prompt (node-data (first prompts))))
                       narr          (assoc :narration narr))))]
    {:panels (mapv panel->p (filterv #(= "panel" (type-of %)) nodes))}))

(defn doc->storyboards
  "全 page を storyboard に射影 [{:panels …} …]。"
  [doc] (mapv page->storyboard (:pages doc)))
