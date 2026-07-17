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

;; youshi(原稿用紙) の旧固定ジオメトリ(world px; bare-rect 世代)。現行の SSoT は
;; 下の youshi-templates(genko-embed.ts の mm 実寸スペック)とそこから導出される
;; youshi-{paper,trim,frame,safe}-bounds。この2定数は互換のため残置(非推奨)。
(def youshi-outer-bounds {:x1 250 :y1 20 :x2 750 :y2 700})
(def youshi-inner-bounds {:x1 285 :y1 70 :x2 715 :y2 650})

;; ── youshi(原稿用紙) 実寸ジオメトリ(SSoT) ──────────────────────────────────
;; 旧: kami.mangaka.genko-render に居た定義群。render IR(canvaskit 依存)を
;; 要求しない純データ/算術なので doc 層のここが正本 — genko-render は同名の
;; 後方互換 alias を維持し、genko-tx 系の消費者(app-aozora の :gh.manga/rect
;; 投影)は render を require せずにここから読む。
;; mm→world px: world 窓 1000×720 に、旧 bare-rect 世代の縦帯 (y 20..700) を保つ
;; uniform scale youshi-px-per-mm = 680/364 ≈ 1.8681、紙面は x=500 に中央寄せ。

(def youshi-templates
  "原稿用紙 template spec (mm)。key = page の :youshi :type (youshi-types)。"
  {"none"    {:draw false}
   "b4manga" {:draw true :w-mm 257.0 :h-mm 364.0
              :trim  {:x1 18.0 :y1 18.0 :x2 239.0 :y2 346.0}
              :outer {:x1 25.0 :y1 27.0 :x2 232.0 :y2 337.0}
              :inner {:x1 53.5 :y1 72.0 :x2 203.5 :y2 292.0}
              :ruler-step 5 :ruler-small 1}
   "b4koma"  {:draw true :w-mm 257.0 :h-mm 364.0
              :trim  {:x1 18.0 :y1 18.0 :x2 239.0 :y2 346.0}
              :outer {:x1 25.0 :y1 27.0 :x2 232.0 :y2 337.0}
              :inner {:x1 53.5 :y1 72.0 :x2 203.5 :y2 292.0}
              :ruler-step 5 :ruler-small 1 :koma 4}})

(def youshi-px-per-mm
  "mm→world px の uniform scale。B4 の紙高 364mm を旧 bare-rect 世代の縦帯
  y 20..700 (680px) にはめる = 680/364 ≈ 1.8681。"
  (/ 680.0 364.0))

(def youshi-origin
  "紙面左上の world 座標 [x y]。x=500 中央寄せ、y=20(旧世代の上端と同じ)。"
  [(- 500.0 (* 257.0 0.5 youshi-px-per-mm)) 20.0])

(defn mm->world
  "原稿用紙 mm 座標(紙左上原点)→ world px [x y]。"
  [mx my]
  (let [[ox oy] youshi-origin]
    [(+ ox (* mx youshi-px-per-mm)) (+ oy (* my youshi-px-per-mm))]))

(defn mm-rect->world
  "原稿用紙 mm 矩形 {:x1 :y1 :x2 :y2} → world px 矩形。"
  [{:keys [x1 y1 x2 y2]}]
  (let [[wx1 wy1] (mm->world x1 y1) [wx2 wy2] (mm->world x2 y2)]
    {:x1 wx1 :y1 wy1 :x2 wx2 :y2 wy2}))

(def youshi-paper-bounds
  "用紙全体 (B4 257×364mm) の world 座標。"
  (mm-rect->world {:x1 0.0 :y1 0.0 :x2 257.0 :y2 364.0}))
(def youshi-trim-bounds
  "裁ち落とし枠 (trim) の world 座標。"
  (mm-rect->world (:trim (youshi-templates "b4manga"))))
(def youshi-frame-bounds
  "基本枠 (印刷領域) の world 座標。"
  (mm-rect->world (:outer (youshi-templates "b4manga"))))
(def youshi-safe-bounds
  "内枠 (150×220mm テキスト安全域) の world 座標。embed の panel preset が分割する
  領域 (getYoushiInnerRect) と同じ — genko-ui の :apply-preset がこれを渡す。"
  (mm-rect->world (:inner (youshi-templates "b4manga"))))

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
(defn parent-of
  "親 nid。genko-embed.ts の `o._parent||o._layer||''` に忠実 — JS では \"\" が falsy
  なので、:_parent が空文字列でも :_layer へフォールバックする (nil だけでなく)。"
  [n]
  (or (not-empty (get-in n [:data :_parent]))
      (not-empty (get-in n [:data :_layer]))
      ""))

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
      ;; JS の `x?…:''` / `a||b` は空文字列も falsy — not-empty で忠実に再現する。
      "panel"    (str "Panel " (let [p (:panelName d)] (if (or (nil? p) (= p "")) panel-count p)))
      "ai-image" (str "AI Image" (when-let [p (not-empty (:_genPrompt d))] (str " (" (subs p 0 (min 12 (count p))) ")")))
      "ai-desc"  (str "AI Desc"  (when-let [p (not-empty (:_genPrompt d))] (str " (" (subs p 0 (min 12 (count p))) ")")))
      "prompt"   (str "Prompt: " (subs (str (:prompt d)) 0 (min 16 (count (str (:prompt d))))))
      "text"     (str "Text: " (subs (str (:text d)) 0 (min 8 (count (str (:text d))))))
      "link"     (or (not-empty (:linkTitle d)) (not-empty (:text d)) "Link")
      "group"    (or (not-empty (:groupName d)) "Group")
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
                           ;; inline JS: strokes.forEach((s,i)=>… 'Stroke '+(i+1)) — stroke の
                           ;; 連番は per-kind index (strokes 配列内の位置)。strokes-then-overlays
                           ;; 順の入力では gi と一致するが、interleave された :nodes でも忠実に。
                           nm (if stroke?
                                (str "Stroke " (inc idx))
                                (do (when (= t "panel") (swap! pc inc))
                                    (node-name n @pc)))]
                       {:gi gi :nid (nid-of n) :par (parent-of n) :vis (self-visible? n)
                        :type t :kind kind :idx idx :nm nm :ref n :agent (agent-of n)
                        :has-children false}))
                   nodes))]
    ;; inline JS: `if(n.par&&nids.has(n.par))…` — par が "" (falsy) の node は誰の子でも
    ;; ないので、nid が "" の node を has-children にしない (par 非空を要求)。
    (mapv (fn [row]
            (assoc row :has-children
                   (boolean (some #(and (seq (:par %)) (= (:nid row) (:par %))) base))))
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

(defn visible-map
  "全 node の実効可視 (node-visible?) を一括計算した {nid bool}。host の render loop
  (genko-embed.ts tessellateAll 等) が毎フレーム per-node で問い合わせる代わりに、
  1 回の変換で参照できる bulk API。"
  [nodes]
  (into {} (map (fn [n] (let [id (nid-of n)] [id (node-visible? nodes id)]))) nodes))

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

(defn- replay-wrap
  "inline replayOplog の忠実 wrapper 化 (stroke/addOverlay): `_nid`(無ければ呼び手が
  生成) と `_visible=true` だけを data に書く — wrap-node と違い :type/:_parent/:_layer
  を data に注入しない (inline JS は payload をそのまま push し rSavePage で包むだけ)。"
  [id type data]
  {:id id :type type :visible true :data (assoc data :_nid id :_visible true)})

(defn- replay-wrap-as-is
  "addGroup/panelPreset の忠実 wrapper 化: inline JS は overlay payload を一切変更せず
  push する (id は `o._nid||''` — nid 生成もしない、visible は `_visible!==false`)。"
  [type-default o]
  {:id (or (:_nid o) "") :type (or (:type o) type-default)
   :visible (not (false? (:_visible o))) :data o})

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
  genko と同じく no-op。

  既知の意図的乖離 (inline replayOplog に対して):
  - node の並び: inline は strokes/overlays を別配列で持ち rSavePage で
    strokes→overlays 順に直列化するが、本実装は op 順の単一 :nodes vector
    (kind 内の相対順は一致し、loadPage 相当の投影後は同一挙動)。
  - deletePage/switchPage: inline の rLoadPage は splice 直後に旧 index へ
    rSavePage する（作業配列を別ページに書き込みうる既知バグ）— これは再現しない。"
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
                              (assoc (nodes-> #(conj % (replay-wrap id "stroke" s)))
                                     :redo []))
                            st)
             "addOverlay" (if-let [o (:overlay d)]
                            (nodes-> #(conj % (replay-wrap (or (:_nid o) (gen-nid)) (:type o) o)))
                            st)
             "addGroup"   (if-let [o (:overlay d)]
                            (nodes-> #(conj % (replay-wrap-as-is "group" o)))
                            st)
             "panelPreset" (if-let [ps (:panels d)]
                             (nodes-> #(into % (map (partial replay-wrap-as-is "panel") ps)))
                             st)
             ;; inline replay の deleteNode は `_parent===dnid` だけを見て `_parent=''` に
             ;; する (:_layer は見ない・触らない — live 側 deleteNode とは非対称だが忠実に)。
             "deleteNode" (nodes-> (fn [ns]
                                     (->> ns
                                          (mapv #(if (= (:nid d) (get-in % [:data :_parent]))
                                                   (assoc-in % [:data :_parent] "") %))
                                          (filterv #(not= (:nid d) (nid-of %))))))
             "moveNode"   (if (and (:nid d) (some? (:dx d)))
                            (nodes-> (fn [ns] (replace-node ns (:nid d) #(update % :data move-data (:dx d) (:dy d)))))
                            st)
             ;; inline replay の reparent は `n._parent=…` のみ (:_layer は書かない —
             ;; live 側 setParent が両方書くのと非対称だが忠実に)。
             "reparent"   (if (some? (:childNid d))
                            (nodes-> (fn [ns] (replace-node ns (:childNid d)
                                                            #(assoc-in % [:data :_parent] (or (:parentNid d) "")))))
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

;; ── panel 矩形のページ正規化(:gh.manga/rect / komawari :panel/rect 座標系) ──

(defn- round4 [n] (/ (Math/round (* (double n) 10000.0)) 10000.0))

(defn page-koma-bounds
  "page の youshi 実寸に基づくコマ割り基準枠 = 内枠 youshi-safe-bounds
  (embed の panel preset が分割する領域)。youshi を描画しない page
  (:type \"none\" / youshi 無し)は nil。"
  [page]
  (when (:draw (get youshi-templates (get-in page [:youshi :type])))
    youshi-safe-bounds))

(defn normalize-rect
  "world px [x1 y1 x2 y2] → 基準枠 frame({:x1 :y1 :x2 :y2})に対する正規化
  [x y w h](基準枠が 0..1。枠外に描かれた矩形は範囲外の値のまま=裁ち落とし
  相当)。1e-4 丸め。座標が欠けるか基準枠が退化していれば nil。"
  [[rx1 ry1 rx2 ry2] frame]
  (let [{fx1 :x1 fy1 :y1 fx2 :x2 fy2 :y2} frame
        fw (when (and (number? fx1) (number? fx2)) (- fx2 fx1))
        fh (when (and (number? fy1) (number? fy2)) (- fy2 fy1))]
    (when (and (number? rx1) (number? ry1) (number? rx2) (number? ry2)
               (number? fw) (pos? fw) (number? fh) (pos? fh))
      [(round4 (/ (- rx1 fx1) fw)) (round4 (/ (- ry1 fy1) fh))
       (round4 (/ (- rx2 rx1) fw)) (round4 (/ (- ry2 ry1) fh))])))

(defn page-panel-rects-normalized
  "page → page->storyboard と同順(panel node の :nodes 順)の正規化 [x y w h] 列。
  基準枠は page-koma-bounds(youshi 内枠)、youshi の無い page は panel 矩形群の
  外接 bbox(絶対位置は落ちるが相対レイアウトは保存)。正規化できない panel は
  nil。:gh.manga/rect / kami.mangaka.komawari の :panel/rect と同じページ座標系
  なので、genko 手描きレイアウトを tx へそのまま持ち出せる。"
  [page]
  (let [rects (mapv (fn [pn] (let [d (node-data pn)]
                               [(:x1 d) (:y1 d) (:x2 d) (:y2 d)]))
                    (filterv #(= "panel" (type-of %)) (:nodes page)))
        frame (or (page-koma-bounds page)
                  (let [nums (filterv #(every? number? %) rects)]
                    (when (seq nums)
                      {:x1 (apply min (map #(nth % 0) nums))
                       :y1 (apply min (map #(nth % 1) nums))
                       :x2 (apply max (map #(nth % 2) nums))
                       :y2 (apply max (map #(nth % 3) nums))})))]
    (mapv (fn [r] (when frame (normalize-rect r frame))) rects)))
