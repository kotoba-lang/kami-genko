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

;; ── color helpers ────────────────────────────────────────────────────────────

#?(:clj (defn- hex-byte [s] (Integer/parseInt s 16))
   :cljs (defn- hex-byte [s] (js/parseInt s 16)))

(defn hex->rgba
  "\"#rrggbb\" (g/agent-color の返り値) → [r g b a] 0..1(a=1.0 固定)。"
  [hex]
  (let [h (subs hex 1)]
    [(/ (hex-byte (subs h 0 2)) 255.0)
     (/ (hex-byte (subs h 2 4)) 255.0)
     (/ (hex-byte (subs h 4 6)) 255.0)
     1.0]))

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

(def ^:private prompt-placeholder-width 2)

(defn prompt-draws
  "\"prompt\" node → 矩形の placeholder 枠(この panel の予定コンテンツを示すだけ)、
  persona の agent-color で色分け(g/agent-color と同じ色 SSoT を使い、toolbar/node-tree
  等の他箇所と一致させる)。既存語彙(:rect :mode :loop, 新規プリミティブ無し)を再利用 —
  \"dashed\" な見た目(線分の破線 stipple)にするには :mode :segments 等で輪郭を多数の
  短い線分へ分割する必要があるが、それは新規の tessellation ロジックであり、テキスト
  node の 8x8 マーカ矩形と同じ fidelity(=グリフ/実コンテンツは描かない placeholder)を
  保つにはオーバーエンジニアリングなので採らない — agent-color による色分けだけで
  panel 枠(ink 色/実線)と視覚的に区別できる。"
  [{:keys [x1 y1 x2 y2 _agent]}]
  [{:op :rect :mode :loop :x1 x1 :y1 y1 :x2 x2 :y2 y2
    :color (hex->rgba (g/agent-color _agent)) :width prompt-placeholder-width}])

(defn ai-image-draws
  "\"ai-image\" node → :op :image 1つ(bounds + 安定 cache key + base64 payload)。
  key は node id(:_nid) — この node は生成成功時に一度だけ作られ、以後 :_genImage は
  変更されない(app-aozora の manga_chat.cljc に mutate 箇所なし)ので id 単独で安全な
  cache key になる。host(WebGL2)は key ごとに一度だけ decode+upload しキャッシュする。
  :_genImage が空(理論上は起きないが防御的に)は draw op を出さない。"
  [{:keys [x1 y1 x2 y2 _genImage] :as d} nid]
  (when (seq _genImage)
    [{:op :image :x1 x1 :y1 y1 :x2 x2 :y2 y2 :image-key nid :image-b64 _genImage}]))

(defn node->draws
  "1 node → draw ops。panel=矩形枠, stroke=筆圧リボン, fukidashi=種別別輪郭+しっぽ,
  tone=パターン塗り, text=位置マーカ, prompt=agent-color 枠(placeholder),
  ai-image=テクスチャ画像(:op :image)。"
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
      "prompt"    (when (:x1 d) (prompt-draws d))
      "ai-image"  (when (:x1 d) (ai-image-draws d (g/nid-of n)))
      nil)))

;; ── 原稿用紙 (genkouyoushi) templates ───────────────────────────────────────
;; SSoT = kami-engine-sdk src/lib/genko/genko-embed.ts の YOUSHI / tessellateYoushi
;; (週刊少年ジャンプ特製漫画原稿用紙再現、実寸 mm ベース)の忠実移植:
;;   B4判 用紙全体:       0,0 → 257,364 mm
;;   裁ち落とし枠 (trim):  18,18 → 239,346 (仕上がり B5 182×257mm + α)
;;   基本枠 (outer):       25,27 → 232,337 (印刷領域)
;;   内枠 (inner/safe):    53.5,72 → 203.5,292 (150×220mm テキスト安全域)
;;   目盛り: 用紙4辺 1mm 小(2mm 長)/ 5mm 大(4mm 長)、目盛り帯は 4辺 15mm 幅
;;   トンボ: 裁ち落とし枠4隅 (L字 10mm 内向き + 十字 ±2mm) + 4辺中央 (バー ±3mm + 10mm 内向き)
;;   4コマ分割線 (b4koma): 内枠を縦4等分する水平線 3本
;;   センターマーク: 内枠4辺中央の外側に十字
;;   色: ガイド水色 CB=[0.55 0.78 0.92](枠 α=0.6/0.8/0.9, 目盛り α=0.7)、
;;       コマ分割 CG=[0.7 0.7 0.7] α=0.4、トンボ 黒 α=0.5/0.4、
;;       紙面 [0.98 0.98 0.97]、目盛り帯 [0.88 0.94 0.97]、机(desk)= #f0ead6
;;   線幅: 0.15..0.7mm(embed は最低 0.5px の半幅で tessellate — fit スケール
;;       ≈1.87px/mm では実質 ≈1px。本 renderer は hairline で描く=同等の見た目)。
;; mm→world px の spec/算術の正本は kami.mangaka.genko(doc 層、canvaskit 非依存)
;; へ移動 — genko-tx 系の消費者が render を require せずに読めるようにするため。
;; 以下は後方互換 alias(genko-ui / テスト / 既存 host は gr/* 名で参照し続ける)。

(def youshi-templates g/youshi-templates)
(def youshi-px-per-mm g/youshi-px-per-mm)
(def youshi-origin g/youshi-origin)
(def mm->world g/mm->world)
(def mm-rect->world g/mm-rect->world)
(def youshi-paper-bounds g/youshi-paper-bounds)
(def youshi-trim-bounds g/youshi-trim-bounds)
(def youshi-frame-bounds g/youshi-frame-bounds)
(def youshi-safe-bounds g/youshi-safe-bounds)

(def desk-color
  "机(キャンバス背景)。embed のページ背景 #f0ead6 と同じクリーム。"
  [0.94 0.918 0.84 1.0])
(def paper-color [0.98 0.98 0.97 1.0])
(def ^:private ruler-band-color [0.88 0.94 0.97 1.0])
(def ^:private cb [0.55 0.78 0.92])   ; 水色 guidelines (embed CB)
(def ^:private cg [0.7 0.7 0.7])      ; gray koma divider (embed CG)

(defn- fill-rect [b color] (merge {:op :rect :mode :fan :color color} b))
(defn- frame-rect [b color width] (merge {:op :rect :mode :loop :color color :width width} b))
(defn- seg-op
  "mm 座標の線分列(点ペアの flat vector)→ :segments draw op (world coords)。
  :mode :segments は GL_LINES(点2つで独立線分1本)— 目盛り/トンボを 1 op に束ねる。"
  [mm-pts color]
  {:op :poly :mode :segments :color color
   :points (mapv (fn [[x y]] (mm->world x y)) mm-pts)})

(defn- ruler-tick-segs
  "目盛り(embed tessellateYoushi §3)。4辺、`small` mm 刻み、`step` の倍数は大目盛り。
  → {:big [...] :small [...]}(mm 点ペアの flat vector)。"
  [w h step small]
  (let [tick (fn [mm len horizontal?]
               (if horizontal?
                 [[mm 0.0] [mm len] [mm h] [mm (- h len)]]          ; top + bottom
                 [[0.0 mm] [len mm] [w mm] [(- w len) mm]]))        ; left + right
        gen (fn [limit horizontal?]
              (reduce (fn [acc mm]
                        (let [big? (zero? (mod mm step))]
                          (update acc (if big? :big :small)
                                  into (tick (double mm) (if big? 4.0 2.0) horizontal?))))
                      {:big [] :small []}
                      (range 0 (inc limit) small)))
        xs (gen (long w) true)
        ys (gen (long h) false)]
    {:big (into (:big xs) (:big ys)) :small (into (:small xs) (:small ys))}))

(defn- tombo-segs
  "トンボ(embed §7)。→ {:corner [...] :cross [...]}(mm 点ペア)。:corner = 4隅の
  L字主線(α0.5)、:cross = 4隅の十字 + 4辺中央のセンタートンボ(α0.4)。"
  [{tl :x1 tt :y1 tr :x2 tb :y2}]
  (let [len 10.0
        corners [[tl tt -1.0 -1.0] [tr tt 1.0 -1.0] [tr tb 1.0 1.0] [tl tb -1.0 1.0]]
        corner-main (mapcat (fn [[cx cy dx dy]]
                              [[cx cy] [(- cx (* dx len)) cy]
                               [cx cy] [cx (- cy (* dy len))]])
                            corners)
        corner-cross (mapcat (fn [[cx cy _ _]]
                               [[(- cx 2.0) cy] [(+ cx 2.0) cy]
                                [cx (- cy 2.0)] [cx (+ cy 2.0)]])
                             corners)
        mid-x (/ (+ tl tr) 2.0) mid-y (/ (+ tt tb) 2.0)
        center (mapcat (fn [[cx cy dx dy]]
                         (if (zero? dx)
                           [[(- cx 3.0) cy] [(+ cx 3.0) cy]
                            [cx cy] [cx (- cy (* dy len))]]
                           [[cx (- cy 3.0)] [cx (+ cy 3.0)]
                            [cx cy] [(- cx (* dx len)) cy]]))
                       [[mid-x tt 0.0 -1.0] [mid-x tb 0.0 1.0]
                        [tl mid-y -1.0 0.0] [tr mid-y 1.0 0.0]])]
    {:corner (vec corner-main) :cross (vec (concat corner-cross center))}))

(defn- center-mark-segs
  "内枠⇔基本枠間のセンターマーク十字(embed §9)。→ mm 点ペア。"
  [{il :x1 it :y1 ir :x2 ib :y2}]
  (let [cx (/ (+ il ir) 2.0) cy (/ (+ it ib) 2.0)]
    [[(- cx 3.0) (- it 4.0)] [(+ cx 3.0) (- it 4.0)] [cx (- it 7.0)] [cx (- it 1.0)]
     [(- cx 3.0) (+ ib 4.0)] [(+ cx 3.0) (+ ib 4.0)] [cx (+ ib 1.0)] [cx (+ ib 7.0)]
     [(- il 4.0) (- cy 3.0)] [(- il 4.0) (+ cy 3.0)] [(- il 7.0) cy] [(- il 1.0) cy]
     [(+ ir 4.0) (- cy 3.0)] [(+ ir 4.0) (+ cy 3.0)] [(+ ir 1.0) cy] [(+ ir 7.0) cy]]))

(defn youshi-draws
  "原稿用紙 template → draw-list (world coords)。genko-embed.ts tessellateYoushi の
  忠実移植で、描画順も一致: 1 紙面(白) → 2 目盛り帯(淡水色4辺) → 3 目盛り →
  4 裁ち落とし枠 → 5 基本枠 → 6 内枠 → 7 トンボ → 8 4コマ分割線(b4koma のみ) →
  9 センターマーク。
  visible=false / type=\"none\" は空(自由キャンバス)。youshi が無い(nil)・未知 type の
  page も \"none\" と同じ空 — 旧 embed の bootstrap は必ず youshi を作るので youshi 無し
  doc は手組み由来であり、勝手に紙を敷かないのが最小驚き(テンプレは toolbar の
  原稿用紙 select でいつでも敷ける)。"
  [{:keys [type visible]}]
  (let [{:keys [draw w-mm h-mm trim outer inner ruler-step ruler-small koma]}
        (get youshi-templates type)]
    (if (or (false? visible) (not draw))
      []
      (let [rm 15.0 ; 目盛り帯の幅 mm (embed §2)
            ticks (ruler-tick-segs w-mm h-mm ruler-step ruler-small)
            tombo (tombo-segs trim)
            {il :x1 it :y1 ir :x2 ib :y2} inner]
        (-> [;; 1. 紙面
             (fill-rect (mm-rect->world {:x1 0.0 :y1 0.0 :x2 w-mm :y2 h-mm}) paper-color)
             ;; 2. 目盛り帯 (淡い水色、4辺)
             (fill-rect (mm-rect->world {:x1 0.0 :y1 0.0 :x2 w-mm :y2 rm}) ruler-band-color)
             (fill-rect (mm-rect->world {:x1 0.0 :y1 (- h-mm rm) :x2 w-mm :y2 h-mm}) ruler-band-color)
             (fill-rect (mm-rect->world {:x1 0.0 :y1 rm :x2 rm :y2 (- h-mm rm)}) ruler-band-color)
             (fill-rect (mm-rect->world {:x1 (- w-mm rm) :y1 rm :x2 w-mm :y2 (- h-mm rm)}) ruler-band-color)
             ;; 3. 目盛り (5mm 大 / 1mm 小)
             (seg-op (:big ticks) (conj cb 0.7))
             (seg-op (:small ticks) (conj cb 0.7))
             ;; 4-6. 裁ち落とし枠 → 基本枠 → 内枠 (水色、細→太)
             (frame-rect (mm-rect->world trim) (conj cb 0.6) 1)
             (frame-rect (mm-rect->world outer) (conj cb 0.8) 1)
             (frame-rect (mm-rect->world inner) (conj cb 0.9) 1)
             ;; 7. トンボ (4隅 L字 + 十字、4辺中央)
             (seg-op (:corner tombo) [0.0 0.0 0.0 0.5])
             (seg-op (:cross tombo) [0.0 0.0 0.0 0.4])]
            ;; 8. 4コマ分割線 (b4koma)
            (cond->
             koma (conj (seg-op (vec (mapcat (fn [k]
                                               (let [ky (+ it (* (/ (- ib it) koma) k))]
                                                 [[il ky] [ir ky]]))
                                             (range 1 koma)))
                                (conj cg 0.4))))
            ;; 9. センターマーク (内枠4辺中央の外側)
            (conj (seg-op (center-mark-segs inner) (conj cb 0.5))))))))

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
