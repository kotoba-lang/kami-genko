(ns kami.mangaka.genko-app
  "cljs-only genko manga editor (ADR-2607020300)。genko-embed.ts(手書き TS ランタイム)を
  廃し、エディタ全体を cljs で実装する: doc モデル(kami.mangaka.genko) + 全ドキュメント
  undo/redo(shitsuke kotoba.editor) + WebGL2 2D 描画(genko-render の draw-list) + ツール
  (select/draw/panel/fukidashi/tone/text、pentab 筆圧・fukiType/fukiTail・tonePattern
  対応)+ コマ割りプリセット + node-tree(drag並べ替え・可視トグル)+ localStorage 永続
  (export/import 併設)+ pan/zoom(freeboard.board 移植)。UI は reagent。DOM/GPU/入力は
  cljs host interop。

  kotoba-server(kotobase.net)への CACAO 自己発行永続(vendored kotobase.{cid,cacao,
  client})を任意同期として搭載。既定の自動保存は引き続き localStorage(信頼性優先、
  ネットワーク往復をキー入力のたびに走らせない)。"
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]
            [kami.mangaka.genko :as g]
            [kami.mangaka.genko-render :as gr]
            [kotoba.editor :as ed]
            ["@noble/curves/ed25519.js" :refer [ed25519]]
            [kotobase.cid :as kcid]
            [kotobase.cacao :as kcacao]
            [kotobase.client :as kc]
            [canvaskit.hit-test :as ckht]))

;; ── editor state (kotoba.editor db: :doc + :undo-stack + :redo-stack) ─────────
(defonce state
  (r/atom {:doc (g/new-doc "Mangaka" {:page-id (g/gen-nid) :youshi-id (g/gen-nid)})
           :undo-stack [] :redo-stack []
           :tool "draw" :selection #{} :draft nil
           :fuki-type "oval" :fuki-tail "bottom" :tone-pattern "dot"
           :viewport gr/default-viewport :pan-from nil :kotoba-status nil}))

;; ── persistence: host-injectable :save/:load port; default = localStorage ─────
;; genko doc は g/write-doc(cljs=JSON.stringify) / g/read-doc(cljs=parse+normalize)。
;; SDK/host が B2/PDS transport を後から差し込める(port は kotoba の host-injection 流儀)。
(def store-key "genko/doc")
(defn- local-save! [doc] (js/localStorage.setItem store-key (g/write-doc doc)))
(defn- local-load []
  (when-let [s (js/localStorage.getItem store-key)] (g/read-doc s)))
(defonce persist (atom {:save local-save! :load local-load}))
(defn save-doc! [] (when-let [f (:save @persist)] (f (:doc @state))))
(defn load-doc! [] (when-let [d (some-> (:load @persist) (apply []))] (swap! state assoc :doc d :selection #{})))
(defn- iso-name [] (str "genko-" (.replace (.toISOString (js/Date.)) #"[:.]" "-") ".json"))
(defn export-json! []
  (let [blob (js/Blob. #js [(g/write-doc (:doc @state))] #js {:type "application/json"})
        url (js/URL.createObjectURL blob)
        a (js/document.createElement "a")]
    (set! (.-href a) url) (set! (.-download a) (iso-name)) (.click a) (js/URL.revokeObjectURL url)))
(defn import-json! []
  (let [inp (js/document.createElement "input")]
    (set! (.-type inp) "file") (set! (.-accept inp) ".json")
    (set! (.-onchange inp)
          (fn [e] (let [f (aget (.. e -target -files) 0) rd (js/FileReader.)]
                    (set! (.-onload rd) (fn [_] (when-let [d (g/read-doc (.-result rd))]
                                                  (swap! state assoc :doc d :selection #{} :undo-stack [] :redo-stack []))))
                    (.readAsText rd f))))
    (.click inp)))

;; ── kotoba-server(kotobase.net) 永続: actor 自身の鍵で CACAO を自己発行 ───────────
;; 秘密鍵(32byte Ed25519 seed)は localStorage に保持(JVM 版の .<actor>/identity.edn
;; に相当するブラウザの等価物; ブラウザはファイルを書けない)。既定の自動保存には
;; 使わず(ネットワーク往復をキー入力のたびに走らせたくない)、明示操作(⛅ボタン)で
;; 呼ぶ任意同期として persist port とは別に用意する。
(def kotoba-endpoint "https://kotobase.net")
(def kotoba-db-name "genko-mangaka")
(def ^:private kotoba-identity-key "genko/kotoba-identity")

(defn- load-or-create-kotoba-secret-key! []
  (if-let [s (js/localStorage.getItem kotoba-identity-key)]
    (kcacao/base64->bytes s)
    (let [seed (.getRandomValues js/crypto (js/Uint8Array. 32))]
      (js/localStorage.setItem kotoba-identity-key (kcacao/bytes->base64 seed))
      seed)))

(defonce kotoba-secret-key (load-or-create-kotoba-secret-key!))
(defonce kotoba-did (kcid/did-key-from-ed25519-pub (.getPublicKey ed25519 kotoba-secret-key)))
(defonce kotoba-client (kc/make-client {:endpoint kotoba-endpoint
                                        :secret-key kotoba-secret-key
                                        :operator-did kotoba-did}))

;; kotoba-server の datomic 面は AT Protocol の at://did/collection/rkey では
;; なく CID(kotobase/db/<did>/<db-name>)で addressing する(実測、ADR注記参照)。
;; ユーザ向けの安定した「この doc の場所」表示としては at:// 形の識別子が
;; 読みやすいので、実ストレージキー(CID graph)とは別に表示用の規約として作る
;; (このURI自体を解決する汎用 AT Protocol リゾルバは想定しない)。
(def kotoba-at-uri (str "at://" kotoba-did "/kotoba.genko.doc/" kotoba-db-name))

;; 実測で判明した kotobase.net の datomic 面の制約(ライブ検証、2026-07-02):
;; - :db/id は整数 tempid でなければならない(文字列 tempid は黙って 0 datom で
;;   no-op になる) — client_request_test.cljc の `-1` 慣習どおり。
;; - entity id は(整数 eid でなく)不透明な CID 文字列 — "最新" は eid 順序でなく
;;   自前の :genko/updated-at(ISO8601 文字列, 辞書順=時系列順)で判定する。
;; - サーバの EDN reader は文字列値中のエスケープ済みダブルクォート(\")を正しく
;;   扱えない(2属性目以降が黙って消える)。JSON を直接埋め込まず base64 で包む。
(defn kotoba-save!
  "現在の doc を kotobase.net の operator db(kotoba-db-name)へ transact する
  (CACAO は kc/transact が呼ぶたびに自己発行、ttl 300s)。Promise を返す。"
  []
  (let [tx (str "[{:db/id -1 :genko/doc-json \"" (js/btoa (g/write-doc (:doc @state)))
               "\" :genko/updated-at \"" (.toISOString (js/Date.)) "\"}]")]
    (kc/transact kotoba-client kotoba-db-name tx)))

(defn kotoba-load!
  "kotoba-db-name の :genko/doc-json(base64)+ :genko/updated-at を持つ全行を取得し、
  updated-at 降順で最初にデコード成功したものを doc として復元する(壊れた/異物の
  行が新しい timestamp を騙っていても無視して次点にフォールバックする)。1件も
  無ければ resolve(nil)。"
  []
  (-> (kc/q kotoba-client kotoba-db-name
           "{:find [?v ?t] :where [[?e :genko/doc-json ?v] [?e :genko/updated-at ?t]]}")
      (.then (fn [^js res]
               (let [rows (js->clj (.-rows_edn res))
                     decoded (mapv (fn [[v t]] [(kc/decode-edn-scalar v) (kc/decode-edn-scalar t)]) rows)
                     newest-first (->> decoded (sort-by second) reverse)]
                 (some (fn [[v _t]]
                         (try (g/read-doc (js/atob v)) (catch :default _ nil)))
                       newest-first))))))

(defn kotoba-sync-save! []
  (swap! state assoc :kotoba-status :saving)
  (-> (kotoba-save!)
      (.then (fn [_] (swap! state assoc :kotoba-status :saved)))
      (.catch (fn [err] (js/console.error "kotoba save failed:" err)
                (swap! state assoc :kotoba-status [:error (.-message err)])))))

(defn kotoba-sync-load! []
  (swap! state assoc :kotoba-status :loading)
  (-> (kotoba-load!)
      (.then (fn [doc]
               (if doc
                 (do (swap! state assoc :doc doc :selection #{} :kotoba-status :loaded))
                 (swap! state assoc :kotoba-status [:error "no doc on server"]))))
      (.catch (fn [err] (js/console.error "kotoba load failed:" err)
                (swap! state assoc :kotoba-status [:error (.-message err)])))))

(defn- snap [db] (ed/snapshot db [:doc]))
(defn- active-idx [db] (get-in db [:doc :activePageIdx] 0))
(defn- active-nodes [db] (get-in db [:doc :pages (active-idx db) :nodes] []))
(defn- commit-add! [node]
  (swap! state (fn [db]
                 (-> (ed/push-undo db (snap db) ed/default-history-limit)
                     (update-in [:doc :pages (active-idx db) :nodes] (fnil conj []) node)))))
(defn- commit-add-many! [nodes]
  (when (seq nodes)
    (swap! state (fn [db]
                   (-> (ed/push-undo db (snap db) ed/default-history-limit)
                       (update-in [:doc :pages (active-idx db) :nodes] (fnil into []) nodes))))))
(defn apply-panel-preset! [preset-key]
  (commit-add-many! (mapv #(g/panel-node (g/gen-nid) %)
                          (g/panel-preset-rects preset-key g/youshi-inner-bounds))))
(defn toggle-vis! [nid]
  (swap! state (fn [db]
                 (-> (ed/push-undo db (snap db) ed/default-history-limit)
                     (update-in [:doc :pages (active-idx db) :nodes] g/toggle-node-visible nid)))))
(defn reorder-node! [from-id to-id position]
  (swap! state (fn [db]
                 (-> (ed/push-undo db (snap db) ed/default-history-limit)
                     (update-in [:doc :pages (active-idx db) :nodes] g/reorder-nodes from-id to-id position)))))
(defn do-undo! [] (swap! state #(ed/undo % snap ed/restore)))
(defn do-redo! [] (swap! state #(ed/redo % snap ed/restore)))

;; ── WebGL2 2D renderer (draws the genko-render draw-list) ────────────────────
(defonce gl-state (atom nil)) ; {:gl :prog :buf :loc}

(def ^:private vs-src
  "#version 300 es\nin vec2 pos;\nvoid main(){gl_Position=vec4(pos,0.0,1.0);}")
(def ^:private fs-src
  "#version 300 es\nprecision mediump float;\nuniform vec4 col;\nout vec4 o;\nvoid main(){o=col;}")

(defn- compile-shader [gl type src]
  (let [s (.createShader gl type)]
    (.shaderSource gl s src) (.compileShader gl s)
    (when-not (.getShaderParameter gl s (.-COMPILE_STATUS gl))
      (js/console.error "shader:" (.getShaderInfoLog gl s)))
    s))

(defn init-gl! [canvas]
  (let [gl (or (.getContext canvas "webgl2") (throw (js/Error. "no webgl2")))
        prog (.createProgram gl)]
    (.attachShader gl prog (compile-shader gl (.-VERTEX_SHADER gl) vs-src))
    (.attachShader gl prog (compile-shader gl (.-FRAGMENT_SHADER gl) fs-src))
    (.linkProgram gl prog) (.useProgram gl prog)
    (let [buf (.createBuffer gl)
          posl (.getAttribLocation gl prog "pos")
          coll (.getUniformLocation gl prog "col")]
      (.bindBuffer gl (.-ARRAY_BUFFER gl) buf)
      (.enableVertexAttribArray gl posl)
      (.vertexAttribPointer gl posl 2 (.-FLOAT gl) false 0 0)
      (reset! gl-state {:gl gl :prog prog :buf buf :coll coll}))))

(defn- world->clip [W H vp [wx wy]]
  (let [[sx sy] (gr/world->screen vp [wx wy])]
    [(- (* (/ sx W) 2.0) 1.0) (- 1.0 (* (/ sy H) 2.0))]))

(defn- ellipse-pts [{:keys [x1 y1 x2 y2]}]
  (let [cx (/ (+ x1 x2) 2.0) cy (/ (+ y1 y2) 2.0)
        rx (/ (js/Math.abs (- x2 x1)) 2.0) ry (/ (js/Math.abs (- y2 y1)) 2.0)]
    (for [i (range 48)] (let [a (* 2 js/Math.PI (/ i 48))]
                          [(+ cx (* rx (js/Math.cos a))) (+ cy (* ry (js/Math.sin a)))]))))

(defn- op->pts [{:keys [op x1 y1 x2 y2 points] :as o}]
  (case op
    :rect [[x1 y1] [x2 y1] [x2 y2] [x1 y2]]
    :poly points
    :ellipse (ellipse-pts o)
    []))

(defn- gl-mode
  "draw op の :mode → GL 描画プリミティブ(:strip/:fan=塗り, :loop/:line=線; 既定 :line)。"
  [gl mode]
  (case mode
    :strip (.-TRIANGLE_STRIP gl)
    :fan   (.-TRIANGLE_FAN gl)
    :loop  (.-LINE_LOOP gl)
    (.-LINE_STRIP gl)))

(defn- draw-op! [gl coll W H vp {:keys [color mode] :as o}]
  (let [pts (op->pts o)]
    (when (seq pts)
      (let [flat (clj->js (mapcat #(world->clip W H vp %) pts))
            arr (js/Float32Array. flat)]
        (.bufferData gl (.-ARRAY_BUFFER gl) arr (.-DYNAMIC_DRAW gl))
        (apply js-invoke gl "uniform4f" coll color)
        (.drawArrays gl (gl-mode gl mode) 0 (count pts))))))

(defn- draft-mode [{:keys [op _kind]}]
  (cond (= op :rect) :loop
        (= op :ellipse) :loop
        (= _kind "stroke") :line
        :else :line))

(defn render! []
  (when-let [{:keys [gl coll]} @gl-state]
    (let [cv (.-canvas gl) W (.-width cv) H (.-height cv)
          db @state
          vp (:viewport db)
          youshi (get-in db [:doc :pages (active-idx db) :youshi])
          draws (into (gr/youshi-draws youshi) (gr/draw-list (active-nodes db) (:selection db)))
          draft (:draft db)]
      (.viewport gl 0 0 W H) ; GL viewport(描画先ピクセル範囲) — 編集用 pan/zoom の vp とは別物
      (.clearColor gl 0.94 0.918 0.84 1.0) ; cream
      (.clear gl (.-COLOR_BUFFER_BIT gl))
      (doseq [o draws] (draw-op! gl coll W H vp o))
      (when draft (draw-op! gl coll W H vp (assoc draft :mode (draft-mode draft)))))))

;; ── pointer input → tool actions ─────────────────────────────────────────────
;; canvas は attribute 解像度(1000x720 = world->screen が仮定する座標系)を CSS で
;; ウィンドウに合わせて伸縮表示する(genko.html の calc())。offsetX/offsetY は CSS px
;; なので、attribute/CSS 比でスケールしないと buffer 解像度 ≠ 表示サイズの窓幅で
;; pan/zoom・描画位置がずれる(evt-pt が currentTarget を捨てて素通ししていた既存の穴)。
(defn- event-screen-xy [e]
  (let [cv (.-currentTarget e) rect (.getBoundingClientRect cv)]
    [(* (.-offsetX e) (/ (.-width cv) (.-width rect)))
     (* (.-offsetY e) (/ (.-height cv) (.-height rect)))]))

;; pentab 筆圧: PointerEvent.pressure(pen=0..1 実測値、mouse/未対応touchは 0 か 0.5 固定)。
;; 0 は「圧力センサ無し」を意味するため 0.6 にフォールバックし、潰れたストロークを防ぐ。
(defn- evt-pt [e]
  (let [[sx sy] (event-screen-xy e) p (.-pressure e)]
    [sx sy (if (pos? p) p 0.6)]))

(defn- hit-test
  "world 点に当たる最前面 node の nid。canvaskit.hit-test へ委譲(ADR-2607071130):
  全 node 同 z なので「後勝ち(subviews 順)」= 旧 reverse+some と同じ順序。"
  [db world-pt]
  (:nid (ckht/hit-test
         (keep (fn [n]
                 (let [{:keys [x1 y1 x2 y2]} (g/node-data n)]
                   (when x1
                     {:frame [(min x1 x2) (min y1 y2) (abs (- x2 x1)) (abs (- y2 y1))]
                      :nid (g/nid-of n)})))
               (active-nodes db))
         world-pt)))

(defn- place-text! [[x y]]
  (when-let [t (js/window.prompt "テキスト:" "セリフ")]
    (when (seq t)
      (commit-add! (g/text-node (g/gen-nid) {:x x :y y :text t})))))

;; select ツールで空白をドラッグしたら pan(freeboard.board と同じ UX)。ドラッグ中の
;; 増分は screen 座標(offsetX/Y)のまま gr/pan-viewport に渡す(pan は screen delta 引数)。
(defn- on-down [e]
  (let [[sx sy p] (evt-pt e) db @state
        [x y] (gr/screen->world (:viewport db) [sx sy])]
    (case (:tool db)
      "select" (if-let [nid (hit-test db [x y])]
                 (swap! state assoc :selection #{nid})
                 (swap! state assoc :selection #{} :pan-from [sx sy]))
      "draw"   (swap! state assoc :draft {:op :poly :points [[x y p]] :color gr/ink :size 4 :_kind "stroke"})
      "panel"  (swap! state assoc :draft {:op :rect :x1 x :y1 y :x2 x :y2 y :color gr/ink :_kind "panel"})
      "fukidashi" (swap! state assoc :draft {:op :ellipse :x1 x :y1 y :x2 x :y2 y :color gr/ink :_kind "fukidashi"})
      "tone"   (swap! state assoc :draft {:op :rect :x1 x :y1 y :x2 x :y2 y :color [0.5 0.5 0.5 0.6] :_kind "tone"})
      "text"   (place-text! [x y])
      nil)
    (render!)))

(defn- on-move [e]
  (let [db @state]
    (cond
      (:pan-from db)
      (let [[sx sy] (event-screen-xy e) [lx ly] (:pan-from db)]
        (swap! state (fn [d] (-> d (update :viewport gr/pan-viewport (- sx lx) (- sy ly))
                                  (assoc :pan-from [sx sy]))))
        (render!))
      (:draft db)
      (let [[sx sy p] (evt-pt e) [x y] (gr/screen->world (:viewport db) [sx sy])]
        (swap! state update :draft
               (fn [d] (if (= :poly (:op d)) (update d :points conj [x y p]) (assoc d :x2 x :y2 y))))
        (render!)))))

(defn- on-wheel [e]
  (.preventDefault e)
  (let [vp (:viewport @state)
        factor (if (neg? (.-deltaY e)) 1.1 (/ 1.0 1.1))
        cursor (event-screen-xy e)]
    (swap! state update :viewport gr/zoom-viewport (* (:zoom vp) factor) cursor)
    (render!)))

(defn- on-up [_]
  (swap! state assoc :pan-from nil)
  (when-let [d (:draft @state)]
    (let [db @state
          id (g/gen-nid)
          node (case (:_kind d)
                 "stroke" (g/wrap-node id "stroke" {:points (mapv (fn [[x y p]] {:x x :y y :p p}) (:points d))
                                                    :color (:color d) :size (:size d)})
                 "panel"  (g/panel-node id {:x1 (:x1 d) :y1 (:y1 d) :x2 (:x2 d) :y2 (:y2 d)})
                 "fukidashi" (g/fukidashi-node id {:x1 (:x1 d) :y1 (:y1 d) :x2 (:x2 d) :y2 (:y2 d)
                                                    :fukiType (:fuki-type db) :fukiTail (:fuki-tail db)})
                 "tone"   (g/tone-node id {:x1 (:x1 d) :y1 (:y1 d) :x2 (:x2 d) :y2 (:y2 d) :tonePattern (:tone-pattern db)})
                 nil)]
      (swap! state assoc :draft nil)
      (when node (commit-add! node)))
    (render!)))

(defn delete-selected! []
  (let [sel (:selection @state)]
    (when (seq sel)
      (swap! state (fn [db]
                     (-> (ed/push-undo db (snap db) ed/default-history-limit)
                         (update-in [:doc :pages (active-idx db) :nodes]
                                    (fn [ns] (filterv #(not (contains? sel (g/nid-of %))) ns)))
                         (assoc :selection #{})))))))

;; ── reagent UI (toolbar + node tree) ─────────────────────────────────────────
(def tools ["select" "draw" "panel" "fukidashi" "tone" "text"])

(defn toolbar []
  (let [db @state]
    [:div {:style {:display "flex" :gap "6px" :padding "6px" :align-items "center"
                   :background "#111" :color "#fff" :font "13px sans-serif"}}
     [:b "原稿 genko (cljs)"]
     (for [t tools]
       ^{:key t}
       [:button {:on-click #(swap! state assoc :tool t)
                 :style {:padding "4px 8px" :border-radius "6px" :cursor "pointer"
                         :background (if (= t (:tool db)) "#e06090" "#333") :color "#fff" :border "none"}}
        t])
     [:select {:value "" :title "コマ割りプリセット"
               :on-change (fn [e]
                            (let [v (.. e -target -value)]
                              (when (seq v) (apply-panel-preset! v))
                              (set! (.. e -target -value) "")))
               :style {:padding "4px" :border-radius "6px"}}
      [:option {:value ""} "コマ割り…"]
      (for [k ["1" "2h" "2v" "3h" "2x2"]]
        ^{:key k} [:option {:value k} k])]
     (when (= "fukidashi" (:tool db))
       [:select {:key "fuki-type" :value (:fuki-type db) :title "吹き出し種別"
                 :on-change #(swap! state assoc :fuki-type (.. % -target -value))
                 :style {:padding "4px" :border-radius "6px"}}
        (for [ft (sort g/fukidashi-types)] ^{:key ft} [:option {:value ft} ft])])
     (when (= "fukidashi" (:tool db))
       [:select {:key "fuki-tail" :value (:fuki-tail db) :title "しっぽの向き"
                 :on-change #(swap! state assoc :fuki-tail (.. % -target -value))
                 :style {:padding "4px" :border-radius "6px"}}
        (for [ft (sort g/fukidashi-tails)] ^{:key ft} [:option {:value ft} ft])])
     (when (= "tone" (:tool db))
       [:select {:key "tone-pattern" :value (:tone-pattern db) :title "トーンパターン"
                 :on-change #(swap! state assoc :tone-pattern (.. % -target -value))
                 :style {:padding "4px" :border-radius "6px"}}
        (for [tp (sort g/tone-patterns)] ^{:key tp} [:option {:value tp} tp])])
     [:span {:style {:flex "1"}}]
     [:button {:on-click export-json! :style {:padding "4px 8px"}} "⇩ export"]
     [:button {:on-click import-json! :style {:padding "4px 8px"}} "⇧ import"]
     [:button {:on-click kotoba-sync-save! :title (str kotoba-at-uri " へ保存(" kotoba-endpoint ")")
               :style {:padding "4px 8px"}} "☁ save"]
     [:button {:on-click kotoba-sync-load! :title (str kotoba-at-uri " から読込")
               :style {:padding "4px 8px"}} "☁ load"]
     (when-let [st (:kotoba-status db)]
       [:span {:style {:opacity 0.8 :color (if (vector? st) "#e06060" "#8fdc8f")}}
        (case st :saving "…" :saved "☁✓" :loading "…" :loaded "☁✓"
              (str "☁✗ " (second st)))])
     [:button {:on-click do-undo! :disabled (not (ed/can-undo? db))
               :style {:padding "4px 8px"}} "↶ undo"]
     [:button {:on-click do-redo! :disabled (not (ed/can-redo? db))
               :style {:padding "4px 8px"}} "↷ redo"]
     [:button {:on-click #(swap! state assoc :viewport gr/default-viewport)
               :title "空白ドラッグ=pan、ホイール=zoom"
               :style {:padding "4px 8px"}} "⌂ view"]
     [:span {:style {:margin-left "8px" :opacity 0.7}}
      (str (count (active-nodes db)) " nodes · " (.toFixed (* 100 (:zoom (:viewport db))) 0) "%")]]))

(defn- drop-position
  "drop 先 row の中心より上なら before、下なら after(HTML5 DnD dragover 座標)。"
  [e]
  (let [rect (.getBoundingClientRect (.-currentTarget e))
        mid (/ (+ (.-top rect) (.-bottom rect)) 2)]
    (if (< (.-clientY e) mid) "before" "after")))

(defn tree []
  (let [db @state
        rows (g/all-nodes (active-nodes db))]
    [:div {:style {:width "200px" :padding "6px" :font "12px sans-serif" :overflow "auto"
                   :border-right "1px solid #ccc" :background "#faf7f0"}}
     [:div {:style {:font-weight "bold" :margin-bottom "4px"}} "Nodes"]
     (for [row rows]
       ^{:key (:nid row)}
       [:div {:draggable true
              :on-drag-start (fn [e] (.setData (.-dataTransfer e) "text/plain" (:nid row)))
              :on-drag-over (fn [e] (.preventDefault e))
              :on-drop (fn [e]
                         (.preventDefault e)
                         (let [from (.getData (.-dataTransfer e) "text/plain")]
                           (when (and (seq from) (not= from (:nid row)))
                             (reorder-node! from (:nid row) (drop-position e)))))
              :on-click #(swap! state assoc :selection #{(:nid row)} :tool "select")
              :style {:padding "2px 4px" :cursor "grab" :border-radius "4px"
                      :display "flex" :align-items "center" :gap "4px"
                      :opacity (if (:vis row) 1 0.4)
                      :background (if (contains? (:selection db) (:nid row)) "#cfe3ff" "transparent")}}
        [:span {:on-click (fn [e] (.stopPropagation e) (toggle-vis! (:nid row)))
                :title "表示/非表示"} (if (:vis row) "👁" "🚫")]
        [:span (:nm row)]])]))

;; ── boot ─────────────────────────────────────────────────────────────────────
(defn ^:export main []
  (let [cv (js/document.getElementById "gl")]
    (init-gl! cv)
    (.addEventListener cv "pointerdown" on-down)
    (.addEventListener cv "pointermove" on-move)
    (js/window.addEventListener "pointerup" on-up)
    (.addEventListener cv "wheel" on-wheel #js {:passive false})
    (js/window.addEventListener "keydown"
      (fn [e]
        (if (or (.-ctrlKey e) (.-metaKey e))
          (case (.-key e)
            "z" (do (.preventDefault e) (if (.-shiftKey e) (do-redo!) (do-undo!)))
            "y" (do (.preventDefault e) (do-redo!))
            nil)
          (case (.-key e)
            ("Delete" "Backspace") (do (.preventDefault e) (delete-selected!))
            nil))))
    (load-doc!) ; localStorage から復元(あれば)
    (rdom/render [toolbar] (js/document.getElementById "bar"))
    (rdom/render [tree] (js/document.getElementById "side"))
    (add-watch state :render (fn [_ _ _ _] (render!)))
    (add-watch state :autosave (fn [_ _ old new] (when (not= (:doc old) (:doc new)) (save-doc!))))
    (render!)
    (set! (.-genkoState js/globalThis) state) ; browser 検証フック
    (set! (.-genkoApi js/globalThis)          ; verification helpers
          #js {:doUndo do-undo! :doRedo do-redo!
               :addPanel (fn [x1 y1 x2 y2] (commit-add! (g/panel-node (g/gen-nid) {:x1 x1 :y1 y1 :x2 x2 :y2 y2})))
               :addTone (fn [x1 y1 x2 y2 & [pattern]]
                          (commit-add! (g/tone-node (g/gen-nid) {:x1 x1 :y1 y1 :x2 x2 :y2 y2 :tonePattern (or pattern "dot")})))
               :addFukidashi (fn [x1 y1 x2 y2 & [fuki-type fuki-tail]]
                               (commit-add! (g/fukidashi-node (g/gen-nid) {:x1 x1 :y1 y1 :x2 x2 :y2 y2
                                                                            :fukiType (or fuki-type "oval")
                                                                            :fukiTail (or fuki-tail "bottom")})))
               :addStroke (fn [pts-js size] (commit-add! (g/wrap-node (g/gen-nid) "stroke"
                                                           {:points (js->clj pts-js :keywordize-keys true)
                                                            :color gr/ink :size (or size 4)})))
               :drawOpModes (fn [] (clj->js (mapv :mode (gr/draw-list (active-nodes @state) #{}))))
               :selectFirst (fn [] (when-let [n (first (active-nodes @state))] (swap! state assoc :selection #{(g/nid-of n)}) true))
               :deleteSelected delete-selected!
               :nodeCount (fn [] (count (active-nodes @state)))
               :nodeTypes (fn [] (clj->js (mapv g/type-of (active-nodes @state))))
               :nodeIds (fn [] (clj->js (mapv g/nid-of (active-nodes @state))))
               :visibleNodeIds (fn [] (let [ns (active-nodes @state)]
                                         (clj->js (filterv #(g/node-visible? ns (g/nid-of %)) (mapv g/nid-of ns)))))
               :applyPreset apply-panel-preset!
               :toggleVis toggle-vis!
               :reorderNode reorder-node!
               :getViewport (fn [] (clj->js (:viewport @state)))
               :setViewport (fn [x y zoom] (swap! state assoc :viewport {:x x :y y :zoom zoom}) (render!))
               :panBy (fn [dx dy] (swap! state update :viewport gr/pan-viewport dx dy) (render!))
               :zoomAt (fn [new-zoom sx sy] (swap! state update :viewport gr/zoom-viewport new-zoom [sx sy]) (render!))
               :worldToScreen (fn [wx wy] (clj->js (gr/world->screen (:viewport @state) [wx wy])))
               :screenToWorld (fn [sx sy] (clj->js (gr/screen->world (:viewport @state) [sx sy])))
               :canUndo (fn [] (boolean (ed/can-undo? @state)))
               :canRedo (fn [] (boolean (ed/can-redo? @state)))
               :kotobaDid (fn [] kotoba-did)
               :kotobaAtUri (fn [] kotoba-at-uri)
               :kotobaGraph (fn [] (kcid/canonical-graph kotoba-did kotoba-db-name))
               :kotobaSave (fn [] (kotoba-save!))
               :kotobaLoad (fn [] (kotoba-load!))
               :kotobaClient (fn [] kotoba-client)
               :kotobaDatoms (fn [] (.then (kc/datoms kotoba-client kotoba-db-name ":eavt") clj->js))
               :kotobaQ (fn [query-edn] (.then (kc/q kotoba-client kotoba-db-name query-edn) clj->js))
               :kotobaTransact (fn [tx-edn] (.then (kc/transact kotoba-client kotoba-db-name tx-edn) clj->js))
               :kotobaPull (fn [entity pattern-edn] (.then (kc/pull kotoba-client kotoba-db-name entity pattern-edn) clj->js))})))
