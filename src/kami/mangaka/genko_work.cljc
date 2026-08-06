(ns kami.mangaka.genko-work
  "公開済みの manga 作品 → **編集できる原稿 doc**。

  `genko-project` は storyboard(コマ矩形・吹き出し・SFX が構造として在るもの)を doc に
  投影する。こちらは**それが手元に無い**場合の入口で、あるのは平らな公開ページ画像
  1 枚だけという状況を扱う。

  ── なぜ「平らな画像しか無い」が既定なのか（実測 2026-08-06）──────────────────
  `gh-arc0-1-v11` の D1 の row には `blobKey` と `jumpQa` しか無く、panel row は
  1 件も無い。storyboard は生成のときメモリに在って、公開物には残っていない。
  だから復元できない storyboard を待つのでも、捏造するのでもなく、**公開ページを
  下絵として敷いて、コマ割り・ふきだし・トーン・文字を人が組む**という形にする。
  これは劣化ではなく、ネーム→下書き→ペン入れという実際の順序に一致している。

  ── 何が編集の結果として残るか ────────────────────────────────────────────
  下絵は locked な layer の下の `ai-image`(URL 参照)で、`:nodes` の 1 つに過ぎない。
  人が上に描いた panel / fukidashi / text / tone も同じ `:nodes` に入るので、
  `genko-project/doc->page` がそのまま storyboard EDN に戻せる —— つまり
  **ここで組んだ原稿は mangaka のパイプラインの入力に戻る**。下絵を目玉アイコンで
  隠せば、残るのは人が組んだ原稿だけになる。

  純 cljc・id は入力から決まる(乱数を使わない)。同じ work を 2 回開いても同じ doc に
  なるので、localStorage の doc と開き直した doc が node id で食い違わない。"
  (:require [clojure.string :as str]
            [kami.mangaka.genko :as g]))

(def b5-aspect
  "仕上がり B5(182×257mm)の 幅/高さ。mangaka のパイプラインが実際に出している比で、
  実測でもある: `gh-arc0-1-v11-p01` は 1075×1518 = 0.70817、182/257 = 0.70817。

  ページの実寸が分からないときの既定値としてだけ使う —— 分かるときは
  `:width`/`:height` を渡すこと。既定値は「たぶんこう」であって測定ではない。"
  (/ 182.0 257.0))

(def default-underlay-opacity
  "下絵の既定の濃さ。1.0 だと自分の線が見えず、薄すぎると何を写しているか分からない。"
  0.45)

(defn aspect-of
  "`{:width :height}` → 幅/高さ。どちらか欠けるか 0 なら nil(既定値に任せる)。"
  [{:keys [width height]}]
  (let [w (when (number? width) (double width))
        h (when (number? height) (double height))]
    (when (and w h (pos? w) (pos? h)) (/ w h))))

(defn underlay-rect
  "`bounds`({:x1 :y1 :x2 :y2})の中に `aspect`(幅/高さ)の矩形を**中央寄せで最大**に
  収めた world 矩形。aspect が nil なら `b5-aspect`。

  枠に合わせて引き伸ばさないのは、公開ページの比と原稿用紙の枠の比が違うから
  (B4 の裁ち落とし枠は 221×328mm = 0.674、公開ページは 0.708)。引き伸ばすと
  下絵の上に引いた線が元の絵とずれ、しかもずれた理由が見えない。"
  [{:keys [x1 y1 x2 y2]} aspect]
  (let [bw (- x2 x1)
        bh (- y2 y1)
        a (if (and (number? aspect) (pos? (double aspect))) (double aspect) b5-aspect)
        w (min bw (* bh a))
        h (/ w a)
        cx (+ x1 (/ bw 2.0))
        cy (+ y1 (/ bh 2.0))]
    {:x1 (- cx (/ w 2.0)) :y1 (- cy (/ h 2.0))
     :x2 (+ cx (/ w 2.0)) :y2 (+ cy (/ h 2.0))}))

(defn origin-of
  "絶対 URL → scheme + host(末尾 `/` 無し)。相対や空なら nil。

  `js/URL` を使わないのは、この ns が純 cljc だから —— JVM のテストから同じ関数を
  呼べることが、下の `resolve-url` を実際に検査できる条件になっている。"
  [base]
  (let [b (str base)]
    (when-let [m (re-find #"^([a-zA-Z][a-zA-Z0-9+.-]*://[^/]+)" b)]
      (second m))))

(defn resolve-url
  "カタログが返す `src` を、そのカタログの位置を基準に解決する。

  読者 API(`mangaka-reader`)が返す `src` は `/img/<key>` という**絶対パス**で、
  これは reader 自身の origin での話。studio を別 origin(`app.itonami.cloud/mangaka/`)
  に置くと、そのまま使えば `app.itonami.cloud/img/<key>` を指してしまい 404 になる。
  絵が出ないのではなく **placeholder 枠のまま黙って止まる**ので、原因が画面から
  見えない。

  規則は 3 つだけ:

  - 既に絶対 URL(`https://…`)なら触らない。
  - `base` が絶対で `src` が `/` 始まりなら、base の origin に接ぐ。
  - それ以外(base が相対 = same-origin proxy に置いた形)は触らない —— その配置
    では `/img/…` も `img/…` も、ページと同じ origin を指すのが正しい。"
  [base src]
  (let [s (str src)]
    (cond
      (str/blank? s) s
      (re-find #"^[a-zA-Z][a-zA-Z0-9+.-]*://" s) s
      (str/starts-with? s "/") (if-let [o (origin-of base)] (str o s) s)
      :else s)))

(defn with-base
  "work(カタログ 1 件、一覧の行でも詳細でも)の `:cover` と `:pages[].src` を
  `base` 基準に解決したもの。表紙と下絵は同じ規則で解けなければならない ——
  一覧では表紙が出るのに開くと下絵が出ない(あるいは逆)が、一番読み解きにくい。"
  [work base]
  (cond-> work
    (:cover work) (update :cover #(resolve-url base %))
    (seq (:pages work)) (update :pages (fn [ps] (mapv #(update % :src (partial resolve-url base)) ps)))))

(defn- pad2 [n]
  (let [s (str n)] (if (< (count s) 2) (str "0" s) s)))

(defn- page-number [{:keys [n]} idx]
  (if (integer? n) n (inc idx)))

(defn page-nodes
  "1 ページ分の node: 下絵 layer と、その下の `ai-image`(URL 参照)。

  layer は `:locked true`。下絵はドラッグして動かすものではないし、`node-tree` の
  目玉アイコンで隠せるので、掴めることに利点が無い。"
  [rkey page idx {:keys [opacity]}]
  (let [n (page-number page idx)
        pid (str rkey "/p" (pad2 n))
        lid (str pid "/layer/shitagaki")]
    [(g/wrap-node lid "layer"
                  {:layerName (str "下絵 p" (pad2 n))
                   :blendMode "normal" :opacity 1.0 :locked true :zIndex 0}
                  "")
     (g/wrap-node (str pid "/shitagaki") "ai-image"
                  (merge (underlay-rect g/youshi-trim-bounds (aspect-of page))
                         {:imageUrl (str (:src page))
                          :opacity (let [o opacity]
                                     (if (number? o) (double o) default-underlay-opacity))
                          :blendMode "normal"
                          :mime (or (:mime page) "image/png")
                          ;; 画素は URL から来る。空文字なのは「base64 は無い」を
                          ;; 型で言うため(genko-render は 2 経路のどちらかを見る)。
                          :_genImage ""
                          :_genPrompt ""})
                  lid)]))

(defn doc-name
  "doc の表示名。title と series の両方があるなら繋ぐ(series だけだと何の作品か
  分からず、title だけだと版が分からない)。"
  [{:keys [title series rkey]}]
  (let [parts (remove str/blank? [(some-> title str) (some-> series str)])]
    (if (seq parts) (str/join " — " parts) (str rkey))))

(defn work->doc
  "公開作品(reader の work JSON 形)→ 編集できる genko doc。

      {:rkey \"gh-arc0-1-v11\" :title \"Ghost Hacker\" :series \"arc0-1 v11\"
       :pages [{:n 1 :src \"img/gh-arc0-1-v11-p01\" :width 1075 :height 1518} …]}

  `:src` は `imageUrl` に入る。カタログが別 origin に在るなら、呼び手が先に
  `with-base` を通しておくこと(`:opts` ではなく別関数なのは、一覧の `:cover` も
  同じ解決を要るため —— 片方だけ通すと表紙は出るのに下絵が出ない)。相対のままの
  `src` は相対のまま入る: ブラウザがページの位置を基準に解決するので、同じ doc が
  別の mount point でも動く。

  `pages` が空の作品も doc は返す —— 白紙 1 枚。`:pages` が空の doc は
  `g/normalize` が弾くので、「開けたが編集できない」より「白紙が開く」を選ぶ。"
  ([work] (work->doc work nil))
  ([{:keys [rkey pages] :as work} opts]
   (let [rkey (str rkey)
         pages (vec pages)]
     {:name (doc-name work)
      :docId (str "mangaka/" rkey "/genko")
      :workRkey rkey
      :activePageIdx 0
      :pages (if (seq pages)
               (vec (map-indexed
                     (fn [idx p]
                       (let [n (page-number p idx)]
                         (g/page (str rkey "/p" (pad2 n) "/page")
                                 (str "Page " n)
                                 (g/youshi (str rkey "/p" (pad2 n) "/youshi") "b4manga" true)
                                 (page-nodes rkey p idx opts))))
                     pages))
               [(g/page (str rkey "/p01/page") "Page 1"
                        (g/youshi (str rkey "/p01/youshi") "b4manga" true))])})))

(defn underlay-nid?
  "その node id が `work->doc` の作った下絵か。UI が「下絵だけ隠す/濃さを変える」を
  実装するときの述語 —— 名前の一致で判断させない。"
  [nid]
  (and (string? nid) (str/ends-with? nid "/shitagaki")))
