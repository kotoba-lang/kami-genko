(ns kami.mangaka.genko-app
  "cljs-only genko manga editor (ADR-2607020300). genko-embed.ts(手書き TS ランタイム)を
  廃し、エディタ全体を cljs で実装する第一段: doc モデル(kami.mangaka.genko) + 全ドキュメント
  undo/redo(shitsuke kotoba.editor) + WebGL2 2D 描画(genko-render の draw-list) + 基本ツール
  (select / panel / draw)。UI は reagent。DOM/GPU/入力は cljs host interop。

  今後の parity 対象: fukidashi/tone/text ツール, pentab 筆圧, youshi グリッド, tree drag,
  B2/PDS 永続, AT-URI。まずは『cljs だけで動く genko』の土台をブラウザ実機で確立する。"
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]
            [kami.mangaka.genko :as g]
            [kami.mangaka.genko-render :as gr]
            [kotoba.editor :as ed]))

;; ── editor state (kotoba.editor db: :doc + :undo-stack + :redo-stack) ─────────
(defonce state
  (r/atom {:doc (g/new-doc "Mangaka" {:page-id (g/gen-nid) :youshi-id (g/gen-nid)})
           :undo-stack [] :redo-stack []
           :tool "draw" :selection #{} :draft nil}))

(defn- snap [db] (ed/snapshot db [:doc]))
(defn- active-idx [db] (get-in db [:doc :activePageIdx] 0))
(defn- active-nodes [db] (get-in db [:doc :pages (active-idx db) :nodes] []))
(defn- commit-add! [node]
  (swap! state (fn [db]
                 (-> (ed/push-undo db (snap db) ed/default-history-limit)
                     (update-in [:doc :pages (active-idx db) :nodes] (fnil conj []) node)))))
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

(defn- world->clip [W H [x y]] [(- (* (/ x W) 2.0) 1.0) (- 1.0 (* (/ y H) 2.0))])

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

(defn- draw-op! [gl coll W H {:keys [color] :as o} loop?]
  (let [pts (op->pts o)]
    (when (seq pts)
      (let [flat (clj->js (mapcat #(world->clip W H %) pts))
            arr (js/Float32Array. flat)]
        (.bufferData gl (.-ARRAY_BUFFER gl) arr (.-DYNAMIC_DRAW gl))
        (apply js-invoke gl "uniform4f" coll color)
        (.drawArrays gl (if loop? (.-LINE_LOOP gl) (.-LINE_STRIP gl)) 0 (count pts))))))

(defn render! []
  (when-let [{:keys [gl coll]} @gl-state]
    (let [cv (.-canvas gl) W (.-width cv) H (.-height cv)
          db @state
          youshi (get-in db [:doc :pages (active-idx db) :youshi])
          draws (into (gr/youshi-draws youshi) (gr/draw-list (active-nodes db) (:selection db)))
          draft (:draft db)]
      (.viewport gl 0 0 W H)
      (.clearColor gl 0.94 0.918 0.84 1.0) ; cream
      (.clear gl (.-COLOR_BUFFER_BIT gl))
      (doseq [o draws] (draw-op! gl coll W H o (not= :poly (:op o))))
      (when draft (draw-op! gl coll W H draft (= :rect (:op draft)))))))

;; ── pointer input → tool actions ─────────────────────────────────────────────
(defn- evt-pt [e] (let [t (.-currentTarget e)] [(.-offsetX e) (.-offsetY e)]))

(defn- hit-test [db [px py]]
  (->> (active-nodes db) reverse
       (some (fn [n] (let [d (g/node-data n)]
                       (when (and (:x1 d) (<= (min (:x1 d) (:x2 d)) px (max (:x1 d) (:x2 d)))
                                  (<= (min (:y1 d) (:y2 d)) py (max (:y1 d) (:y2 d))))
                         (g/nid-of n)))))))

(defn- place-text! [[x y]]
  (when-let [t (js/window.prompt "テキスト:" "セリフ")]
    (when (seq t)
      (commit-add! (g/text-node (g/gen-nid) {:x x :y y :text t})))))

(defn- on-down [e]
  (let [[x y] (evt-pt e) db @state]
    (case (:tool db)
      "select" (swap! state assoc :selection (if-let [nid (hit-test db [x y])] #{nid} #{}))
      "draw"   (swap! state assoc :draft {:op :poly :points [[x y]] :color gr/ink :size 2 :_kind "stroke"})
      "panel"  (swap! state assoc :draft {:op :rect :x1 x :y1 y :x2 x :y2 y :color gr/ink :_kind "panel"})
      "fukidashi" (swap! state assoc :draft {:op :ellipse :x1 x :y1 y :x2 x :y2 y :color gr/ink :_kind "fukidashi"})
      "tone"   (swap! state assoc :draft {:op :rect :x1 x :y1 y :x2 x :y2 y :color [0.5 0.5 0.5 0.6] :_kind "tone"})
      "text"   (place-text! [x y])
      nil)
    (render!)))

(defn- on-move [e]
  (when (:draft @state)
    (let [[x y] (evt-pt e)]
      (swap! state update :draft
             (fn [d] (if (= :poly (:op d)) (update d :points conj [x y]) (assoc d :x2 x :y2 y))))
      (render!))))

(defn- on-up [_]
  (when-let [d (:draft @state)]
    (let [id (g/gen-nid)
          node (case (:_kind d)
                 "stroke" (g/wrap-node id "stroke" {:points (mapv (fn [[x y]] {:x x :y y}) (:points d))
                                                    :color (:color d) :size 2})
                 "panel"  (g/panel-node id {:x1 (:x1 d) :y1 (:y1 d) :x2 (:x2 d) :y2 (:y2 d)})
                 "fukidashi" (g/fukidashi-node id {:x1 (:x1 d) :y1 (:y1 d) :x2 (:x2 d) :y2 (:y2 d) :fukiType "oval"})
                 "tone"   (g/tone-node id {:x1 (:x1 d) :y1 (:y1 d) :x2 (:x2 d) :y2 (:y2 d) :tonePattern "dot"})
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
     [:span {:style {:flex "1"}}]
     [:button {:on-click do-undo! :disabled (not (ed/can-undo? db))
               :style {:padding "4px 8px"}} "↶ undo"]
     [:button {:on-click do-redo! :disabled (not (ed/can-redo? db))
               :style {:padding "4px 8px"}} "↷ redo"]
     [:span {:style {:margin-left "8px" :opacity 0.7}}
      (str (count (active-nodes db)) " nodes")]]))

(defn tree []
  (let [db @state
        rows (g/all-nodes (active-nodes db))]
    [:div {:style {:width "200px" :padding "6px" :font "12px sans-serif" :overflow "auto"
                   :border-right "1px solid #ccc" :background "#faf7f0"}}
     [:div {:style {:font-weight "bold" :margin-bottom "4px"}} "Nodes"]
     (for [row rows]
       ^{:key (:nid row)}
       [:div {:on-click #(swap! state assoc :selection #{(:nid row)} :tool "select")
              :style {:padding "2px 4px" :cursor "pointer" :border-radius "4px"
                      :background (if (contains? (:selection db) (:nid row)) "#cfe3ff" "transparent")}}
        (:nm row)])]))

;; ── boot ─────────────────────────────────────────────────────────────────────
(defn ^:export main []
  (let [cv (js/document.getElementById "gl")]
    (init-gl! cv)
    (.addEventListener cv "pointerdown" on-down)
    (.addEventListener cv "pointermove" on-move)
    (js/window.addEventListener "pointerup" on-up)
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
    (rdom/render [toolbar] (js/document.getElementById "bar"))
    (rdom/render [tree] (js/document.getElementById "side"))
    (add-watch state :render (fn [_ _ _ _] (render!)))
    (render!)
    (set! (.-genkoState js/globalThis) state) ; browser 検証フック
    (set! (.-genkoApi js/globalThis)          ; verification helpers
          #js {:doUndo do-undo! :doRedo do-redo!
               :addPanel (fn [x1 y1 x2 y2] (commit-add! (g/panel-node (g/gen-nid) {:x1 x1 :y1 y1 :x2 x2 :y2 y2})))
               :addTone (fn [x1 y1 x2 y2] (commit-add! (g/tone-node (g/gen-nid) {:x1 x1 :y1 y1 :x2 x2 :y2 y2 :tonePattern "dot"})))
               :selectFirst (fn [] (when-let [n (first (active-nodes @state))] (swap! state assoc :selection #{(g/nid-of n)}) true))
               :deleteSelected delete-selected!
               :nodeCount (fn [] (count (active-nodes @state)))
               :nodeTypes (fn [] (clj->js (mapv g/type-of (active-nodes @state))))
               :canUndo (fn [] (boolean (ed/can-undo? @state)))
               :canRedo (fn [] (boolean (ed/can-redo? @state)))})))
