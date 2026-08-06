(ns kami.mangaka.genko-view
  "genko editor chrome as pure `.cljc` hiccup on the kotoba-lang design system
  (skill `kotoba-uiux`, ADR-2607122200): toolbar, node tree and the editor
  frame, built from `kotoba-ui.core` alone.

  ── why this ns exists ──────────────────────────────────────────────────────
  The chrome used to live inside `kami.mangaka.genko-ui` as reagent components
  carrying 29 inline `:style` maps and 12 raw hex literals (`#111` toolbar,
  `#e06090` active tool, `#faf7f0` sidebar, `#cfe3ff` selection …) — a private
  palette that matched nothing else in the workspace and had no dark
  appearance. Two things were wrong with that, and this ns fixes both:

  1. **Colors and type are decisions the design system already made.** Every
     value here is a `--hig-*` / `--liquid-glass-*` token or a component from
     `kotoba-ui.core`. The only hex in the whole editor is the theme accent in
     `kami.mangaka.genko-theme`, which is where rule 5 says a hex belongs.
  2. **A view holding closures is neither testable nor SSR-renderable.** These
     fns take a plain editor db and return plain hiccup, so `->html` can render
     them (and a JVM test can assert on them) while reagent renders the exact
     same data in the browser — shitsuke's dual-render contract, rule 8.

  ── how interaction gets back out ────────────────────────────────────────────
  Pure hiccup cannot carry `:on-click`. It does not need to: `act` is the
  design system's portable interaction attribute (see `shitsuke.components`'
  ns docstring). Every interactive element here emits `data-act`, and the cljs
  host installs ONE delegated listener per event type on the editor root,
  which walks up from the event target to the nearest `[data-act]` and turns
  it into an action vector. `kami.mangaka.genko-ui` holds those listeners;
  `act->action` / `change-act->action` below are the vocabulary they share.

  A pleasant consequence: the old code needed `.stopPropagation` on the
  per-node visibility toggle so it would not also select the row. With
  delegation the nearest `data-act` ancestor wins on its own, so the toggle
  never reaches the row's act and the special case disappears."
  (:require [clojure.string :as str]
            [jp-go-dds.core :as dds]
            [kami.mangaka.genko :as g]
            [kami.mangaka.genko-render :as gr]
            [kotoba.editor :as ed]))

;; ── editor db ────────────────────────────────────────────────────────────────

(defn initial-db
  "editor db 初期値。doc 省略時は空 doc \"Mangaka\"。

  Lives here rather than in `genko-ui` (which re-exports it) because it is
  pure and `.cljc`: the SSR page generator needs an initial db to render the
  chrome from, and so do these views' tests."
  ([] (initial-db (g/new-doc "Mangaka" {:page-id (g/gen-nid) :youshi-id (g/gen-nid)})))
  ([doc]
   {:doc doc :undo-stack [] :redo-stack []
    :tool "draw" :selection #{} :draft nil
    :fuki-type "oval" :fuki-tail "bottom" :tone-pattern "dot"
    :viewport gr/default-viewport :pan-from nil :kotoba-status nil
    ;; ── studio(作品を開く)—— 既定は従来どおり editor ────────────────────────
    ;; `:screen` の既定を `:library` にしない。作品カタログを持たない host
    ;; (kami-genko 自身の公開面)では空の一覧が挟まるだけで、編集を始めるのに
    ;; クリックが 1 回増える。カタログを持つ host(mangaka studio)が起動時に
    ;; `[:show-library]` を打つ。
    :screen :editor
    :works nil
    :works-status nil}))

;; ── db accessors (pure; shared with genko-ui) ────────────────────────────────

(defn active-idx [db] (get-in db [:doc :activePageIdx] 0))
(defn active-nodes [db] (get-in db [:doc :pages (active-idx db) :nodes] []))
(defn active-youshi [db] (get-in db [:doc :pages (active-idx db) :youshi]))

;; ── act vocabulary ───────────────────────────────────────────────────────────
;; An act is `"<group>"` or `"<group>/<arg>"`. The group of a no-argument act
;; IS its action op, so the table below stays a table instead of a case
;; expression that has to be edited in two places.

(def arg-acts
  "Click acts whose trailing path segment is the action's single argument.
  `\"tool/draw\"` -> `[:set-tool \"draw\"]`, `\"select-node/n7\"` ->
  `[:select-node \"n7\"]`."
  {"tool"        :set-tool
   "select-node" :select-node
   "toggle-vis"  :toggle-vis
   ;; ページ送り。値は 0 起点の index を 10 進で書いた文字列。
   "set-page"    :set-page})

(def nullary-acts
  "Click acts that are their own action op and take no argument."
  #{:undo :redo :reset-viewport :delete-selected :toggle-youshi-vis
    :add-page :show-library :show-editor})

(def host-acts
  "Click act GROUPS that are NOT editor actions: they are effects the host owns
  (a download, a file picker, a network round-trip). `act->action` returns nil
  for these on purpose — `genko-ui`'s handler dispatches them itself, so a doc
  action and a side effect can never be confused for one another.

  These are matched by **group** (the part before the first `/`), not by the
  whole act, because `open-work/<rkey>` carries an argument and is still a host
  effect. Matching the whole string made every argument-carrying host act look
  unknown and silently do nothing."
  #{"export" "import" "cloud-save" "cloud-load"
    "storyboard" "open-work" "new-doc" "reload-works"})

(def change-acts
  "`change` acts (the `<select>`s) -> action op. The selected value is the
  action's argument, so these cannot use the click vocabulary above."
  {"youshi-type"  :set-youshi-type
   "panel-preset" :apply-preset
   "fuki-type"    :set-fuki-type
   "fuki-tail"    :set-fuki-tail
   "tone-pattern" :set-tone-pattern
   "underlay"     :set-underlay-opacity})

(defn act->action
  "`data-act` string -> editor action vector, or nil when the act is not a
  click action (unknown, or one of `host-acts`)."
  [act]
  (when (seq act)
    (let [[group arg] (str/split act #"/" 2)]
      (if-let [op (get arg-acts group)]
        (when (seq arg) [op arg])
        (let [op (keyword group)]
          (when (contains? nullary-acts op) [op]))))))

(defn host-act
  "`data-act` string -> `[group arg]` when the act is a host effect, else nil.
  The host handler switches on `group` and gets `arg` already split, so the
  argument-carrying host acts (`open-work/<rkey>`) need no second parser."
  [act]
  (when (seq act)
    (let [[group arg] (str/split act #"/" 2)]
      (when (contains? host-acts group) [group arg]))))

(defn change-act->action
  "`data-act` string + the control's new value -> editor action vector, or nil.
  An empty value is nil, not an action: the コマ割り menu returns to its
  placeholder row after each use and that reset must not re-apply the preset."
  [act value]
  (when-let [op (get change-acts act)]
    (when (seq value) [op value])))

;; ── toolbar ──────────────────────────────────────────────────────────────────

(def tool-names
  "Tool ids, in toolbar order. These are `:tool` db values and half the click
  act vocabulary, so they stay ASCII whatever the labels say."
  ["select" "draw" "panel" "fukidashi" "tone" "text"])

(def tool-labels
  "Tool id -> tab label. Japanese, because the rest of the chrome already is
  (原稿用紙 / コマ割り / 吹き出し種別) — bare English ids next to those read as
  untranslated strings rather than as a deliberate choice."
  {"select"    "選択"
   "draw"      "ペン"
   "panel"     "コマ"
   "fukidashi" "ふきだし"
   "tone"      "トーン"
   "text"      "文字"})

(def youshi-options
  "原稿用紙 template choices — the same ids and wording the kami-engine-sdk
  genko-embed `youshiType` select uses, so a doc authored in either surface
  reads the same."
  [["b4manga" "B4 漫画"] ["b4koma" "4コマ"] ["none" "Free"]])

(def panel-preset-options
  "コマ割り presets. The leading empty row is the menu's own label: picking a
  preset applies it and the menu snaps back here, so it never displays a
  stale 'current' preset that is not a property of the page."
  [["" "コマ割り…"] ["1" "1"] ["2h" "2h"] ["2v" "2v"] ["3h" "3h"] ["2x2" "2x2"]])

(def underlay-options
  "下絵の濃さ。トレースするには紙より薄くないと自分の線が見えず、薄すぎると何を
  写しているのか分からない。0% は「消す」ではなく「今は見ない」— node は残る。"
  [["0" "下絵 0%"] ["0.25" "25%"] ["0.45" "45%"] ["0.7" "70%"] ["1" "100%"]])

(defn underlay-nodes
  "active page の下絵 node(`ai-image` で `:imageUrl` を持つもの)。`genko-work` が
  敷いたものだけでなく、URL 参照の画像すべてを指す — 濃さを変えられるのは
  「画素が doc の外に在る画像」という性質であって、誰が作ったかではない。"
  [db]
  (filterv #(and (= "ai-image" (g/type-of %))
                 (seq (str (:imageUrl (g/node-data %)))))
           (active-nodes db)))

(defn underlay-opacity
  "下絵の現在の濃さ。複数あるときは先頭のもの(select は 1 つしか出さない)。"
  [db]
  (let [o (some-> (first (underlay-nodes db)) g/node-data :opacity)]
    (if (number? o) (double o) 1.0)))

(defn- opacity-select-value
  "`underlay-options` のどの row を選択状態にするか。実数を文字列キーに丸めるので、
  近いものを選ぶ(0.45 が既定だが doc は任意の値を持てる)。"
  [opacity]
  (->> underlay-options
       (map first)
       (sort-by #(Math/abs (- (double opacity) (double (parse-double %)))))
       first))

(defn page-nav-view
  "ページ送り。`:activePageIdx` はずっと doc モデルに在ったが UI からは動かせず、
  複数ページの原稿は開けても 1 枚目しか見られなかった。manga は 1 枚で終わらない
  ので、これが無いと『作品を開く』が成立しない。

  番号は 1 起点で見せ、act は 0 起点で渡す(モデルの index をそのまま)。"
  [db]
  (let [n (g/page-count (:doc db))
        i (active-idx db)]
    [:div.genko-pages {:role "group" :aria-label "ページ"}
     (dds/button "◀" {:type :outline :size "sm" :disabled (<= i 0)
                      :attrs {:data-act (str "set-page/" (dec i))
                              :title "前のページ"
                              :aria-label "前のページ"}})
     (if (<= n 1)
       [:span.genko-readout (str "1 / " (max n 1))]
       (dds/select {:size "sm" :value (str i)
                    :attrs {:data-act "set-page" :aria-label "ページ"}}
                   (mapv (fn [k] [(str k) (str (inc k) " / " n)]) (range n))))
     (dds/button "▶" {:type :outline :size "sm" :disabled (>= i (dec n))
                      :attrs {:data-act (str "set-page/" (inc i))
                              :title "次のページ"
                              :aria-label "次のページ"}})
     (dds/button "＋" {:type :outline :size "sm"
                       :attrs {:data-act "add-page"
                               :title "ページを足す"
                               :aria-label "ページを足す"}})]))

(defn- sync-status
  "The cloud-sync indicator. Status colors come from the HIG system palette —
  the design system's answer to exactly this need — rather than the invented
  `#e06060`/`#8fdc8f` pair the inline-styled version carried."
  [status]
  (when status
    (let [error? (vector? status)]
      ;; `badge` takes only :class, so the live-region semantics live on a bare
      ;; wrapper rather than being passed in and silently dropped. Saving is
      ;; asynchronous and its result appears without the user acting, which is
      ;; precisely what aria-live is for.
      [:span {:role "status" :aria-live "polite"
              :title (when error? (str (second status)))}
       (dds/chip-label
        (case status
          :saving "…" :saved "☁✓" :loading "…" :loaded "☁✓"
          (str "☁✗ " (second status)))
        {:color (if error? "red" "blue")})])))

(defn tools-view
  "The tool palette: a segmented control over `tool-names`.

  It sits in the toolbar. Moving it to the sidebar was tried and reverted —
  `tab-bar` is a horizontal segmented control that does not wrap, so in a
  260px column the six Japanese labels compressed to one character per line
  and the last tab was clipped off the edge. A vertical tool palette needs a
  vertical component, and the design system does not have one today (noted as
  a gap rather than worked around with app CSS)."
  [db]
  (into [:div.genko-tools {:role "group" :aria-label "ツール"}]
        (for [t tool-names]
          (dds/button (get tool-labels t t)
                      {;; DADS expresses "this one is current" through the
                       ;; button's own type rather than a separate segmented
                       ;; control: filled is the active tool, outline the rest.
                       :type (if (= t (:tool db)) :solid-fill :outline)
                       :size "sm"
                       :attrs {:data-act (str "tool/" t)
                               :aria-pressed (if (= t (:tool db)) "true" "false")}}))))

(defn toolbar-view
  "Editor toolbar — document-scope controls only. `db` is the editor db; opts
  `{:sync?}` shows the cloud save/load pair (the host only has those when its
  adapter carries a `:sync`).

  No title: the page's own `<title>` already names the document, and a label
  in here only pushed the controls onto a second row."
  ([db] (toolbar-view db nil))
  ([db {:keys [sync? library?]}]
   (let [tool (:tool db)
         youshi (active-youshi db)
         zoom (get-in db [:viewport :zoom] 1.0)
         underlays (underlay-nodes db)]
     [:header.genko-toolbar
      (remove
       nil?
       [;; 作品一覧へ戻る道。カタログを持つ host のときだけ出す — 一覧が無い
        ;; surface にこのボタンがあると、押した先が空になる。
        (when library?
          (dds/button "☰ 作品" {:type :outline :size "sm"
                               :attrs {:data-act "show-library"
                                       :title "作品一覧へ戻る"}}))
        (page-nav-view db)
        (tools-view db)
        ;; None of these dropdowns has a visible <label> (a toolbar has no room
        ;; for six of them), so each states its own name through :attrs. That
        ;; opt had to be added to shitsuke.components/select first — it used to
        ;; drop aria-label without a word, which is how a control ends up
        ;; announced as nothing while the code reads as accessible.
        (dds/select {:size "sm" :value (or (:type youshi) "none")
                     :attrs {:data-act "youshi-type" :aria-label "原稿用紙"}}
                    youshi-options)
        (dds/select {:size "sm" :value ""
                     :attrs {:data-act "panel-preset" :aria-label "コマ割り"}}
                    panel-preset-options)
        ;; 下絵の濃さ。下絵が無い page では出さない(効かない control を並べない)。
        (when (seq underlays)
          (dds/select {:size "sm" :value (opacity-select-value (underlay-opacity db))
                       :attrs {:data-act "underlay" :aria-label "下絵の濃さ"}}
                      underlay-options))
        ;; The three tool-specific menus appear only for the tool they belong
        ;; to — the toolbar stays one row instead of carrying six dead controls.
        (when (= "fukidashi" tool)
          (dds/select {:size "sm" :value (:fuki-type db)
                       :attrs {:data-act "fuki-type" :aria-label "吹き出し種別"}}
                      (mapv (fn [x] [x x]) (sort g/fukidashi-types))))
        (when (= "fukidashi" tool)
          (dds/select {:size "sm" :value (:fuki-tail db)
                       :attrs {:data-act "fuki-tail" :aria-label "しっぽの向き"}}
                      (mapv (fn [x] [x x]) (sort g/fukidashi-tails))))
        (when (= "tone" tool)
          (dds/select {:size "sm" :value (:tone-pattern db)
                       :attrs {:data-act "tone-pattern" :aria-label "トーンパターン"}}
                      (mapv (fn [x] [x x]) (sort g/tone-patterns))))
        [:span.genko-spacer]
        (dds/button "⇩ export" {:type :outline :size "sm" :attrs {:data-act "export" :title "JSON を書き出す"}})
        (dds/button "⇧ import" {:type :outline :size "sm" :attrs {:data-act "import" :title "JSON を読み込む"}})
        ;; 組んだ原稿を storyboard EDN に戻す。これが在るから、ここでの作業は
        ;; 眺めて終わりではなく mangaka のパイプラインの入力になる
        ;; (genko-project/doc->page が panel 矩形・ふきだし・SFX を読み出す)。
        (dds/button "⇩ storyboard" {:type :outline :size "sm"
                                    :attrs {:data-act "storyboard"
                                            :title "コマ・ふきだし・文字を storyboard EDN として書き出す"}})
        (when sync? (dds/button "☁ save" {:type :outline :size "sm" :attrs {:data-act "cloud-save" :title "kotobase.net に保存"}}))
        (when sync? (dds/button "☁ load" {:type :outline :size "sm" :attrs {:data-act "cloud-load" :title "kotobase.net から復元"}}))
        (when sync? (sync-status (:kotoba-status db)))
        (dds/button "↶ undo" {:type :outline :size "sm" :disabled (not (ed/can-undo? db)) :attrs {:data-act "undo"}})
        (dds/button "↷ redo" {:type :outline :size "sm" :disabled (not (ed/can-redo? db)) :attrs {:data-act "redo"}})
        (dds/button "⌂ view" {:type :outline :size "sm"
                              :attrs {:data-act "reset-viewport"
                                      :title "空白ドラッグ=pan、ホイール=zoom"}})
        [:span.genko-readout
         (str (count (active-nodes db)) " nodes · "
              (Math/round (double (* 100 zoom))) "%")]])])))

;; ── node tree ────────────────────────────────────────────────────────────────
;; DADS has no list component with a trailing slot, so the tree is a semantic
;; <ul>/<li>. That is not a downgrade: a node tree IS a list, `role="listitem"`
;; came from a div pretending to be one, and the markup is now what a screen
;; reader wants without being told.

(defn- eye-button
  "Visibility toggle. `xs` because it sits inside a row, not beside it."
  [act vis?]
  (dds/button (if vis? "👁" "🚫")
              {:type :text :size "xs"
               :attrs {:data-act act
                       :title (if vis? "隠す" "表示する")
                       :aria-pressed (if vis? "true" "false")}}))

(defn- node-row
  "One tree row. `:draggable` + `data-nid` are the reorder handles the host's
  delegated drag listeners read; `aria-selected` says a row is selected to a
  screen reader, not only to the eye.

  `draggable` is the string, not the boolean: it is an ENUMERATED attribute, so
  a bare `draggable` is an invalid value and falls back to `auto` — which for a
  list item means not draggable at all. reagent/React happens to serialise
  `true` as `\"true\"`, but the SSR path renders the bare form, and the
  dual-render contract means both must be right."
  [{:keys [nid nm vis]} selected?]
  [:li.genko-node {:draggable "true"
                   :data-nid nid
                   :data-act (str "select-node/" nid)
                   :aria-selected (if selected? "true" "false")}
   [:span {:class (when-not vis "genko-node--hidden")} nm]
   (eye-button (str "toggle-vis/" nid) vis)])

(defn tree-view
  "Node tree: 原稿用紙 first (it is a page-level node, not one of `:nodes`),
  then the drawn nodes in paint order."
  [db]
  (let [rows (g/all-nodes (active-nodes db))
        youshi (active-youshi db)
        selection (:selection db)]
    (into [:ul.genko-tree {:aria-label "ノード"}]
          (remove
           nil?
           (cons
            (when youshi
              (let [vis? (not (false? (:visible youshi)))]
                [:li.genko-node
                 [:span {:class (when-not vis? "genko-node--hidden")}
                  (str "genkouyoushi (" (or (:type youshi) "none") ")")]
                 (eye-button "toggle-youshi-vis" vis?)]))
            (map (fn [row] (node-row row (contains? selection (:nid row)))) rows))))))

(defn sidebar-view
  "The side panel. One child today, but it is the panel — not the tree — that
  the frame places, and saying so keeps the frame from having to know what is
  inside it."
  [db]
  [:aside.genko-sidebar (tree-view db)])

;; ── canvas + frame ───────────────────────────────────────────────────────────

(defn canvas-view
  "The drawing surface. Attribute width/height are the world-coordinate
  contract (1000x720) that `genko-render` assumes; display size is the CSS box."
  ([] (canvas-view nil))
  ([{:keys [id width height]}]
   [:canvas {:id (or id "gl")
             :class "genko-canvas"
             :width (or width 1000)
             :height (or height 720)
             :aria-label "原稿 canvas"}]))

;; ── 作品を開く（library） ─────────────────────────────────────────────────────
;; エディタを開いて白紙が出るのは「道具」だが、mangaka がやりたいのは「この作品の
;; 続きを描く」。だから最初の画面は作品の一覧で、そこから 1 つ選ぶと原稿になる。
;;
;; カタログは host が取ってきて `[:set-works …]` で db に入れる(この ns は純)。
;; 取れなかったときに一覧を空で見せると「作品が無い」と読めてしまうので、
;; `:works-status` を必ず添えて **取れなかったことを言う**。

(defn- work-meta-line
  "作品カードの 2 行目: 話数・作者・ジャンル・ページ数。無い項目は出さない
  (空の区切り記号が並ぶと、値が在るのに壊れているように読める)。"
  [{:keys [series author genre pageCount]}]
  (let [parts (remove str/blank?
                      [(some-> series str)
                       (some-> author str)
                       (some-> genre str)
                       (when (and (number? pageCount) (pos? pageCount))
                         (str pageCount " ページ"))])]
    (when (seq parts)
      [:p.genko-work-meta (str/join " · " parts)])))

(defn work-card
  "1 作品。表紙を押しても開く(カード全体が `data-act` を持つので `closest-act` が
  拾う)ので、ボタンは「押せる場所はここ」を示すためのもの。"
  [{:keys [rkey title cover logline] :as work}]
  (let [act (str "open-work/" rkey)]
    [:li.genko-work
     [:button.genko-work-hit {:type "button" :data-act act
                              :aria-label (str (or (not-empty (str title)) rkey) " を原稿として開く")}
      (if (str/blank? (str cover))
        [:span.genko-work-nocover "表紙なし"]
        [:img.genko-work-cover {:src (str cover) :alt "" :loading "lazy"}])]
     [:div.genko-work-body
      [:h3.genko-work-title (or (not-empty (str title)) rkey)]
      (work-meta-line work)
      (when-not (str/blank? (str logline))
        [:p.genko-work-logline (str logline)])
      (dds/button "原稿として開く" {:type :solid-fill :size "sm"
                                :attrs {:data-act act}})]]))

(defn works-status-view
  "カタログの状態。`nil` は「まだ聞いていない」で、これは何も言わないのが正しい。"
  [status]
  (case status
    :loading [:p.genko-readout "作品を読み込んでいます…"]
    :empty (dds/notification-banner
            {:type :info-1 :heading "公開済みの作品がありません"}
            [:p "新しい原稿から始めてください。"])
    :error (dds/notification-banner
            {:type :warning :heading "作品の一覧を取得できませんでした"
             :actions [(dds/button "再試行" {:type :outline :size "sm"
                                          :attrs {:data-act "reload-works"}})]}
            [:p "この面は作品カタログを持たない場所に置かれているか、"
             "ネットワークが届いていません。新しい原稿と JSON の読み込みは使えます。"])
    nil))

(defn library-view
  "作品一覧の画面。`editor-view` と同じく index 1 が attrs map であること
  (`genko-ui` が `:ref` を差し込むため)。"
  ([db] (library-view db nil))
  ([db _opts]
   (let [works (vec (:works db))]
     [:div.genko-library {}
      [:header.genko-toolbar
       (dds/heading 1 "原稿スタジオ" {:size "24"})
       [:span.genko-spacer]
       (dds/button "新しい原稿" {:type :solid-fill :size "sm"
                             :attrs {:data-act "new-doc"}})
       (dds/button "⇧ import" {:type :outline :size "sm"
                               :attrs {:data-act "import" :title "JSON を読み込む"}})
       (when (seq (get-in db [:doc :pages]))
         (dds/button "編集中の原稿へ" {:type :outline :size "sm"
                                 :attrs {:data-act "show-editor"}}))]
      [:div.genko-library-body
       [:p.genko-library-lede
        "作品を選ぶと、公開済みのページを下絵に敷いた原稿が開きます。"
        "コマ割り・ふきだし・トーン・文字を上に組んで、storyboard として書き出せます。"]
       (works-status-view (:works-status db))
       (when (seq works)
         (into [:ul.genko-works {:aria-label "作品"}] (map work-card works)))]])))

(defn editor-view
  "The whole editor: toolbar, node tree, canvas.

  The frame lives in `app-css` rather than in a shared scaffold. kotoba-ui
  grew an `app-shell {:fill true}` for exactly this — three editors were
  re-deriving the same four rules — but DADS's `dds-ext-*` layer is deliberately
  small and government-site shaped (container / section / grid / stack / row /
  card), and on this base genko is the only editor that needs a viewport-bounded
  frame. One consumer is a rule, not a pattern; if a second arrives, it moves
  upstream then."
  ([db] (editor-view db nil))
  ([db {:keys [canvas] :as opts}]
   ;; The empty attrs map is load-bearing, not noise. `genko-ui/editor` mounts
   ;; this under reagent and attaches its ref with `(update 1 assoc :ref …)`,
   ;; which needs index 1 to BE the attrs map. Without it index 1 is the
   ;; toolbar vector and reagent dies with "Vector's key for assoc must be a
   ;; number" — the whole editor renders nothing. Held by a test.
   [:div.genko-editor {}
    (toolbar-view db (select-keys opts [:sync? :library?]))
    [:div.genko-body
     (sidebar-view db)
     [:main.genko-main (canvas-view canvas)]]]))

(defn app-view
  "`:screen` に従って作品一覧か原稿を出す。どちらも index 1 が attrs map なので、
  host の `:ref` 差し込みは画面が切り替わっても同じ書き方でよい。

  分岐をここに置くのは、`editor-view` を embedder(app-aozora)がそのまま使い続け
  られるようにするため —— 一覧を持たない host に画面遷移を持ち込まない。"
  ([db] (app-view db nil))
  ([db opts]
   (if (= :library (:screen db))
     (library-view db opts)
     (editor-view db opts))))

;; ── app stylesheet ───────────────────────────────────────────────────────────

(defn- rgba-css
  "`genko-render`'s [r g b a] float color -> a CSS `rgb()`. The desk color has
  exactly one definition — the renderer's — and this reads it rather than
  restating it; the previous chrome hardcoded `#f0ead6` in three places (page
  CSS, editor wrapper, canvas element) beside the renderer's own copy."
  [[r g b a]]
  (str "rgb(" (Math/round (double (* 255 r))) " "
       (Math/round (double (* 255 g))) " "
       (Math/round (double (* 255 b))) " / " a ")"))

(def editor-css
  "The editor screen's own CSS.

  Every value is a `--hig-*` token — the workspace's shared token contract —
  and `jp-go-dds.tokens/bridge-css` resolves those onto DADS primitives. So
  this stylesheet did not have to be rewritten when the base changed from
  liquid-glass to DADS: that is the whole point of writing to the contract
  rather than to a library.

  What it contains is the editor frame and the timeline of a drawing app: a
  viewport-bounded shell whose canvas takes the leftover space, a sidebar that
  scrolls inside itself, and the pre-paint fallback for the desk colour the
  WebGL renderer owns. DADS's `dds-ext-*` layer is government-site shaped and
  has no editor frame; genko is the only editor on this base that needs one."
  (str
   ;; --- frame: exactly the viewport, panes scroll inside it -----------------
   ".genko-editor{height:100dvh;display:flex;flex-direction:column;overflow:hidden}\n"
   ".genko-body{flex:1;min-height:0;display:grid;grid-template-columns:260px minmax(0,1fr)}\n"
   ".genko-sidebar{min-height:0;overflow:auto;padding:var(--hig-spacing-2);"
   "border-right:1px solid var(--hig-color-separator);"
   "background:var(--hig-color-secondary-system-background)}\n"
   ".genko-main{min-width:0;min-height:0;display:flex;flex-direction:column}\n"
   "@media(max-width:768px){"
   ".genko-body{grid-template-columns:minmax(0,1fr);grid-template-rows:auto minmax(0,1fr)}"
   ".genko-sidebar{border-right:none;border-bottom:1px solid var(--hig-color-separator)}"
   "}\n"
   ;; --- toolbar -------------------------------------------------------------
   ".genko-toolbar{display:flex;align-items:center;flex-wrap:wrap;"
   "gap:var(--hig-spacing-2);padding:var(--hig-spacing-2) var(--hig-spacing-4);"
   "border-bottom:1px solid var(--hig-color-separator);"
   "background:var(--hig-color-secondary-system-background)}\n"
   ".genko-tools{display:flex;gap:var(--hig-spacing-1);flex-wrap:wrap}\n"
   ;; ページ送りは折り返させない。◀ と ▶ が別の行に分かれると、隣にあることで
   ;; 意味が読める組が壊れる。
   ".genko-pages{display:flex;align-items:center;gap:var(--hig-spacing-1);"
   "flex-wrap:nowrap}\n"
   ".genko-spacer{flex:1 1 auto}\n"
   ".genko-readout{color:var(--hig-color-secondary-label);white-space:nowrap;"
   "font-size:var(--hig-text-caption1-font-size)}\n"
   ;; --- node tree -----------------------------------------------------------
   ".genko-tree{list-style:none;margin:0;padding:0}\n"
   ".genko-node{display:flex;align-items:center;gap:var(--hig-spacing-2);"
   "padding:var(--hig-spacing-1) var(--hig-spacing-2);"
   "border-radius:var(--hig-radius-sm);cursor:grab}\n"
   ".genko-node > span:first-child{flex:1;min-width:0;overflow:hidden;"
   "text-overflow:ellipsis;white-space:nowrap}\n"
   ;; Selection is the key colour at low opacity — the same signal DADS uses
   ;; for a current item, not a second one invented here.
   ".genko-node[aria-selected=\"true\"]{background:var(--hig-color-secondary-system-fill)}\n"
   ;; A hidden node is still listed, just de-emphasized.
   ".genko-node--hidden{color:var(--hig-color-tertiary-label)}\n"
   ;; --- canvas --------------------------------------------------------------
   ".genko-canvas{flex:1;min-width:0;min-height:0;width:100%;"
   "touch-action:none;cursor:crosshair;background:" (rgba-css gr/desk-color) "}\n"))

(def library-css
  "The library screen's own CSS — the 作品一覧 you land on before there is a
  document to edit.

  It is a **separate def from `editor-css` on purpose.** Both are bounded by
  size in the test suite, and one shared number would have grown every time a
  screen was added until it stopped meaning anything. Bounding each screen
  separately keeps the question the bound asks — *is a second design system
  creeping back in?* — answerable per screen."
  (str
   ;; エディタと同じ viewport-bounded frame。一覧が本文ごと外側にスクロールすると
   ;; toolbar の「新しい原稿」が画面から消え、戻る道が無くなる。
   ".genko-library{height:100dvh;display:flex;flex-direction:column;overflow:hidden;"
   "background:var(--hig-color-system-background)}\n"
   ".genko-library-body{flex:1;min-height:0;overflow:auto;"
   "padding:var(--hig-spacing-4) var(--hig-spacing-content-margin)}\n"
   ".genko-library-lede{margin:0 0 var(--hig-spacing-4);max-width:60ch;"
   "color:var(--hig-color-secondary-label);"
   "font-size:var(--hig-text-subheadline-font-size)}\n"
   ;; auto-fill + minmax: 1 列でも破綻せず、広い窓では自然に増える。列数を
   ;; breakpoint で数えると、窓幅と作品数の組み合わせのぶんだけ規則が要る。
   ".genko-works{list-style:none;margin:0;padding:0;display:grid;"
   "gap:var(--hig-spacing-4);"
   "grid-template-columns:repeat(auto-fill,minmax(220px,1fr))}\n"
   ".genko-work{display:flex;flex-direction:column;gap:var(--hig-spacing-2);"
   "padding:var(--hig-spacing-3);border-radius:var(--hig-radius-md);"
   "border:1px solid var(--hig-color-separator);"
   "background:var(--hig-color-secondary-system-background)}\n"
   ;; 表紙は押せる。button の既定の見た目は全部落として、中身(画像)だけを見せる。
   ".genko-work-hit{display:block;padding:0;border:0;background:none;cursor:pointer;"
   "border-radius:var(--hig-radius-sm);overflow:hidden}\n"
   ".genko-work-hit:focus-visible{outline:2px solid var(--hig-color-tint);"
   "outline-offset:2px}\n"
   ;; aspect-ratio は仕上がり B5(182:257)。表紙が来る前から箱の高さが決まるので、
   ;; 読み込みのたびに一覧が跳ねない。
   ".genko-work-cover{display:block;width:100%;aspect-ratio:182/257;"
   "object-fit:cover;background:var(--hig-color-tertiary-system-fill)}\n"
   ".genko-work-nocover{display:flex;align-items:center;justify-content:center;"
   "width:100%;aspect-ratio:182/257;color:var(--hig-color-tertiary-label);"
   "background:var(--hig-color-tertiary-system-fill);"
   "font-size:var(--hig-text-caption1-font-size)}\n"
   ".genko-work-body{display:flex;flex-direction:column;gap:var(--hig-spacing-1);"
   "align-items:flex-start}\n"
   ".genko-work-title{margin:0;font-size:var(--hig-text-headline-font-size)}\n"
   ".genko-work-meta{margin:0;color:var(--hig-color-secondary-label);"
   "font-size:var(--hig-text-caption1-font-size)}\n"
   ".genko-work-logline{margin:0;color:var(--hig-color-secondary-label);"
   "font-size:var(--hig-text-footnote-font-size)}\n"))

(def app-css
  "Every screen's CSS, in one string — what a host injects.

  Hosts keep consuming this name; the split into `editor-css` / `library-css`
  is about what the test suite can bound, not about making callers assemble
  the stylesheet themselves."
  (str editor-css library-css))
