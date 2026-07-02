(ns kami.mangaka.genko-render
  "Pure: genko nodes → 2D draw-list in world coords (renderer-agnostic).
  cljs-only genko editor (ADR-2607020300) の描画 IR。WebGL2/WebGPU/Canvas いずれの
  ホストも同じ draw-list を消費できる。色は [r g b a] 0..1、座標は world px。"
  (:require [kami.mangaka.genko :as g]))

(def ink [0.09 0.09 0.09 1.0])

(defn node->draws
  "1 node → draw ops。panel=矩形枠, stroke=折れ線, fukidashi=楕円枠, text=位置マーカ。"
  [n]
  (let [d (g/node-data n) t (g/type-of n)]
    (case t
      "panel"     [{:op :rect :x1 (:x1 d) :y1 (:y1 d) :x2 (:x2 d) :y2 (:y2 d)
                    :color ink :width (or (:borderW d) 2)}]
      "stroke"    (when (seq (:points d))
                    [{:op :poly :points (mapv (fn [p] [(:x p) (:y p)]) (:points d))
                      :color (or (:color d) ink) :width (or (:size d) 2)}])
      "fukidashi" [{:op :ellipse :x1 (:x1 d) :y1 (:y1 d) :x2 (:x2 d) :y2 (:y2 d)
                    :color ink :width 2}]
      "tone"      [{:op :rect :x1 (:x1 d) :y1 (:y1 d) :x2 (:x2 d) :y2 (:y2 d)
                    :color [0.5 0.5 0.5 0.6] :width 1}]
      "text"      [{:op :rect :x1 (:x d) :y1 (:y d) :x2 (+ (:x d) 8) :y2 (+ (:y d) 8)
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
      [{:op :rect :x1 ox1 :y1 oy1 :x2 ox2 :y2 oy2 :color guide :width 1}
       {:op :rect :x1 ix1 :y1 iy1 :x2 ix2 :y2 iy2 :color inner-guide :width 1}])))

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
