(ns kami.mangaka.genko-ui
  "Reusable genko editor UI — reagent components + pure state transitions with
  an injectable state adapter (ADR-2607091300 follow-up: aozora native mount).

  genko-app.cljs のスタンドアロン editor から UI をライブラリ化したもの。
  components は module-level ratom を触らず、adapter 経由で state を読み・
  action を発行する。host は ratom(スタンドアロン genko-app)でも re-frame
  (app-aozora /studio/<slug>/genko)でも同じ components を使える。

  ── adapter contract ─────────────────────────────────────────────────────────
  {:db*       IDeref   ; *reactive* deref → editor db (ratom / cursor / reaction /
                       ;  re-frame subscription)。components と GL 再描画 reaction が
                       ;  deref する。
   :dispatch! (fn [action]) ; action = data vector (下記 `step` の語彙)。host は
                       ;  ratom なら (swap! state step action)、re-frame なら
                       ;  (rf/dispatch-sync [:genko/act action]) 等で適用する。
   :sync      (optional) ; クラウド同期ボタン(☁ save/load)を toolbar に出す
              {:save! (fn []) :load! (fn []) :title str}
              ; nil/省略 = 非表示 (aozora は PDS follow-up まで繋がない)。
   :prompt-fn (optional (fn [label default] -> str|nil)) ; text ツールの入力。
              ; 省略時 js/window.prompt}

  ── editor db shape (`initial-db`) ──────────────────────────────────────────
  {:doc <genko doc (kami.mangaka.genko)> :undo-stack [] :redo-stack []
   :tool str :selection #{nid} :draft nil
   :fuki-type str :fuki-tail str :tone-pattern str
   :viewport {:x :y :zoom} :pan-from nil :kotoba-status nil}

  ── actions (`step` = pure (editor-db, action) -> editor-db) ─────────────────
  [:set-tool t] [:select #{nid}] [:select-node nid]
  [:set-fuki-type v] [:set-fuki-tail v] [:set-tone-pattern v]
  [:add-node node] [:add-nodes nodes] [:apply-preset k]
  [:set-youshi-type t] [:toggle-youshi-vis]
  [:toggle-vis nid] [:reorder from-id to-id position]
  [:undo] [:redo] [:delete-selected]
  [:place-text [x y] text]
  [:pointer-down {:screen [sx sy] :pressure p}]
  [:pointer-move {:screen [sx sy] :pressure p}]
  [:pointer-up]
  [:wheel-zoom {:screen [sx sy] :delta-y dy}] [:reset-viewport]
  [:set-doc doc]  ; import/load — selection と undo/redo stack をリセット

  doc を変え永続化が必要な action は `doc-action?` が true(host が autosave を
  スケジュールする判定に使う)。"
  (:require [reagent.core :as r]
            [reagent.ratom :as ratom]
            [kami.mangaka.genko :as g]
            [kami.mangaka.genko-render :as gr]
            [kotoba.editor :as ed]
            [canvaskit.hit-test :as ckht]))

;; ── editor db ────────────────────────────────────────────────────────────────

(defn initial-db
  "editor db 初期値。doc 省略時は空 doc \"Mangaka\"。"
  ([] (initial-db (g/new-doc "Mangaka" {:page-id (g/gen-nid) :youshi-id (g/gen-nid)})))
  ([doc]
   {:doc doc :undo-stack [] :redo-stack []
    :tool "draw" :selection #{} :draft nil
    :fuki-type "oval" :fuki-tail "bottom" :tone-pattern "dot"
    :viewport gr/default-viewport :pan-from nil :kotoba-status nil}))

(defn- snap [db] (ed/snapshot db [:doc]))
(defn active-idx [db] (get-in db [:doc :activePageIdx] 0))
(defn active-nodes [db] (get-in db [:doc :pages (active-idx db) :nodes] []))
(defn- push-undo [db] (ed/push-undo db (snap db) ed/default-history-limit))

(defn- add-nodes* [db nodes]
  (if (seq nodes)
    (-> (push-undo db)
        (update-in [:doc :pages (active-idx db) :nodes] (fnil into []) (vec nodes)))
    db))

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

;; ── pointer transitions (pure) ───────────────────────────────────────────────
;; select ツールで空白をドラッグしたら pan(freeboard.board と同じ UX)。ドラッグ中の
;; 増分は screen 座標のまま gr/pan-viewport に渡す(pan は screen delta 引数)。

(defn- pointer-down [db {:keys [screen pressure]}]
  (let [[sx sy] screen
        p (or pressure 0.6)
        [x y] (gr/screen->world (:viewport db) [sx sy])]
    (case (:tool db)
      "select" (if-let [nid (hit-test db [x y])]
                 (assoc db :selection #{nid})
                 (assoc db :selection #{} :pan-from [sx sy]))
      "draw"   (assoc db :draft {:op :poly :points [[x y p]] :color gr/ink :size 4 :_kind "stroke"})
      "panel"  (assoc db :draft {:op :rect :x1 x :y1 y :x2 x :y2 y :color gr/ink :_kind "panel"})
      "fukidashi" (assoc db :draft {:op :ellipse :x1 x :y1 y :x2 x :y2 y :color gr/ink :_kind "fukidashi"})
      "tone"   (assoc db :draft {:op :rect :x1 x :y1 y :x2 x :y2 y :color [0.5 0.5 0.5 0.6] :_kind "tone"})
      ;; "text" は host が prompt して [:place-text …] を発行する(step は純のまま)
      db)))

(defn- pointer-move [db {:keys [screen pressure]}]
  (let [[sx sy] screen
        p (or pressure 0.6)]
    (cond
      (:pan-from db)
      (let [[lx ly] (:pan-from db)]
        (-> db
            (update :viewport gr/pan-viewport (- sx lx) (- sy ly))
            (assoc :pan-from [sx sy])))
      (:draft db)
      (let [[x y] (gr/screen->world (:viewport db) [sx sy])]
        (update db :draft
                (fn [d] (if (= :poly (:op d)) (update d :points conj [x y p]) (assoc d :x2 x :y2 y)))))
      :else db)))

(defn- draft->node [db {:keys [_kind] :as d}]
  (let [id (g/gen-nid)]
    (case _kind
      "stroke" (g/wrap-node id "stroke" {:points (mapv (fn [[x y p]] {:x x :y y :p p}) (:points d))
                                         :color (:color d) :size (:size d)})
      "panel"  (g/panel-node id {:x1 (:x1 d) :y1 (:y1 d) :x2 (:x2 d) :y2 (:y2 d)})
      "fukidashi" (g/fukidashi-node id {:x1 (:x1 d) :y1 (:y1 d) :x2 (:x2 d) :y2 (:y2 d)
                                        :fukiType (:fuki-type db) :fukiTail (:fuki-tail db)})
      "tone"   (g/tone-node id {:x1 (:x1 d) :y1 (:y1 d) :x2 (:x2 d) :y2 (:y2 d)
                                :tonePattern (:tone-pattern db)})
      nil)))

(defn- pointer-up [db]
  (let [db (assoc db :pan-from nil)]
    (if-let [d (:draft db)]
      (let [node (draft->node db d)
            db (assoc db :draft nil)]
        (if node (add-nodes* db [node]) db))
      db)))

(defn- wheel-zoom [db {:keys [screen delta-y]}]
  (let [vp (:viewport db)
        factor (if (neg? delta-y) 1.1 (/ 1.0 1.1))]
    (update db :viewport gr/zoom-viewport (* (:zoom vp) factor) screen)))

(defn- delete-selected [db]
  (let [sel (:selection db)]
    (if (seq sel)
      (-> (push-undo db)
          (update-in [:doc :pages (active-idx db) :nodes]
                     (fn [ns] (filterv #(not (contains? sel (g/nid-of %))) ns)))
          (assoc :selection #{}))
      db)))

;; ── step: 全 action の pure 適用 ─────────────────────────────────────────────

(defn step
  "editor db に action(data vector)を純適用する。host(ratom swap! / re-frame
  event handler)がこれを呼ぶ — doc の変換は kami.mangaka.genko の関数(SSoT)。"
  [db [op & args :as action]]
  (case op
    :set-tool         (assoc db :tool (first args))
    :select           (assoc db :selection (set (first args)))
    :select-node      (assoc db :selection #{(first args)} :tool "select")
    :set-fuki-type    (assoc db :fuki-type (first args))
    :set-fuki-tail    (assoc db :fuki-tail (first args))
    :set-tone-pattern (assoc db :tone-pattern (first args))
    :add-node         (add-nodes* db [(first args)])
    :add-nodes        (add-nodes* db (first args))
    ;; パネルプリセットが分割するのは原稿用紙の内枠(テキスト安全域)— embed の
    ;; getYoushiInnerRect と同じ(gr/youshi-safe-bounds、mm 由来の world 座標)。
    :apply-preset     (add-nodes* db (mapv #(g/panel-node (g/gen-nid) %)
                                           (g/panel-preset-rects (first args) gr/youshi-safe-bounds)))
    :toggle-vis       (-> (push-undo db)
                          (update-in [:doc :pages (active-idx db) :nodes] g/toggle-node-visible (first args)))
    :reorder          (let [[from to position] args]
                        (-> (push-undo db)
                            (update-in [:doc :pages (active-idx db) :nodes] g/reorder-nodes from to position)))
    :undo             (ed/undo db snap ed/restore)
    :redo             (ed/redo db snap ed/restore)
    :delete-selected  (delete-selected db)
    :place-text       (let [[[x y] text] args]
                        (if (seq text)
                          (add-nodes* db [(g/text-node (g/gen-nid) {:x x :y y :text text})])
                          db))
    ;; 原稿用紙 (youshi): template 切替 / 表示トグル。youshi は page 直下の特別
    ;; ノード({:id :type :visible})で :nodes には入らない — embed の youshiType/
    ;; youshiVis op と同じ語彙。youshi が無い page でも set-youshi-type は
    ;; ノードを作って敷ける(手組み doc の救済)。
    :set-youshi-type  (-> (push-undo db)
                          (update-in [:doc :pages (active-idx db) :youshi]
                                     (fn [y] (g/youshi (or (:id y) (g/gen-nid))
                                                       (first args)
                                                       (not (false? (:visible y)))))))
    :toggle-youshi-vis (-> (push-undo db)
                           (update-in [:doc :pages (active-idx db) :youshi :visible] false?))
    :pointer-down     (pointer-down db (first args))
    :pointer-move     (pointer-move db (first args))
    :pointer-up       (pointer-up db)
    :wheel-zoom       (wheel-zoom db (first args))
    :reset-viewport   (assoc db :viewport gr/default-viewport)
    :set-doc          (assoc db :doc (first args) :selection #{} :draft nil
                             :undo-stack [] :redo-stack [])
    (do (js/console.warn "genko-ui/step: unknown action" (pr-str action)) db)))

(def ^:private doc-ops
  #{:add-node :add-nodes :apply-preset :toggle-vis :reorder
    :set-youshi-type :toggle-youshi-vis
    :undo :redo :delete-selected :place-text :pointer-up :set-doc})

(defn doc-action?
  "doc を変えうる(=永続化が必要な)action か。host の autosave スケジュール判定用。"
  [[op]]
  (contains? doc-ops op))

;; ── keyboard ─────────────────────────────────────────────────────────────────

(defn- editable-target?
  "入力中の element へのキーを editor ショートカットに吸わせない guard。"
  [e]
  (let [t (.-target e)
        tag (some-> t .-tagName)]
    (or (contains? #{"INPUT" "TEXTAREA" "SELECT"} tag)
        (some-> t .-isContentEditable))))

(defn keydown-action
  "keydown event → action vector(または nil)。マッチしたら preventDefault 済み。
  Cmd/Ctrl+Z = undo, Shift+Cmd/Ctrl+Z / Cmd/Ctrl+Y = redo, Delete/Backspace =
  delete-selected。input/textarea 等へのタイプは無視。"
  [e]
  (when-not (editable-target? e)
    (if (or (.-ctrlKey e) (.-metaKey e))
      (case (.-key e)
        "z" (do (.preventDefault e) (if (.-shiftKey e) [:redo] [:undo]))
        "y" (do (.preventDefault e) [:redo])
        nil)
      (case (.-key e)
        ("Delete" "Backspace") (do (.preventDefault e) [:delete-selected])
        nil))))

;; ── JSON export / import ─────────────────────────────────────────────────────

(defn- iso-name [] (str "genko-" (.replace (.toISOString (js/Date.)) #"[:.]" "-") ".json"))

(defn export-json!
  "doc を JSON ファイルとしてダウンロードさせる。"
  [doc]
  (let [blob (js/Blob. #js [(g/write-doc doc)] #js {:type "application/json"})
        url (js/URL.createObjectURL blob)
        a (js/document.createElement "a")]
    (set! (.-href a) url) (set! (.-download a) (iso-name)) (.click a) (js/URL.revokeObjectURL url)))

(defn import-json!
  "file picker で JSON doc を選ばせ、読めたら [:set-doc doc] を発行する。"
  [dispatch!]
  (let [inp (js/document.createElement "input")]
    (set! (.-type inp) "file") (set! (.-accept inp) ".json")
    (set! (.-onchange inp)
          (fn [e] (let [f (aget (.. e -target -files) 0) rd (js/FileReader.)]
                    (set! (.-onload rd) (fn [_] (when-let [d (g/read-doc (.-result rd))]
                                                  (dispatch! [:set-doc d]))))
                    (.readAsText rd f))))
    (.click inp)))

;; ── WebGL2 2D renderer (draws the genko-render draw-list) ────────────────────

(def ^:private vs-src
  "#version 300 es\nin vec2 pos;\nvoid main(){gl_Position=vec4(pos,0.0,1.0);}")
(def ^:private fs-src
  "#version 300 es\nprecision mediump float;\nuniform vec4 col;\nout vec4 o;\nvoid main(){o=col;}")

;; textured-quad program (ai-image node draw-op :image) — 2枚目の shader program。
;; 単色 program(vs-src/fs-src)とは別 program/別 buffer で共存させる(既存の描画は
;; 一切触らない)。頂点は [x y u v] interleave の per-draw dynamic buffer(単色 program の
;; :rect/:poly と同様、node 数だけ毎フレーム bufferData する — 原稿用紙+node 数程度の
;; op 数なら十分軽い。instancing 等は現状の規模ではオーバーエンジニアリング)。
(def ^:private vs-tex-src
  "#version 300 es\nin vec2 pos;\nin vec2 uv;\nout vec2 vUv;\nvoid main(){vUv=uv;gl_Position=vec4(pos,0.0,1.0);}")
(def ^:private fs-tex-src
  "#version 300 es\nprecision mediump float;\nin vec2 vUv;\nuniform sampler2D tex;\nout vec4 o;\nvoid main(){o=texture(tex,vUv);}")

(defn- compile-shader [gl type src]
  (let [s (.createShader gl type)]
    (.shaderSource gl s src) (.compileShader gl s)
    (when-not (.getShaderParameter gl s (.-COMPILE_STATUS gl))
      (js/console.error "shader:" (.getShaderInfoLog gl s)))
    s))

(defn- link-program! [gl vs fs]
  (let [prog (.createProgram gl)]
    (.attachShader gl prog (compile-shader gl (.-VERTEX_SHADER gl) vs))
    (.attachShader gl prog (compile-shader gl (.-FRAGMENT_SHADER gl) fs))
    (.linkProgram gl prog)
    (when-not (.getProgramParameter gl prog (.-LINK_STATUS gl))
      (js/console.error "program link:" (.getProgramInfoLog gl prog)))
    prog))

(defn init-gl!
  "canvas に WebGL2 context + 2つの shader program を用意する: 1) line/fill 用の
  単色 program(既存、panel/stroke/fukidashi/tone/text/prompt/youshi 用)、
  2) ai-image node 用の textured-quad program(:tex-prog 以下)。texture cache
  (js/Map、:image-key → WebGLTexture|:pending)も host state としてここに積む
  (アプリ db ではなくホスト側の描画キャッシュ — db* には一切触れない)。
  → {:gl :prog :buf :coll :tex-prog :tex-buf :tex-posl :tex-uvl :tex-samplerl
     :tex-cache} (render! に渡す)。"
  [canvas]
  (let [gl (or (.getContext canvas "webgl2") (throw (js/Error. "no webgl2")))
        prog (link-program! gl vs-src fs-src)
        tex-prog (link-program! gl vs-tex-src fs-tex-src)]
    (.useProgram gl prog)
    (let [buf (.createBuffer gl)
          posl (.getAttribLocation gl prog "pos")
          coll (.getUniformLocation gl prog "col")
          tex-buf (.createBuffer gl)
          tex-posl (.getAttribLocation gl tex-prog "pos")
          tex-uvl (.getAttribLocation gl tex-prog "uv")
          tex-samplerl (.getUniformLocation gl tex-prog "tex")]
      (.bindBuffer gl (.-ARRAY_BUFFER gl) buf)
      (.enableVertexAttribArray gl posl)
      (.vertexAttribPointer gl posl 2 (.-FLOAT gl) false 0 0)
      {:gl gl :prog prog :buf buf :posl posl :coll coll
       :tex-prog tex-prog :tex-buf tex-buf :tex-posl tex-posl :tex-uvl tex-uvl
       :tex-samplerl tex-samplerl :tex-cache (js/Map.)})))

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
  "draw op の :mode → GL 描画プリミティブ(:strip/:fan=塗り, :loop/:line=線,
  :segments=独立線分群(点2つで1本; 原稿用紙の目盛り/トンボ); 既定 :line)。"
  [gl mode]
  (case mode
    :strip    (.-TRIANGLE_STRIP gl)
    :fan      (.-TRIANGLE_FAN gl)
    :loop     (.-LINE_LOOP gl)
    :segments (.-LINES gl)
    (.-LINE_STRIP gl)))

(defn- draw-op! [gl prog buf posl coll W H vp {:keys [color mode] :as o}]
  (let [pts (op->pts o)]
    (when (seq pts)
      ;; render! は同一フレーム内で単色 op と textured-quad op(:image、別 program/
      ;; 別 buffer)を交互に処理しうるので、program/buffer/attrib state は毎回
      ;; 明示的に(再)バインドする(init-gl! で一度だけ設定 → 以後暗黙に持続、という
      ;; 前提を捨てて自己完結にする。既存の見た目・挙動は不変)。
      (.useProgram gl prog)
      (.bindBuffer gl (.-ARRAY_BUFFER gl) buf)
      (.enableVertexAttribArray gl posl)
      (.vertexAttribPointer gl posl 2 (.-FLOAT gl) false 0 0)
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

;; ── ai-image texture cache / async decode-and-upload ─────────────────────────
;; base64(node の :_genImage)は node ごとに一度きり生成され、以後 mutate されない
;; (app-aozora yoro-ui.state.manga-chat の ai-image-node は :manga-chat/image-success
;; で1回作られるだけ)ので、draw-list 側の `:image-key` = node id をそのまま cache key
;; に使える(genko-render.cljc 参照)。decode は createImageBitmap(非同期)— 完了まで
;; 同じ key の再デコードを起こさないよう cache に :pending を先置きし、完了後は
;; `redraw!`(db を経由しない直接 render! 呼び出し — ホスト側の描画キャッシュが
;; 変わっただけで application state は変わっていないため、app の dispatch/db
;; サイクルを回す必要が無い/回すべきでもない)で1回だけ再描画をトリガする。

(defn- b64->bytes [b64]
  (let [bin (js/atob b64) len (.-length bin) bytes (js/Uint8Array. len)]
    (dotimes [i len] (aset bytes i (.charCodeAt bin i)))
    bytes))

(defn- upload-texture! [gl bitmap]
  (let [tex (.createTexture gl)]
    (.bindTexture gl (.-TEXTURE_2D gl) tex)
    (.texParameteri gl (.-TEXTURE_2D gl) (.-TEXTURE_MIN_FILTER gl) (.-LINEAR gl))
    (.texParameteri gl (.-TEXTURE_2D gl) (.-TEXTURE_MAG_FILTER gl) (.-LINEAR gl))
    (.texParameteri gl (.-TEXTURE_2D gl) (.-TEXTURE_WRAP_S gl) (.-CLAMP_TO_EDGE gl))
    (.texParameteri gl (.-TEXTURE_2D gl) (.-TEXTURE_WRAP_T gl) (.-CLAMP_TO_EDGE gl))
    (.texImage2D gl (.-TEXTURE_2D gl) 0 (.-RGBA gl) (.-RGBA gl) (.-UNSIGNED_BYTE gl) bitmap)
    (.bindTexture gl (.-TEXTURE_2D gl) nil)
    tex))

(defn- ensure-texture!
  "cache(js/Map)に `key` の texture が無ければ、`b64` を非同期 decode→upload して
  cache に積み、完了後 `redraw!` を呼ぶ。既に :pending(decode 中)/texture 済みなら
  何もしない — 同じ node を毎フレーム見ても decode は生涯 1 回だけ。"
  [gl cache key b64 redraw!]
  (when (and (seq b64) (not (.has cache key)))
    (.set cache key :pending)
    (-> (js/Promise.resolve (js/Blob. #js [(b64->bytes b64)] #js {:type "image/png"}))
        (.then (fn [blob] (js/createImageBitmap blob)))
        (.then (fn [bitmap]
                 (.set cache key (upload-texture! gl bitmap))
                 (redraw!)))
        (.catch (fn [err]
                  (js/console.error "kami.mangaka.genko-ui: ai-image texture decode failed"
                                     (pr-str key) err)
                  (.delete cache key))))))

(defn- draw-image-op!
  "`:op :image` draw op → textured quad(TRIANGLE_FAN、既存 :fan 語彙の流用で新規
  primitive 無し)。texture が未だキャッシュに無い/decode 中(:pending)の1フレーム目は
  何も描かない(=空白のまま)のではなく、既存の単色パイプラインで dashed 相当の
  placeholder 枠(prompt node と同じ見た目)を代わりに描く — 位置がすぐ判る方が
  『画像生成中、まだ来てない』を『バグって消えた』と誤読されにくいため。"
  [{:keys [gl tex-prog tex-buf tex-posl tex-uvl tex-samplerl tex-cache prog buf posl coll]}
   W H vp {:keys [x1 y1 x2 y2 image-key image-b64] :as o} redraw!]
  (ensure-texture! gl tex-cache image-key image-b64 redraw!)
  (let [cached (.get tex-cache image-key)]
    (if (and cached (not= :pending cached))
      (let [corners [[x1 y1] [x2 y1] [x2 y2] [x1 y2]]
            uvs [[0 0] [1 0] [1 1] [0 1]]
            flat (clj->js (mapcat (fn [pt [u v]]
                                     (let [[cx cy] (world->clip W H vp pt)]
                                       [cx cy u v]))
                                   corners uvs))
            arr (js/Float32Array. flat)]
        (.useProgram gl tex-prog)
        (.bindBuffer gl (.-ARRAY_BUFFER gl) tex-buf)
        (.bufferData gl (.-ARRAY_BUFFER gl) arr (.-DYNAMIC_DRAW gl))
        (.enableVertexAttribArray gl tex-posl)
        (.vertexAttribPointer gl tex-posl 2 (.-FLOAT gl) false 16 0)
        (.enableVertexAttribArray gl tex-uvl)
        (.vertexAttribPointer gl tex-uvl 2 (.-FLOAT gl) false 16 8)
        (.activeTexture gl (.-TEXTURE0 gl))
        (.bindTexture gl (.-TEXTURE_2D gl) cached)
        (.uniform1i gl tex-samplerl 0)
        (.drawArrays gl (.-TRIANGLE_FAN gl) 0 4))
      (draw-op! gl prog buf posl coll W H vp
                {:op :rect :mode :loop :x1 x1 :y1 y1 :x2 x2 :y2 y2
                 :color [0.5 0.5 0.5 0.9] :width 2}))))

(defn render!
  "editor db を WebGL2 canvas に描画する(用紙ガイド + node draw-list + draft)。
  `redraw!`(省略時 = 同じ db でこの render! を再実行する thunk)は ai-image node の
  texture が非同期 decode 完了した瞬間にもう1回描画するための直接呼び出し —
  attach-canvas! は db* を再 deref する版を渡す(呼び出し時点の最新 db で再描画する
  ため。省略時のデフォルトはこの db スナップショットで代用するだけの fallback)。"
  ([glm db] (render! glm db (fn [] (render! glm db))))
  ([{:keys [gl prog buf posl coll] :as glm} db redraw!]
   (when (and gl db)
     (let [cv (.-canvas gl) W (.-width cv) H (.-height cv)
           vp (:viewport db)
           youshi (get-in db [:doc :pages (active-idx db) :youshi])
           draws (into (gr/youshi-draws youshi) (gr/draw-list (active-nodes db) (:selection db)))
           draft (:draft db)]
       (.viewport gl 0 0 W H) ; GL viewport(描画先ピクセル範囲) — 編集用 pan/zoom の vp とは別物
       (let [[dr dg dbv da] gr/desk-color] (.clearColor gl dr dg dbv da)) ; 机 = クリーム(紙面とは別色)
       (.clear gl (.-COLOR_BUFFER_BIT gl))
       (doseq [o draws]
         (if (= :image (:op o))
           (draw-image-op! glm W H vp o redraw!)
           (draw-op! gl prog buf posl coll W H vp o)))
       (when draft (draw-op! gl prog buf posl coll W H vp (assoc draft :mode (draft-mode draft))))))))

;; ── pointer input → adapter actions ──────────────────────────────────────────
;; canvas は attribute 解像度(1000x720 = world->screen が仮定する座標系)を CSS で
;; 伸縮表示する。offsetX/offsetY は CSS px なので、attribute/CSS 比でスケールしないと
;; buffer 解像度 ≠ 表示サイズの窓幅で pan/zoom・描画位置がずれる。

(defn- event-screen-xy [e]
  (let [cv (.-currentTarget e) rect (.getBoundingClientRect cv)]
    [(* (.-offsetX e) (/ (.-width cv) (.-width rect)))
     (* (.-offsetY e) (/ (.-height cv) (.-height rect)))]))

;; pentab 筆圧: PointerEvent.pressure(pen=0..1 実測値、mouse/未対応touchは 0 か 0.5 固定)。
;; 0 は「圧力センサ無し」を意味するため 0.6 にフォールバックし、潰れたストロークを防ぐ。
(defn- evt-pressure [e]
  (let [p (.-pressure e)] (if (pos? p) p 0.6)))

(defn attach-canvas!
  "既存の <canvas> element に genko editor を接続する: WebGL2 init + pointer/
  wheel listeners(adapter へ action 発行)+ db* 変化で再描画する reaction。
  → detach fn(listeners 除去 + reaction dispose)。

  スタンドアロン genko.html の静的 <canvas id=gl> と、reagent の `canvas`
  component の両方がこれを使う(imperative API が単一の正)。"
  [canvas {:keys [db* dispatch! prompt-fn]}]
  (let [glm (init-gl! canvas)
        prompt (or prompt-fn (fn [label default] (js/window.prompt label default)))
        on-down (fn [e]
                  (let [db @db*]
                    (when db
                      (if (= "text" (:tool db))
                        (let [[sx sy] (event-screen-xy e)
                              [x y] (gr/screen->world (:viewport db) [sx sy])]
                          (when-let [t (prompt "テキスト:" "セリフ")]
                            (when (seq t) (dispatch! [:place-text [x y] t]))))
                        (dispatch! [:pointer-down {:screen (event-screen-xy e)
                                                   :pressure (evt-pressure e)}])))))
        on-move (fn [e]
                  (when-let [db @db*]
                    (when (or (:pan-from db) (:draft db))
                      (dispatch! [:pointer-move {:screen (event-screen-xy e)
                                                 :pressure (evt-pressure e)}]))))
        on-up (fn [_] (when @db* (dispatch! [:pointer-up])))
        on-wheel (fn [e]
                   (.preventDefault e)
                   (when @db*
                     (dispatch! [:wheel-zoom {:screen (event-screen-xy e)
                                              :delta-y (.-deltaY e)}])))
        ;; ai-image texture の非同期 decode 完了トリガ: db* を経由せず(=dispatch!/app
        ;; state を一切介さず)、その時点の最新 db で直接もう1回 render! するだけの
        ;; 純ホスト側の副作用。db* が nil(unmount 後 等)なら何もしない。
        redraw! (fn redraw! [] (when-let [db @db*] (render! glm db redraw!)))
        rx (ratom/run! (render! glm @db* redraw!))]
    (.addEventListener canvas "pointerdown" on-down)
    (.addEventListener canvas "pointermove" on-move)
    (js/window.addEventListener "pointerup" on-up)
    (.addEventListener canvas "wheel" on-wheel #js {:passive false})
    (fn detach! []
      (ratom/dispose! rx)
      (.removeEventListener canvas "pointerdown" on-down)
      (.removeEventListener canvas "pointermove" on-move)
      (js/window.removeEventListener "pointerup" on-up)
      (.removeEventListener canvas "wheel" on-wheel))))

;; ── reagent components ───────────────────────────────────────────────────────

(def tool-names ["select" "draw" "panel" "fukidashi" "tone" "text"])

(defn toolbar
  "editor toolbar。opts: {:title str-or-nil} — :sync ボタン(☁)は adapter の
  :sync が在るときだけ出る(status は db の :kotoba-status)。"
  [{:keys [db* dispatch! sync]} & [{:keys [title]}]]
  (let [db @db*]
    (when db
      [:div {:style {:display "flex" :gap "6px" :padding "6px" :align-items "center"
                     :background "#111" :color "#fff" :font "13px sans-serif"
                     :flex-wrap "wrap"}}
       (when title [:b title])
       (for [t tool-names]
         ^{:key t}
         [:button {:on-click #(dispatch! [:set-tool t])
                   :style {:padding "4px 8px" :border-radius "6px" :cursor "pointer"
                           :background (if (= t (:tool db)) "#e06090" "#333") :color "#fff" :border "none"}}
          t])
       ;; 原稿用紙 template (embed の youshiType select と同じ選択肢/表記)
       [:select {:value (or (get-in db [:doc :pages (active-idx db) :youshi :type]) "none")
                 :title "原稿用紙"
                 :on-change #(dispatch! [:set-youshi-type (.. % -target -value)])
                 :style {:padding "4px" :border-radius "6px"}}
        [:option {:value "b4manga"} "B4 漫画"]
        [:option {:value "b4koma"} "4コマ"]
        [:option {:value "none"} "Free"]]
       [:select {:value "" :title "コマ割りプリセット"
                 :on-change (fn [e]
                              (let [v (.. e -target -value)]
                                (when (seq v) (dispatch! [:apply-preset v]))
                                (set! (.. e -target -value) "")))
                 :style {:padding "4px" :border-radius "6px"}}
        [:option {:value ""} "コマ割り…"]
        (for [k ["1" "2h" "2v" "3h" "2x2"]]
          ^{:key k} [:option {:value k} k])]
       (when (= "fukidashi" (:tool db))
         [:select {:key "fuki-type" :value (:fuki-type db) :title "吹き出し種別"
                   :on-change #(dispatch! [:set-fuki-type (.. % -target -value)])
                   :style {:padding "4px" :border-radius "6px"}}
          (for [ft (sort g/fukidashi-types)] ^{:key ft} [:option {:value ft} ft])])
       (when (= "fukidashi" (:tool db))
         [:select {:key "fuki-tail" :value (:fuki-tail db) :title "しっぽの向き"
                   :on-change #(dispatch! [:set-fuki-tail (.. % -target -value)])
                   :style {:padding "4px" :border-radius "6px"}}
          (for [ft (sort g/fukidashi-tails)] ^{:key ft} [:option {:value ft} ft])])
       (when (= "tone" (:tool db))
         [:select {:key "tone-pattern" :value (:tone-pattern db) :title "トーンパターン"
                   :on-change #(dispatch! [:set-tone-pattern (.. % -target -value)])
                   :style {:padding "4px" :border-radius "6px"}}
          (for [tp (sort g/tone-patterns)] ^{:key tp} [:option {:value tp} tp])])
       [:span {:style {:flex "1"}}]
       [:button {:on-click #(export-json! (:doc @db*)) :style {:padding "4px 8px"}} "⇩ export"]
       [:button {:on-click #(import-json! dispatch!) :style {:padding "4px 8px"}} "⇧ import"]
       (when sync
         [:<>
          [:button {:on-click (:save! sync) :title (:title sync)
                    :style {:padding "4px 8px"}} "☁ save"]
          [:button {:on-click (:load! sync) :title (:title sync)
                    :style {:padding "4px 8px"}} "☁ load"]
          (when-let [st (:kotoba-status db)]
            [:span {:style {:opacity 0.8 :color (if (vector? st) "#e06060" "#8fdc8f")}}
             (case st :saving "…" :saved "☁✓" :loading "…" :loaded "☁✓"
                   (str "☁✗ " (second st)))])])
       [:button {:on-click #(dispatch! [:undo]) :disabled (not (ed/can-undo? db))
                 :style {:padding "4px 8px"}} "↶ undo"]
       [:button {:on-click #(dispatch! [:redo]) :disabled (not (ed/can-redo? db))
                 :style {:padding "4px 8px"}} "↷ redo"]
       [:button {:on-click #(dispatch! [:reset-viewport])
                 :title "空白ドラッグ=pan、ホイール=zoom"
                 :style {:padding "4px 8px"}} "⌂ view"]
       [:span {:style {:margin-left "8px" :opacity 0.7}}
        (str (count (active-nodes db)) " nodes · " (.toFixed (* 100 (:zoom (:viewport db))) 0) "%")]])))

(defn- drop-position
  "drop 先 row の中心より上なら before、下なら after(HTML5 DnD dragover 座標)。"
  [e]
  (let [rect (.getBoundingClientRect (.-currentTarget e))
        mid (/ (+ (.-top rect) (.-bottom rect)) 2)]
    (if (< (.-clientY e) mid) "before" "after")))

(defn tree
  "node tree(drag 並べ替え・可視トグル・click 選択)。"
  [{:keys [db* dispatch!]}]
  (let [db @db*]
    (when db
      (let [rows (g/all-nodes (active-nodes db))
            youshi (get-in db [:doc :pages (active-idx db) :youshi])]
        [:div {:style {:width "200px" :padding "6px" :font "12px sans-serif" :overflow "auto"
                       :border-right "1px solid #ccc" :background "#faf7f0"}}
         [:div {:style {:font-weight "bold" :margin-bottom "4px"}} "Nodes"]
         ;; 原稿用紙は page 直下の特別ノード — embed の node tree と同じく
         ;; `genkouyoushi (<type>)` の行を先頭に出す(眼アイコンで表示トグル)。
         (when youshi
           (let [vis? (not (false? (:visible youshi)))]
             [:div {:style {:padding "2px 4px" :border-radius "4px"
                            :display "flex" :align-items "center" :gap "4px"
                            :opacity (if vis? 1 0.4)}}
              [:span {:on-click #(dispatch! [:toggle-youshi-vis])
                      :style {:cursor "pointer"}
                      :title "表示/非表示"} (if vis? "👁" "🚫")]
              [:span (str "genkouyoushi (" (or (:type youshi) "none") ")")]]))
         (for [row rows]
           ^{:key (:nid row)}
           [:div {:draggable true
                  :on-drag-start (fn [e] (.setData (.-dataTransfer e) "text/plain" (:nid row)))
                  :on-drag-over (fn [e] (.preventDefault e))
                  :on-drop (fn [e]
                             (.preventDefault e)
                             (let [from (.getData (.-dataTransfer e) "text/plain")]
                               (when (and (seq from) (not= from (:nid row)))
                                 (dispatch! [:reorder from (:nid row) (drop-position e)]))))
                  :on-click #(dispatch! [:select-node (:nid row)])
                  :style {:padding "2px 4px" :cursor "grab" :border-radius "4px"
                          :display "flex" :align-items "center" :gap "4px"
                          :opacity (if (:vis row) 1 0.4)
                          :background (if (contains? (:selection db) (:nid row)) "#cfe3ff" "transparent")}}
            [:span {:on-click (fn [e] (.stopPropagation e) (dispatch! [:toggle-vis (:nid row)]))
                    :title "表示/非表示"} (if (:vis row) "👁" "🚫")]
            [:span (:nm row)]])]))))

(defn canvas
  "WebGL2 canvas component(attach-canvas! を did-mount で接続、unmount で detach)。
  attribute 解像度は 1000x720 固定(world 座標系の前提)、表示サイズは :style で。"
  [adapter & [_opts]]
  (let [el (atom nil) detach (atom nil)]
    (r/create-class
     {:display-name "genko-canvas"
      :component-did-mount
      (fn [_] (when-let [cv @el] (reset! detach (attach-canvas! cv adapter))))
      :component-will-unmount
      (fn [_] (when-let [d @detach] (d) (reset! detach nil)))
      :reagent-render
      (fn [_adapter & [{:keys [width height style class]}]]
        [:canvas {:ref #(reset! el %)
                  :width (or width 1000) :height (or height 720)
                  :class class
                  :style (merge {:touch-action "none" :cursor "crosshair"
                                 :background "#f0ead6" :display "block"}
                                style)}])})))

(defn editor
  "toolbar + node tree + canvas を組んだ埋め込み用レイアウト(ページ内 mount 向け。
  スタンドアロン genko.html は fixed レイアウトなので個別 component を使う)。
  opts: {:title :height :style :canvas-style}"
  [adapter & [{:keys [title height style canvas-style]}]]
  [:div {:style (merge {:display "flex" :flex-direction "column"
                        :height (or height "70vh") :min-height "480px"
                        :background "#f0ead6"}
                       style)}
   [toolbar adapter {:title title}]
   [:div {:style {:display "flex" :flex "1" :min-height "0"}}
    [tree adapter]
    [:div {:style {:flex "1" :min-width "0" :overflow "hidden"}}
     [canvas adapter {:style (merge {:width "100%" :height "100%"} canvas-style)}]]]])
