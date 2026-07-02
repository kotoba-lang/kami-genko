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

(deftest node-draws-modes
  (testing "panel は :rect :mode :loop"
    (is (= [:rect :loop] ((juxt :op :mode) (first (gr/node->draws (g/panel-node "n1" {:x1 0 :y1 0 :x2 10 :y2 10})))))))
  (testing "stroke は :mode :strip(筆圧リボン)"
    (let [s (g/wrap-node "s1" "stroke" {:points [{:x 0 :y 0 :p 0.5} {:x 10 :y 10 :p 0.9}] :size 3})]
      (is (= [:poly :strip] ((juxt :op :mode) (first (gr/node->draws s))))))))
