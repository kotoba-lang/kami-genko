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

(defn draw-list
  "nodes (serialized wrappers) → flat draw-op vector (world coords)。
   `sel` (nid の set) はハイライト色に切り替える。"
  ([nodes] (draw-list nodes #{}))
  ([nodes sel]
   (vec (mapcat (fn [n]
                  (let [hl (contains? sel (g/nid-of n))]
                    (map #(cond-> % hl (assoc :color [0.11 0.45 0.90 1.0] :width 3))
                         (node->draws n))))
                nodes))))
