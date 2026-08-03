(ns kami.mangaka.tategaki
  "吹き出しの中身をどう組むか — **言語で切り替わる**行組みの純ロジック。

   なぜ genko に置くか: `genko-render/node->draws` は text node に 8x8 のマーカ矩形しか
   出さない（グリフは host が描く、という設計）。だが「どの字を回すか」「どちらへ流すか」は
   フォントにもキャンバスにも依存しない**純粋な規則**で、host ごとに再実装させると必ずズレる。
   実際、消費者側（ghosthacker-shiropico の build-page）が独自に縦組みを書いた結果、
   **長音符が横に寝たまま出力された**（「見ろオ ー リオ！」）。それを直すのはここ。

   分担: **この ns は位置と回転フラグを返すだけ。実際にグリフを描くのは host。**
   `:writing-mode` 自体は genko-project が既に持っている語彙（\"vertical-rtl\" / \"horizontal\"）で、
   ここはその下位＝字の並べ方を担当する。"
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; 言語 → 組み方向
;; ---------------------------------------------------------------------------

(def vertical-locales
  "既定で縦組みにするロケール。日本語のみ。

   中国語は縦組みが可能だが、現代の出版は横組みが主流で、繁体/簡体でも慣行が割れる。
   韓国語も現代はほぼ横組み。**『できる』と『既定にする』は別**なので、
   縦にしたい場合は呼び出し側が :writing-mode を明示する。"
  #{:ja "ja" "ja-JP"})

(def rtl-locales
  "横組みだが右から左へ流すロケール。"
  #{:ar "ar" :he "he" :fa "fa" :ur "ur"})

(defn writing-mode
  "ロケール → :vertical-rtl | :horizontal-lr | :horizontal-rtl。
   `override` に \"vertical-rtl\" / \"horizontal\" 等が来たらそちらを優先する
   （genko-project の :bubble/writing-mode と同じ語彙を受ける）。"
  ([locale] (writing-mode locale nil))
  ([locale override]
   (cond
     (contains? #{"vertical-rtl" :vertical-rtl "vertical" :vertical} override) :vertical-rtl
     (contains? #{"horizontal" :horizontal "horizontal-lr" :horizontal-lr} override) :horizontal-lr
     (contains? #{"horizontal-rtl" :horizontal-rtl} override) :horizontal-rtl
     (contains? vertical-locales locale) :vertical-rtl
     (contains? rtl-locales locale)      :horizontal-rtl
     :else                                :horizontal-lr)))

(defn vertical? [mode] (= :vertical-rtl mode))

;; ---------------------------------------------------------------------------
;; 縦組みで 90° 回す字
;; ---------------------------------------------------------------------------

(def rotate-in-vertical
  "縦組みのとき 90° 回転させる字。

   横組みの字形のまま縦に積むと寝てしまうもの: 長音符・各種ダッシュ・三点リーダ・
   括弧類・等号・縦棒・罫線。**これを落とすと長音符が横に寝る**（実測）。

   ⚠ 小書き仮名（っゃゅょ等）は回転ではなく**位置のオフセット**が要る別問題で、
   ここでは扱わない（`small-kana` を参照）。"
  (set "ー−‐‑‒–—―〜～…‥（）()「」『』【】〔〕〈〉《》｛｝{}［］[]＝=｜|＜＞<>"))

(def small-kana
  "縦組みで右上へ寄せる小書き仮名。回転はしない。
   本 ns は位置補正の**必要性を報告するだけ**で、量は host のフォントメトリクスに依存する。"
  (set "ぁぃぅぇぉっゃゅょゎァィゥェォッャュョヮヵヶ"))

;; ---------------------------------------------------------------------------
;; 行組み
;; ---------------------------------------------------------------------------

(defn layout
  "吹き出しの矩形とテキスト行から、**字ごとの位置と回転フラグ**を返す。

   `lines` は既に改行済みの行ベクタ（折り返しは呼び出し側の責任 — 禁則処理は別問題）。
   `box` は {:x1 :y1 :x2 :y2}。`opts`:
     :locale        ロケール（既定 :ja）
     :writing-mode  明示的な上書き（genko-project と同じ語彙）
     :font-size     1字の送り（既定 14.0）
     :line-gap      行送りの倍率（既定 1.45）

   返り値:
     {:mode :vertical-rtl
      :glyphs [{:ch \\見 :x 120.5 :y 88.0 :rotate? false :small? false} …]}

   **縦組みでは行は右から左へ並ぶ。** 1行目がいちばん右。
   host はこの :glyphs をそのまま描けばよい（:rotate? が真なら (:x,:y) を中心に 90° 回す）。"
  ([lines box] (layout lines box {}))
  ([lines {:keys [x1 y1 x2 y2]}
    {:keys [locale writing-mode font-size line-gap]
     :or   {locale :ja font-size 14.0 line-gap 1.45}}]
   (let [mode (kami.mangaka.tategaki/writing-mode locale writing-mode)
         fs   (double font-size)
         adv  (* fs line-gap)
         cx   (/ (+ x1 x2) 2.0)
         cy   (/ (+ y1 y2) 2.0)
         n    (count lines)
         mk   (fn [ch x y]
                {:ch ch :x x :y y
                 :rotate? (and (vertical? mode) (contains? rotate-in-vertical ch))
                 :small?  (contains? small-kana ch)})]
     {:mode mode
      :glyphs
      (vec
       (if (vertical? mode)
         ;; 縦: 行は右→左、字は上→下
         (let [x0 (+ cx (* adv (/ (dec n) 2.0)))]
           (mapcat
            (fn [i line]
              (let [x  (- x0 (* i adv))
                    m  (count line)
                    y0 (- cy (* (/ (dec m) 2.0) fs))]
                (map-indexed (fn [j ch] (mk ch x (+ y0 (* j fs)))) line)))
            (range) lines))
         ;; 横: 行は上→下、字は左→右（RTL なら右→左）
         (let [rtl? (= :horizontal-rtl mode)
               y0   (- cy (* (/ (dec n) 2.0) adv))]
           (mapcat
            (fn [i line]
              (let [y  (+ y0 (* i adv))
                    m  (count line)
                    x0 (- cx (* (/ (dec m) 2.0) fs))]
                (map-indexed
                 (fn [j ch] (mk ch (if rtl? (- (+ x0 (* (dec m) fs)) (* j fs))
                                       (+ x0 (* j fs))) y))
                 line)))
            (range) lines))))})))

(defn box-size
  "行の内容から、必要な吹き出しの内寸 [w h] を返す。
   **箱を先に決めて字を詰めると溢れる**（実測）ので、字から箱を起こすための関数。
   `pad` は字送りの何倍を余白に取るか（ギザ吹き出しは輪郭が内側へ食い込むので厚めに）。"
  ([lines] (box-size lines {}))
  ([lines {:keys [locale writing-mode font-size line-gap pad]
           :or   {locale :ja font-size 14.0 line-gap 1.45 pad 1.6}}]
   ;; 余白は両軸に同じだけ取る。片側だけ厚くすると、行数と字数が近いときに
   ;; 組み方向と箱の縦横が食い違う（3字×2行の横組みが縦長になった。実測）。
   ;; しっぽのぶんの余裕が要るなら、呼び出し側が :pad を上げる。
   (let [mode (kami.mangaka.tategaki/writing-mode locale writing-mode)
         fs   (double font-size)
         adv  (* fs line-gap)
         n    (count lines)
         m    (if (seq lines) (apply max (map count lines)) 0)
         p    (* fs pad)]
     (if (vertical? mode)
       [(+ (* n adv) p) (+ (* m fs) p)]
       [(+ (* m fs) p)  (+ (* n adv) p)]))))
