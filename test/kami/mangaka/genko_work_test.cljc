(ns kami.mangaka.genko-work-test
  "公開作品 → 編集できる原稿 doc の投影。

  ここが押さえるのは『開けた』ではなく **『開いた結果が編集できる doc になっている』**
  —— つまり `g/normalize` を通り、`genko-render` が実際に image op を出し、
  `genko-project/doc->page` の入力に戻れること。screenshot は証拠にならない。"
  (:require [clojure.test :refer [deftest is testing]]
            [kami.mangaka.genko :as g]
            [kami.mangaka.genko-render :as gr]
            [kami.mangaka.genko-work :as w]))

(def ^:private work
  {:rkey "gh-arc0-1-v11"
   :title "Ghost Hacker"
   :series "arc0-1 v11"
   :pages [{:n 1 :src "img/gh-arc0-1-v11-p01" :width 1075 :height 1518}
           {:n 2 :src "img/gh-arc0-1-v11-p02" :width 1075 :height 1518}]})

(defn- nodes-of [doc idx] (get-in doc [:pages idx :nodes]))

(defn- underlay
  "`idx` ページの下絵 `ai-image` node。"
  [doc idx]
  (first (filter #(= "ai-image" (:type %)) (nodes-of doc idx))))

;; ── the property this ns exists to hold ──────────────────────────────────────

(deftest work->doc-is-an-editable-doc-test
  (testing "投影の出力はそのまま正規化を通る — 『開けたが編集できない』を作らない"
    (let [doc (w/work->doc work)]
      (is (some? (g/normalize doc)))
      (is (g/valid-doc? doc))
      (is (= 2 (g/page-count doc)))
      (is (= 0 (:activePageIdx doc)))))
  (testing "下絵は locked layer の子で、画素は URL 参照 — base64 を doc に持ち込まない"
    (let [doc (w/work->doc work)
          layer (first (filter #(= "layer" (:type %)) (nodes-of doc 0)))
          img (underlay doc 0)]
      (is (true? (:locked (:data layer))) "下絵 layer は掴めない")
      (is (= (:id layer) (:_parent (:data img))) "画像は下絵 layer の子")
      (is (= "img/gh-arc0-1-v11-p01" (:imageUrl (:data img))))
      (is (= "" (:_genImage (:data img))) "base64 は空 —— 画素は doc の外に在る")))
  (testing "投影した doc を genko-render が実際に image op として描く"
    ;; ここが繋がっていないと「doc は作れたが画面には何も出ない」になる。
    (let [d (first (gr/node->draws (underlay (w/work->doc work) 0)))]
      (is (= :image (:op d)))
      (is (= "img/gh-arc0-1-v11-p01" (:image-url d)))
      (is (= w/default-underlay-opacity (:image-alpha d))))))

;; ── determinism ──────────────────────────────────────────────────────────────

(deftest work->doc-is-deterministic-test
  (testing "同じ work を 2 回開いたら同じ doc —— 乱数 id を使っていない"
    ;; localStorage の autosave と開き直した doc が node id で食い違うと、
    ;; undo/redo と選択が別の木を指すことになる。
    (is (= (w/work->doc work) (w/work->doc work))))
  (testing "id は work rkey とページ番号から決まる"
    (let [doc (w/work->doc work)]
      (is (= "mangaka/gh-arc0-1-v11/genko" (:docId doc)))
      (is (= "gh-arc0-1-v11" (:workRkey doc)))
      (is (= "gh-arc0-1-v11/p01/page" (get-in doc [:pages 0 :id])))
      (is (= "gh-arc0-1-v11/p02/page" (get-in doc [:pages 1 :id])))
      (is (w/underlay-nid? (:id (underlay doc 0))))
      (is (not (w/underlay-nid? "n7"))))))

;; ── page numbering ───────────────────────────────────────────────────────────

(deftest page-numbering-test
  (testing ":n が無ければ並び順、あればその番号。id は 2 桁 0 詰め"
    (let [doc (w/work->doc {:rkey "x" :pages [{:src "a"} {:src "b"}]})]
      (is (= "x/p01/page" (get-in doc [:pages 0 :id])))
      (is (= "x/p02/page" (get-in doc [:pages 1 :id])))
      (is (= "Page 1" (get-in doc [:pages 0 :name]))))
    (let [doc (w/work->doc {:rkey "x" :pages [{:n 11 :src "a"}]})]
      (is (= "x/p11/page" (get-in doc [:pages 0 :id])))
      (is (= "Page 11" (get-in doc [:pages 0 :name]))))))

(deftest empty-work-still-opens-test
  (testing "ページの無い作品も白紙 1 枚として開く —— nil を返して入口を塞がない"
    (let [doc (w/work->doc {:rkey "empty"})]
      (is (some? (g/normalize doc)))
      (is (= 1 (g/page-count doc)))
      (is (empty? (nodes-of doc 0)) "白紙 —— 存在しないページの下絵を捏造しない"))))

;; ── resolving the catalog's URLs ─────────────────────────────────────────────

(deftest resolve-url-test
  (let [api "https://mangaka.itonami.cloud/api"]
    (testing "reader が返す絶対パスは、そのカタログの origin に接ぐ"
      ;; これをやらないと studio(app.itonami.cloud/mangaka/)から
      ;; app.itonami.cloud/img/… を引きに行って 404 になり、下絵は placeholder 枠の
      ;; まま黙って止まる。
      (is (= "https://mangaka.itonami.cloud/img/x" (w/resolve-url api "/img/x"))))
    (testing "既に絶対 URL なら触らない"
      (is (= "https://cdn.test/a.png" (w/resolve-url api "https://cdn.test/a.png")))
      (is (= "http://cdn.test/a.png" (w/resolve-url api "http://cdn.test/a.png"))))
    (testing "相対はそのまま —— ページの位置で解ける"
      (is (= "img/x" (w/resolve-url api "img/x"))))
    (testing "base が相対(same-origin proxy に置いた形)なら何も書き換えない"
      (is (= "/img/x" (w/resolve-url "api" "/img/x")))
      (is (= "/img/x" (w/resolve-url nil "/img/x"))))
    (testing "空は空"
      (is (= "" (w/resolve-url api "")))))
  (testing "origin-of は scheme+host だけを取る"
    (is (= "https://mangaka.itonami.cloud" (w/origin-of "https://mangaka.itonami.cloud/api")))
    (is (= "http://localhost:8734" (w/origin-of "http://localhost:8734/api/works")))
    (is (nil? (w/origin-of "api")))
    (is (nil? (w/origin-of "/api")))
    (is (nil? (w/origin-of nil)))))

(deftest with-base-test
  (let [api "https://mangaka.itonami.cloud/api"]
    (testing "表紙とページは同じ規則で解ける —— 片方だけ直すと症状が読み解けない"
      (let [w' (w/with-base {:rkey "r" :cover "/img/r-p01"
                             :pages [{:n 1 :src "/img/r-p01"} {:n 2 :src "/img/r-p02"}]}
                            api)]
        (is (= "https://mangaka.itonami.cloud/img/r-p01" (:cover w')))
        (is (= ["https://mangaka.itonami.cloud/img/r-p01"
                "https://mangaka.itonami.cloud/img/r-p02"]
               (mapv :src (:pages w'))))))
    (testing "表紙もページも無い行を壊さない(一覧 API は cover だけを返す)"
      (is (= {:rkey "r"} (w/with-base {:rkey "r"} api))))
    (testing "解決した work を work->doc に通すと下絵が絶対 URL になる"
      (let [doc (w/work->doc (w/with-base {:rkey "r" :pages [{:n 1 :src "/img/r-p01"}]} api))
            img (first (filter #(= "ai-image" (:type %)) (get-in doc [:pages 0 :nodes])))]
        (is (= "https://mangaka.itonami.cloud/img/r-p01" (:imageUrl (:data img))))))))

(deftest doc-name-test
  (testing "title と series は繋ぐ。どちらも無ければ rkey"
    (is (= "Ghost Hacker — arc0-1 v11" (w/doc-name work)))
    (is (= "Ghost Hacker" (w/doc-name {:rkey "r" :title "Ghost Hacker"})))
    (is (= "r" (w/doc-name {:rkey "r"})))
    (is (= "r" (w/doc-name {:rkey "r" :title "" :series "  "})))))

;; ── underlay geometry ────────────────────────────────────────────────────────

(deftest underlay-rect-preserves-aspect-test
  (let [b {:x1 0.0 :y1 0.0 :x2 200.0 :y2 100.0}
        ratio (fn [{:keys [x1 y1 x2 y2]}] (/ (- x2 x1) (- y2 y1)))]
    (testing "比を保つ —— 枠に合わせて引き伸ばさない"
      ;; 引き伸ばすと下絵の上に引いた線が元の絵とずれ、ずれた理由が画面から見えない。
      (is (< (Math/abs (- 2.0 (ratio (w/underlay-rect b 2.0)))) 1e-9))
      (is (< (Math/abs (- 0.5 (ratio (w/underlay-rect b 0.5)))) 1e-9)))
    (testing "枠の中に収まり、中央に置かれる"
      (let [{:keys [x1 y1 x2 y2] :as r} (w/underlay-rect b 0.5)]
        (is (and (>= x1 (:x1 b)) (<= x2 (:x2 b)) (>= y1 (:y1 b)) (<= y2 (:y2 b)))
            "枠からはみ出さない")
        (is (< (Math/abs (- 100.0 (/ (+ x1 x2) 2.0))) 1e-9) "水平中央")
        (is (< (Math/abs (- 50.0 (/ (+ y1 y2) 2.0))) 1e-9) "垂直中央")
        (is (or (< (Math/abs (- (- y2 y1) 100.0)) 1e-9)
                (< (Math/abs (- (- x2 x1) 200.0)) 1e-9))
            "どちらかの辺は枠に接する —— 最大に収める")))
    (testing "aspect が不明なら B5 の既定値"
      (is (= (w/underlay-rect b nil) (w/underlay-rect b w/b5-aspect)))
      (is (= (w/underlay-rect b 0) (w/underlay-rect b w/b5-aspect))))))

(deftest aspect-of-test
  (testing "実測ページ 1075×1518 は B5(182/257)と一致する"
    (is (< (Math/abs (- (w/aspect-of {:width 1075 :height 1518}) w/b5-aspect)) 1e-4)))
  (testing "寸法が欠けていたら nil —— 既定値に任せる。0 で割らない"
    (is (nil? (w/aspect-of {:width 1075})))
    (is (nil? (w/aspect-of {:width 1075 :height 0})))
    (is (nil? (w/aspect-of {})))))

(deftest underlay-opacity-is-overridable-test
  (testing "濃さは呼び手が決められる —— 既定は自分の線が見える薄さ"
    (let [d (first (gr/node->draws
                    (underlay (w/work->doc work {:opacity 0.8}) 0)))]
      (is (= 0.8 (:image-alpha d))))))
