(ns kami.mangaka.genko-render
  "Pure: genko nodes → 2D draw-list in world coords (renderer-agnostic).
  cljs-only genko editor (ADR-2607020300) の描画 IR。WebGL2/WebGPU/Canvas いずれの
  ホストも同じ draw-list を消費できる。色は [r g b a] 0..1、座標は world px。

  各 draw op は `:mode` で描画プリミティブを明示する(:loop=LINE_LOOP 閉曲線,
  :line=LINE_STRIP 開曲線, :fan=TRIANGLE_FAN 塗り凸多角形(周回順の頂点),
  :strip=TRIANGLE_STRIP 塗りリボン(L/R 交互頂点、pentab 筆圧ストローク用))。"
  (:require [kami.mangaka.genko :as g]
            [canvaskit.scroll-view :as cksv]
            [canvaskit.viewport :as ckvp]))

(def ink [0.09 0.09 0.09 1.0])

;; ── viewport(pan/zoom) — 実装は kotoba-lang/canvaskit(UIScrollView semantics、
;; ADR-2607071130。旧: freeboard.board からの移植コピーを正本へ差し戻し)。
;; doc/API は従来どおり {:x :y :zoom}(node 座標は常に world 固定、host は
;; screen(canvas px) との往復に world->screen/screen->world を介す — pointer 入力は
;; 入口で screen→world に変換して doc に積み、描画は world→screen→clip で毎フレーム投影)。────────
(def default-viewport {:x 0.0 :y 0.0 :zoom 1.0})
(def ^:private zoom-limits {:minimum-zoom-scale 0.2 :maximum-zoom-scale 8.0})

(defn- ->scroll-view [vp] (ckvp/from-viewport vp zoom-limits))

(defn world->screen [vp world-pt]
  (cksv/convert-point-to-view (->scroll-view vp) world-pt))

(defn screen->world [vp screen-pt]
  (cksv/convert-point-from-view (->scroll-view vp) screen-pt))

(defn pan-viewport
  "screen 座標系のドラッグ量(dx-screen dy-screen)だけ pan する。"
  [vp dx-screen dy-screen]
  (ckvp/to-viewport (cksv/scroll-by (->scroll-view vp) [(- dx-screen) (- dy-screen)])))

(defn zoom-viewport
  "screen 点 [sx sy](ズーム中心、通常カーソル位置)の下の world 点を固定したまま
  new-zoom(0.2..8.0 にクランプ)へズームする。"
  [vp new-zoom screen-pt]
  (ckvp/to-viewport (cksv/zoom-to-point (->scroll-view vp) new-zoom screen-pt)))

;; cljs に無い Math 静的メソッドを reader-conditional で吸収(genko.cljc の gen-nid と同型)。
#?(:clj (defn- cos [a] (Math/cos a)) :cljs (defn- cos [a] (js/Math.cos a)))
#?(:clj (defn- sin [a] (Math/sin a)) :cljs (defn- sin [a] (js/Math.sin a)))
#?(:clj (defn- sqrt [a] (Math/sqrt a)) :cljs (defn- sqrt [a] (js/Math.sqrt a)))

(def ^:private tau 6.283185307179586)

(defn- ellipse-pt
  "楕円周上、角度 a における点。rmul=1.0 が輪郭、それ以外で半径を変調(jagged/cloud/wavy)。"
  [x1 y1 x2 y2 a rmul]
  (let [cx (/ (+ x1 x2) 2.0) cy (/ (+ y1 y2) 2.0)
        rx (* rmul (/ (- x2 x1) 2.0)) ry (* rmul (/ (- y2 y1) 2.0))]
    [(+ cx (* rx (cos a))) (+ cy (* ry (sin a)))]))

(defn ellipse-outline-pts
  "楕円輪郭(等分割、既定48点)。fukiType=\"oval\" の形。"
  ([x1 y1 x2 y2] (ellipse-outline-pts x1 y1 x2 y2 48))
  ([x1 y1 x2 y2 n] (mapv #(ellipse-pt x1 y1 x2 y2 (* tau (/ % (double n))) 1.0) (range n))))

(defn jagged-fuki-pts
  "fukiType=\"jagged\": 半径を交互に強く凹ませたギザギザ輪郭。"
  [x1 y1 x2 y2]
  (mapv #(ellipse-pt x1 y1 x2 y2 (* tau (/ % 20.0)) (if (even? %) 1.0 0.82)) (range 20)))

(defn cloud-fuki-pts
  "fukiType=\"cloud\": 高周波の小さい正弦波でボコボコさせた輪郭(もくもく雲)。"
  [x1 y1 x2 y2]
  (mapv #(let [a (* tau (/ % 40.0))] (ellipse-pt x1 y1 x2 y2 a (+ 1.0 (* 0.08 (sin (* a 8)))))) (range 40)))

(defn wavy-fuki-pts
  "fukiType=\"wavy\": 低周波・大振幅の正弦波でうねらせた輪郭。"
  [x1 y1 x2 y2]
  (mapv #(let [a (* tau (/ % 32.0))] (ellipse-pt x1 y1 x2 y2 a (+ 1.0 (* 0.12 (sin (* a 5)))))) (range 32)))

(def ^:private tail-len 26)
(def ^:private tail-half-w 9)

(defn fukidashi-tail-tri
  "fukiTail(\"bottom\"/\"top\"/\"left\"/\"right\")→ bounds 辺中央から突き出す吹き出しの
  しっぽ(3頂点、周回順=:fan で塗れる)。\"none\"/未知/nil は nil(しっぽ無し)。"
  [tail x1 y1 x2 y2]
  (let [cx (/ (+ x1 x2) 2.0) cy (/ (+ y1 y2) 2.0)]
    (case tail
      "bottom" [[(- cx tail-half-w) y2] [(+ cx tail-half-w) y2] [cx (+ y2 tail-len)]]
      "top"    [[(- cx tail-half-w) y1] [(+ cx tail-half-w) y1] [cx (- y1 tail-len)]]
      "left"   [[x1 (- cy tail-half-w)] [x1 (+ cy tail-half-w)] [(- x1 tail-len) cy]]
      "right"  [[x2 (- cy tail-half-w)] [x2 (+ cy tail-half-w)] [(+ x2 tail-len) cy]]
      nil)))

(defn fukidashi-draws
  "fukiType 別の輪郭 draw op(s) + fukiTail のしっぽ(あれば)。"
  [{:keys [fukiType fukiTail x1 y1 x2 y2]}]
  (into (case fukiType
          "square" [{:op :rect :mode :loop :x1 x1 :y1 y1 :x2 x2 :y2 y2 :color ink :width 2}]
          "jagged" [{:op :poly :mode :loop :points (jagged-fuki-pts x1 y1 x2 y2) :color ink :width 2}]
          "cloud"  [{:op :poly :mode :loop :points (cloud-fuki-pts x1 y1 x2 y2) :color ink :width 2}]
          "wavy"   [{:op :poly :mode :loop :points (wavy-fuki-pts x1 y1 x2 y2) :color ink :width 2}]
          [{:op :poly :mode :loop :points (ellipse-outline-pts x1 y1 x2 y2) :color ink :width 2}])
        (when-let [tri (fukidashi-tail-tri fukiTail x1 y1 x2 y2)]
          [{:op :poly :mode :fan :points tri :color ink}])))

(defn- perp-norm
  "2点を結ぶ線分の単位法線(向き付き; ribbon の左右オフセット方向)。"
  [[x0 y0] [x1 y1]]
  (let [dx (- x1 x0) dy (- y1 y0) len (max 1e-6 (sqrt (+ (* dx dx) (* dy dy))))]
    [(/ (- dy) len) (/ dx len)]))

(defn stroke-ribbon-pts
  "pentab 筆圧ストローク: pressure 付き points([{:x :y :p} …]) + base size から
  TRIANGLE_STRIP 用の L0,R0,L1,R1,… 交互頂点(線幅 = size*pressure, 最低幅floor
  で潰れを防ぐ)を生成する簡易 tessellation(miter 接合はしない、法線オフセットのみ)。
  2点未満は nil。"
  [points size]
  (when (>= (count points) 2)
    (let [pv (mapv (fn [p] [(:x p) (:y p) (max 0.35 (double (or (:p p) 0.6)))]) points)
          n (count pv)]
      (vec (mapcat
            (fn [i]
              (let [[x y p] (pv i)
                    prev (pv (max 0 (dec i))) nxt (pv (min (dec n) (inc i)))
                    [nx ny] (perp-norm [(prev 0) (prev 1)] [(nxt 0) (nxt 1)])
                    hw (* 0.5 size p)]
                [[(+ x (* nx hw)) (+ y (* ny hw))]
                 [(- x (* nx hw)) (- y (* ny hw))]]))
            (range n))))))

(def ^:private tone-fill [0.35 0.35 0.35 0.85])

(defn tone-dot-draws
  "tonePattern=\"dot\": グリッド上に並んだ塗り円(網点/ハーフトーン風)。"
  [x1 y1 x2 y2]
  (let [step 14 r 3]
    (vec (for [gy (range (+ y1 (/ step 2.0)) y2 step)
               gx (range (+ x1 (/ step 2.0)) x2 step)]
           {:op :poly :mode :fan :color tone-fill
            :points (ellipse-outline-pts (- gx r) (- gy r) (+ gx r) (+ gy r) 10)}))))

(defn tone-line-draws
  "tonePattern=\"line\": 水平の平行線群(斜線的ハッチング)。"
  [x1 y1 x2 y2]
  (let [step 10]
    (vec (for [gy (range (+ y1 step) y2 step)]
           {:op :poly :mode :line :color tone-fill :points [[x1 gy] [x2 gy]]}))))

(defn tone-cross-draws
  "tonePattern=\"cross\": 水平+垂直の格子ハッチング。"
  [x1 y1 x2 y2]
  (let [step 12]
    (into (vec (for [gy (range (+ y1 step) y2 step)]
                 {:op :poly :mode :line :color tone-fill :points [[x1 gy] [x2 gy]]}))
          (for [gx (range (+ x1 step) x2 step)]
            {:op :poly :mode :line :color tone-fill :points [[gx y1] [gx y2]]}))))

(defn tone-grad-draws
  "tonePattern=\"grad\": 上から下へ不透明度が減衰する帯を積んだグラデーション。"
  [x1 y1 x2 y2]
  (let [bands 8 bh (/ (- y2 y1) bands) [r g b a] tone-fill]
    (vec (for [i (range bands)]
           (let [yy1 (+ y1 (* i bh)) yy2 (+ yy1 bh) t (/ i (double (dec bands)))]
             {:op :rect :mode :fan :x1 x1 :y1 yy1 :x2 x2 :y2 yy2
              :color [r g b (* a (- 1.0 t))]})))))

(defn tone-draws
  "tonePattern 別の draw-list(未知/nil は既定 \"dot\")。"
  [{:keys [tonePattern x1 y1 x2 y2]}]
  (case tonePattern
    "line" (tone-line-draws x1 y1 x2 y2)
    "cross" (tone-cross-draws x1 y1 x2 y2)
    "grad" (tone-grad-draws x1 y1 x2 y2)
    (tone-dot-draws x1 y1 x2 y2)))

(defn node->draws
  "1 node → draw ops。panel=矩形枠, stroke=筆圧リボン, fukidashi=種別別輪郭+しっぽ,
  tone=パターン塗り, text=位置マーカ。"
  [n]
  (let [d (g/node-data n) t (g/type-of n)]
    (case t
      "panel"     [{:op :rect :mode :loop :x1 (:x1 d) :y1 (:y1 d) :x2 (:x2 d) :y2 (:y2 d)
                    :color ink :width (or (:borderW d) 2)}]
      "stroke"    (when-let [pts (stroke-ribbon-pts (:points d) (or (:size d) 2))]
                    [{:op :poly :mode :strip :points pts :color (or (:color d) ink)}])
      "fukidashi" (fukidashi-draws d)
      "tone"      (tone-draws d)
      "text"      [{:op :rect :mode :loop :x1 (:x d) :y1 (:y d) :x2 (+ (:x d) 8) :y2 (+ (:y d) 8)
                    :color [0.7 0.1 0.45 1.0] :width 1}]
      nil)))

(def guide [0.6 0.6 0.55 1.0])
(def inner-guide [0.72 0.78 0.9 1.0])

(defn youshi-draws
  "原稿用紙(b4manga)の枠ガイド → draw-list。visible=false / type=\"none\" は空。
   世界座標は g/youshi-outer-bounds(裁ち落とし) / g/youshi-inner-bounds(基本枠, パネル
   プリセットが分割する対象と同じ SSoT)。"
  [{:keys [type visible]}]
  (if (or (false? visible) (= type "none"))
    []
    (let [{ox1 :x1 oy1 :y1 ox2 :x2 oy2 :y2} g/youshi-outer-bounds
          {ix1 :x1 iy1 :y1 ix2 :x2 iy2 :y2} g/youshi-inner-bounds]
      [{:op :rect :mode :loop :x1 ox1 :y1 oy1 :x2 ox2 :y2 oy2 :color guide :width 1}
       {:op :rect :mode :loop :x1 ix1 :y1 iy1 :x2 ix2 :y2 iy2 :color inner-guide :width 1}])))

(defn draw-list
  "nodes (serialized wrappers) → flat draw-op vector (world coords)。不可視(祖先含む,
   g/node-visible?)な node は描かない。`sel`(nid の set)はハイライト色に切り替える。"
  ([nodes] (draw-list nodes #{}))
  ([nodes sel]
   (vec (mapcat (fn [n]
                  (when (g/node-visible? nodes (g/nid-of n))
                    (let [hl (contains? sel (g/nid-of n))]
                      (map #(cond-> % hl (assoc :color [0.11 0.45 0.90 1.0] :width 3))
                           (node->draws n)))))
                nodes))))
