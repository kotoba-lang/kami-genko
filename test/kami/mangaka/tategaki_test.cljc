(ns kami.mangaka.tategaki-test
  (:require [clojure.test :refer [deftest is testing]]
            [kami.mangaka.tategaki :as t]))

(def box {:x1 0 :y1 0 :x2 200 :y2 200})

(deftest writing-mode-by-locale
  (testing "日本語だけが既定で縦"
    (is (= :vertical-rtl   (t/writing-mode :ja)))
    (is (= :horizontal-lr  (t/writing-mode :en)))
    (is (= :horizontal-lr  (t/writing-mode :zh)))   ; できる≠既定にする
    (is (= :horizontal-rtl (t/writing-mode :ar))))
  (testing "明示指定が勝つ"
    (is (= :horizontal-lr (t/writing-mode :ja "horizontal")))
    (is (= :vertical-rtl  (t/writing-mode :en "vertical-rtl")))))

(deftest long-vowel-rotates-only-in-vertical
  (testing "縦なら長音符は回る（これを落とすと横に寝る）"
    (let [g (:glyphs (t/layout ["オーリオ"] box {:locale :ja}))]
      (is (= [false true false false] (mapv :rotate? g)))))
  (testing "横なら回さない"
    (let [g (:glyphs (t/layout ["オーリオ"] box {:locale :ja :writing-mode "horizontal"}))]
      (is (every? (complement :rotate?) g)))))

(deftest vertical-columns-go-right-to-left
  (let [g (:glyphs (t/layout ["あ" "い"] box {:locale :ja}))]
    (is (> (:x (first g)) (:x (last g))) "1行目がいちばん右")))

(deftest horizontal-rtl-reverses-characters
  (let [lr (:glyphs (t/layout ["ab"] box {:locale :en}))
        rt (:glyphs (t/layout ["ab"] box {:locale :ar}))]
    (is (< (:x (first lr)) (:x (last lr))))
    (is (> (:x (first rt)) (:x (last rt))))))

(deftest box-size-swaps-with-direction
  (let [[vw vh] (t/box-size ["あいうえおかきくけこ" "さしす"] {:locale :ja})
        [hw hh] (t/box-size ["あいうえおかきくけこ" "さしす"] {:locale :en})]
    (is (> vh vw) "縦組みは縦長")
    (is (> hw hh) "横組みは横長")))

(deftest small-kana-flagged-not-rotated
  (let [g (:glyphs (t/layout ["きゃっ"] box {:locale :ja}))]
    (is (= [false true true] (mapv :small? g)))
    (is (every? (complement :rotate?) g))))
