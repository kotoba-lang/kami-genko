(ns kami.mangaka.genko-render-test
  (:require [clojure.test :refer [deftest is testing]]
            [kami.mangaka.genko :as g]
            [kami.mangaka.genko-render :as gr]))

(deftest viewport-transforms
  (testing "既定 viewport は world = screen(恒等変換)"
    (is (= [10.0 20.0] (gr/world->screen gr/default-viewport [10 20])))
    (is (= [10.0 20.0] (gr/screen->world gr/default-viewport [10 20]))))
  (testing "world->screen と screen->world は互いに逆変換(round-trip)"
    (let [vp {:x 50 :y -30 :zoom 2.5}]
      (doseq [pt [[0 0] [100 200] [-40 15]]]
        (let [[sx sy] (gr/world->screen vp pt)
              [wx wy] (gr/screen->world vp [sx sy])]
          (is (< (Math/abs (- wx (first pt))) 1e-9))
          (is (< (Math/abs (- wy (second pt))) 1e-9))))))
  (testing "pan-viewport は screen delta を zoom で割った分だけ world 原点を動かす"
    (let [vp {:x 0.0 :y 0.0 :zoom 2.0}
          panned (gr/pan-viewport vp 20 10)]
      (is (= -10.0 (:x panned))) (is (= -5.0 (:y panned)))))
  (testing "zoom-viewport はクランプし、カーソル下の world 点を固定する(zoom-to-cursor)"
    (let [vp {:x 0.0 :y 0.0 :zoom 1.0}
          cursor [100 200]
          before (gr/screen->world vp cursor)
          zoomed (gr/zoom-viewport vp 3.0 cursor)
          after (gr/screen->world zoomed cursor)]
      (is (= 3.0 (:zoom zoomed)))
      (is (< (Math/abs (- (first before) (first after))) 1e-9))
      (is (< (Math/abs (- (second before) (second after))) 1e-9)))
    (is (= 8.0 (:zoom (gr/zoom-viewport gr/default-viewport 999 [0 0]))) "max-zoom でクランプ")
    (is (= 0.2 (:zoom (gr/zoom-viewport gr/default-viewport 0.0001 [0 0]))) "min-zoom でクランプ")))

(deftest fuki-outline-shapes
  (testing "oval は48点の輪郭"
    (is (= 48 (count (gr/ellipse-outline-pts 0 0 100 100)))))
  (testing "jagged は交互に半径が凹む(偶数idxが外側)"
    (let [pts (gr/jagged-fuki-pts 0 0 100 100)
          cx 50.0 cy 50.0
          dist (fn [[x y]] (Math/sqrt (+ (Math/pow (- x cx) 2) (Math/pow (- y cy) 2))))]
      (is (= 20 (count pts)))
      (is (> (dist (nth pts 0)) (dist (nth pts 1))) "偶数idx(外側)は奇数idx(内側)より中心から遠い")))
  (testing "cloud/wavy も既定点数を返す"
    (is (= 40 (count (gr/cloud-fuki-pts 0 0 100 100))))
    (is (= 32 (count (gr/wavy-fuki-pts 0 0 100 100))))))

(deftest fuki-tail
  (testing "none/未知/nil はしっぽ無し"
    (is (nil? (gr/fukidashi-tail-tri "none" 0 0 100 100)))
    (is (nil? (gr/fukidashi-tail-tri nil 0 0 100 100)))
    (is (nil? (gr/fukidashi-tail-tri "diagonal" 0 0 100 100))))
  (testing "bottom は下辺中央から下に突き出す3頂点"
    (let [tri (gr/fukidashi-tail-tri "bottom" 0 0 100 100)]
      (is (= 3 (count tri)))
      (is (every? #(<= 0 (second %) 100) (take 2 tri)) "根本2頂点は下辺(y=100)上")
      (is (> (second (nth tri 2)) 100) "先端は bounds の外(y2 より下)"))))

(deftest fukidashi-draws-dispatch
  (testing "square は :rect、他は :poly の閉曲線(:mode :loop)"
    (is (= :rect (:op (first (gr/fukidashi-draws {:fukiType "square" :x1 0 :y1 0 :x2 10 :y2 10})))))
    (doseq [ft ["oval" "jagged" "cloud" "wavy"]]
      (is (= :poly (:op (first (gr/fukidashi-draws {:fukiType ft :x1 0 :y1 0 :x2 10 :y2 10})))))
      (is (= :loop (:mode (first (gr/fukidashi-draws {:fukiType ft :x1 0 :y1 0 :x2 10 :y2 10})))))))
  (testing "fukiTail 付きは末尾に :fan の3頂点しっぽが足される"
    (let [draws (gr/fukidashi-draws {:fukiType "oval" :fukiTail "right" :x1 0 :y1 0 :x2 10 :y2 10})]
      (is (= 2 (count draws)))
      (is (= :fan (:mode (last draws))))
      (is (= 3 (count (:points (last draws)))))))
  (testing "fukiTail 無し/none はしっぽ無しで1 op のみ"
    (is (= 1 (count (gr/fukidashi-draws {:fukiType "oval" :x1 0 :y1 0 :x2 10 :y2 10}))))
    (is (= 1 (count (gr/fukidashi-draws {:fukiType "oval" :fukiTail "none" :x1 0 :y1 0 :x2 10 :y2 10}))))))

(deftest stroke-ribbon
  (testing "2点未満は nil"
    (is (nil? (gr/stroke-ribbon-pts [] 2)))
    (is (nil? (gr/stroke-ribbon-pts [{:x 0 :y 0}] 2))))
  (testing "n点 → 2n頂点(L/R交互)、筆圧が高いほど幅が広い"
    (let [thin  (gr/stroke-ribbon-pts [{:x 0 :y 0 :p 0.2} {:x 10 :y 0 :p 0.2}] 10)
          thick (gr/stroke-ribbon-pts [{:x 0 :y 0 :p 1.0} {:x 10 :y 0 :p 1.0}] 10)
          width (fn [pts] (Math/abs (- (second (first pts)) (second (second pts)))))]
      (is (= 4 (count thin)))
      (is (< (width thin) (width thick)) "pressure が低いほど ribbon は細い")))
  (testing "pressure欠損はfloor(0.6)にフォールバックし潰れない"
    (let [pts (gr/stroke-ribbon-pts [{:x 0 :y 0} {:x 10 :y 0}] 10)]
      (is (not= (first pts) (second pts))))))

(deftest tone-patterns
  (testing "dot/line/cross/grad はそれぞれ複数 op、未知は dot にフォールバック"
    (is (pos? (count (gr/tone-draws {:tonePattern "dot" :x1 0 :y1 0 :x2 100 :y2 100}))))
    (is (pos? (count (gr/tone-draws {:tonePattern "line" :x1 0 :y1 0 :x2 100 :y2 100}))))
    (is (pos? (count (gr/tone-draws {:tonePattern "cross" :x1 0 :y1 0 :x2 100 :y2 100}))))
    (is (= 8 (count (gr/tone-draws {:tonePattern "grad" :x1 0 :y1 0 :x2 100 :y2 100}))))
    (is (= (gr/tone-draws {:tonePattern "dot" :x1 0 :y1 0 :x2 100 :y2 100})
           (gr/tone-draws {:tonePattern nil :x1 0 :y1 0 :x2 100 :y2 100}))))
  (testing "cross は line 単独より多い op(縦+横)"
    (is (> (count (gr/tone-cross-draws 0 0 100 100)) (count (gr/tone-line-draws 0 0 100 100)))))
  (testing "grad は下に行くほど alpha が減衰"
    (let [draws (gr/tone-grad-draws 0 0 100 100)
          alphas (mapv #(last (:color %)) draws)]
      (is (apply >= alphas)))))

(deftest youshi-template-geometry
  (testing "mm スペックは genko-embed.ts YOUSHI と一致(B4 257×364、裁ち落とし/基本枠/内枠)"
    (let [{:keys [w-mm h-mm trim outer inner koma]} (get gr/youshi-templates "b4manga")]
      (is (= [257.0 364.0] [w-mm h-mm]))
      (is (= {:x1 18.0 :y1 18.0 :x2 239.0 :y2 346.0} trim))
      (is (= {:x1 25.0 :y1 27.0 :x2 232.0 :y2 337.0} outer))
      (is (= {:x1 53.5 :y1 72.0 :x2 203.5 :y2 292.0} inner))
      (is (nil? koma)))
    (is (= 4 (:koma (get gr/youshi-templates "b4koma"))))
    (is (false? (:draw (get gr/youshi-templates "none")))))
  (testing "world 変換: 紙面は B4 比率を保ち、y 20..700 の帯に x=500 中央寄せ"
    (let [{:keys [x1 y1 x2 y2]} gr/youshi-paper-bounds
          aspect (/ (- x2 x1) (- y2 y1))]
      (is (< (Math/abs (- aspect (/ 257.0 364.0))) 1e-9) "B4 縦横比")
      (is (< (Math/abs (- y1 20.0)) 1e-9))
      (is (< (Math/abs (- y2 700.0)) 1e-9))
      (is (< (Math/abs (- (/ (+ x1 x2) 2.0) 500.0)) 1e-9) "x 中央 = 500")))
  (testing "内枠(safe)はパネルプリセットの分割対象(150×220mm)"
    (let [{:keys [x1 y1 x2 y2]} gr/youshi-safe-bounds]
      (is (< (Math/abs (- (- x2 x1) (* 150.0 gr/youshi-px-per-mm))) 1e-9))
      (is (< (Math/abs (- (- y2 y1) (* 220.0 gr/youshi-px-per-mm))) 1e-9)))
    (is (= [gr/youshi-safe-bounds] (g/panel-preset-rects "1" gr/youshi-safe-bounds 0)))))

(deftest youshi-template-draws
  (let [draws (gr/youshi-draws {:type "b4manga" :visible true})]
    (testing "描画順は embed tessellateYoushi と同じ: 紙面 → 目盛り帯×4 → 目盛り →
              枠×3 → トンボ → センターマーク"
      (is (= [:fan :fan :fan :fan :fan            ; 紙面 + 目盛り帯4辺
              :segments :segments                  ; 目盛り 大/小
              :loop :loop :loop                    ; 裁ち落とし/基本/内枠
              :segments :segments                  ; トンボ 主線/十字
              :segments]                           ; センターマーク
             (mapv :mode draws))))
    (testing "紙面は白系の塗り、枠は水色系の hairline loop"
      (let [paper (first draws)]
        (is (= :rect (:op paper)))
        (is (= gr/paper-color (:color paper)))
        (is (= gr/youshi-paper-bounds (select-keys paper [:x1 :y1 :x2 :y2]))))
      (let [[trim outer inner] (filterv #(= :loop (:mode %)) draws)]
        (is (= gr/youshi-trim-bounds (select-keys trim [:x1 :y1 :x2 :y2])))
        (is (= gr/youshi-frame-bounds (select-keys outer [:x1 :y1 :x2 :y2])))
        (is (= gr/youshi-safe-bounds (select-keys inner [:x1 :y1 :x2 :y2])))
        (is (< (last (:color trim)) (last (:color outer)) (last (:color inner)))
            "枠の α は 裁ち落とし < 基本枠 < 内枠 (0.6/0.8/0.9)")))
    (testing "目盛りは 1mm 刻み・5mm 大目盛り(点ペア = 偶数個)"
      (let [[big small] (filterv #(= :segments (:mode %)) draws)
            ;; 大目盛り: x 0..257 の 5 の倍数 52本 ×2辺 + y 0..364 の 73本 ×2辺
            n-big (* 2 (+ 52 73))]
        (is (= (* 2 n-big) (count (:points big))))
        (is (even? (count (:points small))))))
    (testing "トンボは黒、センターマークは水色"
      (let [segs (filterv #(= :segments (:mode %)) draws)
            [tombo-main tombo-cross center] (drop 2 segs)]
        (is (= [0.0 0.0 0.0 0.5] (:color tombo-main)))
        (is (= [0.0 0.0 0.0 0.4] (:color tombo-cross)))
        (is (= 16 (count (:points tombo-main))) "4隅 × L字2本")
        (is (= 16 (count (:points center))) "内枠4辺中央の十字"))))
  (testing "b4koma は b4manga + 4コマ分割線(3本)"
    (let [manga (gr/youshi-draws {:type "b4manga" :visible true})
          koma (gr/youshi-draws {:type "b4koma" :visible true})]
      (is (= (inc (count manga)) (count koma)))
      (let [div (nth koma (dec (dec (count koma))))] ; センターマークの直前
        (is (= :segments (:mode div)))
        (is (= 6 (count (:points div))) "水平線3本 = 6点"))))
  (testing "none / 非表示 / youshi 無し(nil)・未知 type は空(自由キャンバス)"
    (is (= [] (gr/youshi-draws {:type "none" :visible true})))
    (is (= [] (gr/youshi-draws {:type "b4manga" :visible false})))
    (is (= [] (gr/youshi-draws nil)))
    (is (= [] (gr/youshi-draws {:type "a4wat" :visible true})))))

(deftest node-draws-modes
  (testing "panel は :rect :mode :loop"
    (is (= [:rect :loop] ((juxt :op :mode) (first (gr/node->draws (g/panel-node "n1" {:x1 0 :y1 0 :x2 10 :y2 10})))))))
  (testing "stroke は :mode :strip(筆圧リボン)"
    (let [s (g/wrap-node "s1" "stroke" {:points [{:x 0 :y 0 :p 0.5} {:x 10 :y 10 :p 0.9}] :size 3})]
      (is (= [:poly :strip] ((juxt :op :mode) (first (gr/node->draws s))))))))

(deftest hex->rgba-conversion
  (testing "#rrggbb → [r g b a] 0..1、a=1.0"
    (is (= [1.0 1.0 1.0 1.0] (gr/hex->rgba "#ffffff")))
    (is (= [0.0 0.0 0.0 1.0] (gr/hex->rgba "#000000")))
    (is (= [(/ 224.0 255.0) (/ 96.0 255.0) (/ 96.0 255.0) 1.0] (gr/hex->rgba "#e06060")))))

(deftest prompt-node-draws
  (testing "prompt → 1 op、:rect :mode :loop(既存語彙の再利用、新規プリミティブ無し)"
    (let [n (g/wrap-node "p1" "prompt" {:prompt "x" :_agent "shonen" :x1 0 :y1 0 :x2 100 :y2 80})
          draws (gr/node->draws n)]
      (is (= 1 (count draws)))
      (is (= [:rect :loop] ((juxt :op :mode) (first draws))))
      (is (= {:x1 0 :y1 0 :x2 100 :y2 80} (select-keys (first draws) [:x1 :y1 :x2 :y2])))))
  (testing "色は g/agent-color と同じ SSoT(persona ごとに違う色、他の場所と一致)"
    (let [draw-for (fn [agent] (first (gr/node->draws
                                       (g/wrap-node "p" "prompt" {:prompt "x" :_agent agent
                                                                  :x1 0 :y1 0 :x2 10 :y2 10}))))]
      (is (= (gr/hex->rgba (g/agent-color "shonen")) (:color (draw-for "shonen"))))
      (is (= (gr/hex->rgba (g/agent-color "mecha")) (:color (draw-for "mecha"))))
      (is (not= (:color (draw-for "shonen")) (:color (draw-for "mecha")))
          "persona が違えば色も違う")))
  (testing "bounds(:x1 等)を持たない prompt node(旧 g/prompt-node 由来など)は描かない(nil)"
    (is (nil? (gr/node->draws (g/prompt-node "p2" {:prompt "no bounds"}))))))

(deftest ai-image-node-draws
  (testing "ai-image → 1 op、:op :image、bounds + 安定 key(node id)+ base64 を運ぶ"
    (let [n (g/wrap-node "img1" "ai-image" {:_genImage "ZmFrZQ==" :_genPrompt "a cat" :_agent "shonen"
                                            :x1 5 :y1 6 :x2 105 :y2 86})
          draws (gr/node->draws n)]
      (is (= 1 (count draws)))
      (is (= :image (:op (first draws))))
      (is (= {:x1 5 :y1 6 :x2 105 :y2 86} (select-keys (first draws) [:x1 :y1 :x2 :y2])))
      (is (= "img1" (:image-key (first draws))) "cache key = node id(:_nid)")
      (is (= "ZmFrZQ==" (:image-b64 (first draws))))))
  (testing "base64 が空(理論上起きないが防御的)は draw op を出さない"
    (is (nil? (gr/node->draws (g/wrap-node "img2" "ai-image" {:_genImage "" :x1 0 :y1 0 :x2 10 :y2 10})))))
  (testing "bounds を持たない ai-image node は描かない(nil)"
    (is (nil? (gr/node->draws (g/wrap-node "img3" "ai-image" {:_genImage "ZmFrZQ=="}))))))
