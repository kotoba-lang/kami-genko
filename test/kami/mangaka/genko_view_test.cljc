(ns kami.mangaka.genko-view-test
  "The chrome had no test at all while it was reagent-with-inline-styles —
  nothing on the JVM could load it. Making the views pure `.cljc` is what
  makes these assertions possible, so they are part of the same change."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [html.core :as html]
            [kami.mangaka.genko :as g]
            [kami.mangaka.genko-view :as view]))

(defn- db
  "A minimal editor db in the shape genko-view/initial-db produces."
  ([] (db nil))
  ([overrides]
   (merge (view/initial-db (g/new-doc "T" {:page-id "p1" :youshi-id "y1"}))
          overrides)))

(defn- html [hic] (html/->html hic))

;; ── the property this whole ns exists to hold ────────────────────────────────

(def ^:private hex-color-re
  ;; #rgb / #rrggbb / #rrggbbaa in a style or attribute position.
  #"#[0-9a-fA-F]{3}(?:[0-9a-fA-F]{3}(?:[0-9a-fA-F]{2})?)?\b")

(deftest chrome-carries-no-raw-values-test
  (testing "no raw hex color anywhere in the rendered chrome"
    ;; Before this change the same three components rendered 12 hex literals
    ;; (#111 toolbar, #e06090 active tool, #faf7f0 sidebar, #cfe3ff selection,
    ;; #e06060/#8fdc8f status, …). Colors are the design system's decision;
    ;; the editor's single legitimate hex is the accent in genko-theme.
    (doseq [[label out] {"toolbar" (html (view/toolbar-view (db) {:sync? true}))
                         "tree"    (html (view/tree-view (db)))
                         "editor"  (html (view/editor-view (db)))}]
      (is (nil? (re-find hex-color-re out))
          (str label " leaked a raw hex color: " (re-find hex-color-re out)))))
  (testing "no inline style attribute — layout comes from the shell, not from here"
    (doseq [[label out] {"toolbar" (html (view/toolbar-view (db)))
                         "tree"    (html (view/tree-view (db)))
                         "editor"  (html (view/editor-view (db)))}]
      (is (not (str/includes? out "style=")) (str label " carried an inline style")))))

(deftest app-css-uses-tokens-only-test
  (testing "every color in the app stylesheet is a token reference"
    ;; The desk fallback is the one color literal, and it is DERIVED from
    ;; genko-render/desk-color rather than restated — so it cannot drift from
    ;; what the WebGL renderer actually paints.
    (is (nil? (re-find hex-color-re view/app-css)))
    (is (str/includes? view/app-css "var(--hig-color-tertiary-label)"))
    (is (str/includes? view/app-css "var(--hig-color-secondary-label)"))
    (is (str/includes? view/app-css "var(--hig-spacing-2)"))
    (is (str/includes? view/app-css "var(--hig-radius-sm)")))
  (testing "each screen's stylesheet stays small — a frame, not a second design system"
    ;; The editor sheet grew from 4 rules to ~15 when the base changed. That is
    ;; the honest cost of leaving kotoba-ui: `app-shell {:fill true}` supplied
    ;; the viewport-bounded frame, and DADS's dds-ext-* layer is government-site
    ;; shaped (container / section / grid / stack / row / card) with no editor
    ;; frame in it. The bound is here to catch a second design system creeping
    ;; back in, not to pretend the frame is free.
    ;;
    ;; It is bounded **per screen** rather than over `app-css`. A single number
    ;; over the whole app has to be raised every time a screen is added, and a
    ;; bound that is raised whenever it fails is not a bound. Adding a screen
    ;; should mean adding a bound here, not loosening this one.
    (is (< (count (str/split-lines (str/trim view/editor-css))) 20))
    (is (< (count (str/split-lines (str/trim view/library-css))) 20))
    (is (= (str/trim view/app-css)
           (str/trim (str view/editor-css view/library-css)))
        "app-css は各画面の連結そのもの — ここだけに規則が足されていない")))

;; ── act vocabulary ───────────────────────────────────────────────────────────

(deftest act->action-test
  (testing "argument-taking acts"
    (is (= [:set-tool "draw"] (view/act->action "tool/draw")))
    (is (= [:select-node "n7"] (view/act->action "select-node/n7")))
    (is (= [:toggle-vis "n7"] (view/act->action "toggle-vis/n7"))))
  (testing "an argument-taking act with no argument is not an action"
    (is (nil? (view/act->action "tool")))
    (is (nil? (view/act->action "select-node/"))))
  (testing "nullary acts are their own op"
    (is (= [:undo] (view/act->action "undo")))
    (is (= [:redo] (view/act->action "redo")))
    (is (= [:reset-viewport] (view/act->action "reset-viewport")))
    (is (= [:toggle-youshi-vis] (view/act->action "toggle-youshi-vis")))
    (is (= [:delete-selected] (view/act->action "delete-selected"))))
  (testing "host effects are NOT editor actions — the host runs them itself"
    (doseq [a view/host-acts]
      (is (nil? (view/act->action a))
          (str a " must not resolve to a dispatchable action"))))
  (testing "unknown and empty acts are nil, not exceptions"
    (is (nil? (view/act->action "nope")))
    (is (nil? (view/act->action "nope/arg")))
    (is (nil? (view/act->action "")))
    (is (nil? (view/act->action nil)))))

(deftest change-act->action-test
  (is (= [:set-youshi-type "b4koma"] (view/change-act->action "youshi-type" "b4koma")))
  (is (= [:apply-preset "2x2"] (view/change-act->action "panel-preset" "2x2")))
  (is (= [:set-fuki-type "cloud"] (view/change-act->action "fuki-type" "cloud")))
  (testing "the placeholder row is a reset, not a preset application"
    (is (nil? (view/change-act->action "panel-preset" ""))))
  (testing "unknown change acts are nil"
    (is (nil? (view/change-act->action "undo" "x")))))

(deftest every-emitted-act-resolves-test
  (testing "no control in either screen emits an act nothing can handle"
    ;; The failure this guards is a rename on one side only: a live-looking
    ;; control that silently does nothing when clicked.
    ;;
    ;; Host acts are resolved with `view/host-act`, not by looking the whole
    ;; string up in `host-acts`. Once a host act could carry an argument
    ;; (`open-work/<rkey>`), whole-string matching declared every one of them
    ;; unhandled — which is the same bug in the guard that the guard exists to
    ;; find in the chrome.
    (let [d (-> (g/new-doc "T" {:page-id "p1" :youshi-id "y1"})
                (assoc-in [:pages 0 :nodes] [(g/panel-node "n1" {:x1 0 :y1 0 :x2 1 :y2 1})]))
          screens {"editor"  (html (view/editor-view (db {:doc d :tool "fukidashi"})
                                                     {:sync? true}))
                   "library" (html (view/library-view
                                    (db {:screen :library
                                         :works [{:rkey "gh-arc0-1-v11" :title "Ghost Hacker"
                                                  :cover "img/gh-arc0-1-v11-p01"}]})))
                   ;; The error banner carries the only retry control there is.
                   "library/error" (html (view/library-view
                                          (db {:screen :library :works-status :error})))}]
      (doseq [[label out] screens]
        (let [acts (map second (re-seq #"data-act=\"([^\"]+)\"" out))]
          (is (seq acts) (str label " emitted no acts at all"))
          (doseq [a acts]
            (is (or (view/act->action a)
                    (view/host-act a)
                    (contains? view/change-acts a))
                (str label " emitted an act with no handler: " a))))))))

(deftest host-act-test
  (testing "host effects are matched by group, so they can carry an argument"
    (is (= ["open-work" "gh-arc0-1-v11"] (view/host-act "open-work/gh-arc0-1-v11")))
    (is (= ["new-doc" nil] (view/host-act "new-doc")))
    (is (= ["export" nil] (view/host-act "export"))))
  (testing "an editor action is not a host effect, and vice versa"
    ;; The two vocabularies must stay disjoint: an act that resolved as both
    ;; would run a doc mutation and a side effect from one click.
    (is (nil? (view/host-act "tool/draw")))
    (is (nil? (view/host-act "undo")))
    (is (nil? (view/act->action "open-work/gh-arc0-1-v11")))
    (is (nil? (view/act->action "new-doc")))
    (is (nil? (view/host-act nil)))
    (is (nil? (view/host-act "")))))

(deftest page-nav-test
  (testing "1 ページなら送り先が無い —— 選択は出さず、前後は両方 disabled"
    (let [out (html (view/page-nav-view (db)))]
      (is (str/includes? out "1 / 1"))
      (is (not (str/includes? out "aria-label=\"ページ\"><select"))
          "1 枚しかないのに選ばせない")
      (is (str/includes? out "disabled"))))
  (testing "端の送り act は範囲外を指すが、モデルがクランプするので無害"
    ;; disabled なので発火しないが、`data-act` は文字列なので host が別経路で
    ;; 拾う可能性がある。押せないことと、押されても壊れないことは別の保証。
    (let [d (g/new-doc "T" {:page-id "p1" :youshi-id "y1"})]
      (is (= 0 (:activePageIdx (g/set-page-idx d -1))))
      (is (= 0 (:activePageIdx (g/set-page-idx d 99))))
      (is (= 0 (:activePageIdx (g/set-page-idx d "x"))))))
  (testing "複数ページなら前後と選択が出て、番号は 1 起点・act は 0 起点"
    (let [d (-> (g/new-doc "T" {:page-id "p1" :youshi-id "y1"})
                (g/add-page {:page-id "p2" :youshi-id "y2"})
                (g/add-page {:page-id "p3" :youshi-id "y3"})
                (g/set-page-idx 1))
          out (html (view/page-nav-view (db {:doc d})))]
      (is (str/includes? out "data-act=\"set-page/0\"") "◀ は 1 つ前の index")
      (is (str/includes? out "data-act=\"set-page/2\"") "▶ は 1 つ後の index")
      (is (str/includes? out "2 / 3") "見せる番号は 1 起点")
      (is (str/includes? out "data-act=\"add-page\""))))
  (testing "端では送りが disabled —— 押せるのに何も起きない、を作らない"
    (let [d (g/add-page (g/new-doc "T" {:page-id "p1" :youshi-id "y1"})
                        {:page-id "p2" :youshi-id "y2"})]
      (is (str/includes? (html (view/page-nav-view (db {:doc (g/set-page-idx d 0)}))) "disabled"))
      (is (str/includes? (html (view/page-nav-view (db {:doc (g/set-page-idx d 1)}))) "disabled")))))

(deftest library-view-test
  (testing "作品カードは表紙とタイトルを出し、開く act を持つ"
    (let [out (html (view/library-view
                     (db {:screen :library
                          :works [{:rkey "gh-arc0-1-v11" :title "Ghost Hacker"
                                   :cover "img/gh-arc0-1-v11-p01" :logline "電脳の街で"}]})))]
      (is (str/includes? out "genko-works"))
      (is (str/includes? out "data-act=\"open-work/gh-arc0-1-v11\""))
      (is (str/includes? out "Ghost Hacker"))
      (is (str/includes? out "電脳の街で"))
      (is (str/includes? out "alt=\"\"") "表紙は装飾 —— 隣にタイトルが文字で在る")))
  (testing "表紙が無い作品も一覧に出る —— 箱の高さは表紙の有無で変わらない"
    (let [out (html (view/library-view (db {:screen :library :works [{:rkey "x"}]})))]
      (is (str/includes? out "genko-work-nocover"))
      (is (str/includes? out "data-act=\"open-work/x\""))))
  (testing "状態を聞いていないうちは何も言わない"
    (let [out (html (view/library-view (db {:screen :library})))]
      (is (not (str/includes? out "読み込んでいます")))
      (is (not (str/includes? out "取得できませんでした")))))
  (testing "カタログが無い場所でも新しい原稿と import は使える"
    ;; この面が作品一覧を持たないことは、原稿を作れないことを意味しない。
    (let [out (html (view/library-view (db {:screen :library :works-status :error})))]
      (is (str/includes? out "data-act=\"new-doc\""))
      (is (str/includes? out "data-act=\"import\""))
      (is (str/includes? out "data-act=\"reload-works\"")))))

(deftest app-view-switches-screens-test
  (testing ":screen で画面が決まり、既定は editor —— 一覧を持たない host に遷移を持ち込まない"
    (is (str/includes? (html (view/app-view (db))) "genko-editor"))
    (is (str/includes? (html (view/app-view (db {:screen :library}))) "genko-library")))
  (testing "どちらの画面も index 1 が attrs map —— host の :ref 差し込みが同じ書き方でよい"
    ;; これが崩れると reagent が "Vector's key for assoc must be a number" で死に、
    ;; 画面が丸ごと出なくなる(editor-view と同じ罠)。
    (is (map? (nth (view/app-view (db)) 1)))
    (is (map? (nth (view/app-view (db {:screen :library})) 1)))))

;; ── rendered structure ───────────────────────────────────────────────────────

(deftest toolbar-view-test
  (let [out (html (view/toolbar-view (db {:tool "tone"}) {:sync? true}))]
    (testing "built from design-system components, not hand-rolled elements"
      (is (str/includes? out "genko-toolbar"))
      (is (str/includes? out "dads-button")))
    (testing "unlabeled dropdowns name themselves (shitsuke select :attrs)"
      (is (str/includes? out "aria-label=\"原稿用紙\""))
      (is (str/includes? out "aria-label=\"コマ割り\""))
      (is (str/includes? out "aria-label=\"トーンパターン\"")))
    (testing "tool-specific menus appear only for their tool"
      (is (not (str/includes? out "aria-label=\"吹き出し種別\"")))
      (is (str/includes? (html (view/toolbar-view (db {:tool "fukidashi"})))
                         "aria-label=\"吹き出し種別\"")))
    (testing "sync controls follow the adapter, not the db"
      (is (str/includes? out "data-act=\"cloud-save\""))
      (is (not (str/includes? (html (view/toolbar-view (db))) "data-act=\"cloud-save\""))))
    (testing "undo/redo are disabled with an empty history rather than dead"
      (is (str/includes? out "disabled")))
    (testing "the readout is sized by token, not by a utility class"
      ;; `.hig-*` utility classes came from shitsuke and do not exist on this
      ;; base; the size is a token reference in app-css instead.
      (is (not (str/includes? out "hig-caption1")))
      (is (str/includes? out "genko-readout")))))

(deftest tools-view-test
  (testing "the tool palette is a segmented control in the toolbar"
    (let [tools (html (view/tools-view (db {:tool "tone"})))
          bar   (html (view/toolbar-view (db {:tool "tone"})))]
      (is (str/includes? tools "genko-tools"))
      (is (str/includes? tools "data-type=\"solid-fill\""))
      (is (str/includes? tools "data-act=\"tool/tone\""))
      ;; It belongs to the toolbar, not the sidebar: tab-bar does not wrap, and
      ;; in a 260px column the six labels collapse to one character per line.
      (is (str/includes? bar "genko-tools"))
      (is (not (str/includes? (html (view/sidebar-view (db))) "genko-tools")))))
  (testing "the sidebar is the node tree, built from the shell's stack"
    (let [out (html (view/sidebar-view (db)))]
      (is (str/includes? out "genko-sidebar"))
      (is (str/includes? out "genko-tree")))))

(deftest sync-status-test
  (testing "an async result announces itself"
    (let [out (html (view/toolbar-view (db {:kotoba-status :saved}) {:sync? true}))]
      (is (str/includes? out "role=\"status\""))
      (is (str/includes? out "aria-live=\"polite\""))
      (is (str/includes? out "☁✓"))))
  (testing "an error is a red DADS chip, not an app-invented colour"
    ;; Previously a `.genko-status--error` class pointing at --hig-palette-red.
    ;; DADS's chip-label already has the states, so the app states which one it
    ;; is and stops owning the colour.
    (let [out (html (view/toolbar-view (db {:kotoba-status [:error "boom"]}) {:sync? true}))]
      (is (str/includes? out "dads-chip-label"))
      (is (str/includes? out "data-color=\"red\""))
      (is (str/includes? out "boom"))))
  (testing "a settled state is the neutral chip"
    (is (str/includes? (html (view/toolbar-view (db {:kotoba-status :saved}) {:sync? true}))
                       "data-color=\"blue\""))))

(deftest tree-view-test
  (let [d (-> (g/new-doc "T" {:page-id "p1" :youshi-id "y1"})
              (assoc-in [:pages 0 :nodes] [(g/panel-node "n1" {:x1 0 :y1 0 :x2 1 :y2 1})
                                           (g/panel-node "n2" {:x1 1 :y1 1 :x2 2 :y2 2})]))
        out (html (view/tree-view (db {:doc d :selection #{"n2"}})))]
    (testing "a glass list, not a hand-built div stack"
      (is (str/includes? out "<ul class=\"genko-tree\""))
      (is (str/includes? out "<li class=\"genko-node\"")))
    (testing "selection is announced, not only colored"
      (is (str/includes? out "aria-selected=\"true\""))
      (is (str/includes? out "aria-selected=\"false\"")))
    (testing "rows carry the reorder handles the delegated drag listeners read"
      ;; draggable is an enumerated attribute: a bare `draggable` means auto,
      ;; which for a <div> is NOT draggable. It has to serialise as "true".
      (is (str/includes? out "draggable=\"true\""))
      (is (str/includes? out "data-nid=\"n1\""))
      (is (str/includes? out "data-nid=\"n2\"")))
    (testing "原稿用紙 leads the list as a page-level node"
      (is (str/includes? out "genkouyoushi"))
      (is (str/includes? out "data-act=\"toggle-youshi-vis\"")))))

(deftest hiccup-shape-the-reagent-host-depends-on-test
  (testing "index 1 of editor-view / canvas-view is the attrs map"
    ;; genko-ui/editor and /canvas attach their refs with
    ;; `(update 1 assoc :ref …)`. If index 1 is a child vector instead,
    ;; reagent throws "Vector's key for assoc must be a number" and the editor
    ;; renders nothing — which is exactly what happened when the frame stopped
    ;; going through a component that always emitted attrs.
    (is (map? (nth (view/editor-view (db)) 1)))
    (is (map? (nth (view/canvas-view) 1)))))

(deftest editor-view-test
  (let [out (html (view/editor-view (db)))]
    (testing "the editor frame, not a document"
      (is (str/includes? out "genko-editor")))
    (testing "toolbar is the nav, tree is the sidebar, canvas is the content"
      (is (str/includes? out "genko-toolbar"))
      (is (str/includes? out "<aside class=\"genko-sidebar\""))
      (is (str/includes? out "<main class=\"genko-main\"")))
    (testing "the canvas keeps the world-coordinate contract genko-render assumes"
      (is (str/includes? out "width=\"1000\""))
      (is (str/includes? out "height=\"720\"")))))

;; ── 生成 ─────────────────────────────────────────────────────────────────────

(deftest gen-target-test
  (let [panel (g/panel-node "p1" {:x1 100 :y1 200 :x2 400 :y2 400})
        d (-> (g/new-doc "T" {:page-id "pg" :youshi-id "y"})
              (assoc-in [:pages 0 :nodes] [panel]))]
    (testing "コマを選んでいればその中に置く"
      ;; manga の絵はコマの中に描くもの。既定をページ全面にすると毎回置き直す。
      (let [t (view/gen-target (db {:doc d :selection #{"p1"}}))]
        (is (= {:x1 100 :y1 200 :x2 400 :y2 400} (:bounds t)))
        (is (= "選択中のコマ" (:label t)))))
    (testing "コマに名前があればそれを名乗る — どこに出るか分からないまま待たせない"
      (let [named (g/panel-node "p2" {:x1 0 :y1 0 :x2 10 :y2 10 :panelName "1コマ目"})
            d2 (assoc-in d [:pages 0 :nodes] [named])]
        (is (= "1コマ目" (:label (view/gen-target (db {:doc d2 :selection #{"p2"}})))))))
    (testing "選択が無ければページ全体 — 1 枚絵から始める人を拒まない"
      (let [t (view/gen-target (db {:doc d}))]
        (is (= g/youshi-trim-bounds (:bounds t)))
        (is (= "ページ全体" (:label t)))))
    (testing "コマ以外や複数選択はページ全体（どのコマか一意に決まらない）"
      (is (= "ページ全体" (:label (view/gen-target (db {:doc d :selection #{"p1" "zz"}})))))
      (let [txt (g/text-node "t1" {:x 1 :y 1 :text "あ"})
            d3 (assoc-in d [:pages 0 :nodes] [txt])]
        (is (= "ページ全体" (:label (view/gen-target (db {:doc d3 :selection #{"t1"}})))))))
    (testing "座標の欠けた panel でも落ちずにページ全体へ逃がす"
      (let [broken (g/panel-node "b1" {:x1 nil :y1 nil :x2 nil :y2 nil})
            d4 (assoc-in d [:pages 0 :nodes] [broken])]
        (is (= "ページ全体" (:label (view/gen-target (db {:doc d4 :selection #{"b1"}})))))))
    (testing "aspect は行き先の 幅/高さ（生成サイズへの翻訳は host の仕事）"
      (is (< (Math/abs (- 1.5 (view/gen-aspect (db {:doc d :selection #{"p1"}})))) 1e-9)))))

(deftest gen-model-options-test
  (testing "推測なら推測だと書く — 選べるのに何が起きるか分からない、を作らない"
    (is (= [["a" "A（推測）"]]
           (view/gen-model-options (db {:gen-models [{:model-id "a" :label "A" :fallback? true}]})))))
  (testing "版ずれ（family-guess）も隠さない"
    (is (= [["a" "A（版ずれの可能性）"]]
           (view/gen-model-options (db {:gen-models [{:model-id "a" :label "A" :exact? false}]})))))
  (testing "待ち行列があれば言う"
    (is (= [["a" "A · 待ち 3"]]
           (view/gen-model-options (db {:gen-models [{:model-id "a" :label "A" :exact? true :queue 3}]})))))
  (testing "何も無ければ空 — 空の picker を出す判断は view ではなく host が持つ"
    (is (= [] (view/gen-model-options (db))))))

(deftest gen-view-test
  (let [with-models {:gen-models [{:model-id "animagine-xl-4.0" :label "Animagine XL 4.0" :exact? true}]}]
    (testing "プロンプトが空なら生成は押せない"
      (is (str/includes? (html (view/gen-view (db with-models))) "disabled")))
    (testing "生成中は押せず、所要時間を先に言う"
      (let [out (html (view/gen-view (db (merge with-models {:gen-prompt "教室"
                                                             :gen-status :loading}))))]
        (is (str/includes? out "disabled"))
        (is (str/includes? out "60〜100 秒"))))
    (testing "失敗は上流の文字列をそのまま出す"
      ;; 『生成に失敗しました』に潰すと、直せる人が原因に辿り着けない。
      (let [out (html (view/gen-view (db (merge with-models
                                                {:gen-status [:error "no GATEWAY_URL configured"]}))))]
        (is (str/includes? out "no GATEWAY_URL configured"))))
    (testing "生成の act は host effect であって doc action ではない"
      (is (nil? (view/act->action "generate")))
      (is (= ["generate" nil] (view/host-act "generate"))))))

(deftest sidebar-shows-generation-only-when-the-host-has-one-test
  ;; 繋がっていない場所にボタンだけ在るのは、押せるのに何も起きない control と同じ。
  (is (not (str/includes? (html (view/sidebar-view (db))) "genko-gen")))
  (is (str/includes? (html (view/sidebar-view (db) {:gen? true})) "genko-gen")))

(deftest prompt-can-be-cleared-test
  (testing "select の空は placeholder の戻り、テキストの空は消したという意思"
    (is (nil? (view/change-act->action "panel-preset" "")) "preset を再適用しない")
    (is (= [:set-gen-prompt ""] (view/change-act->action "gen-prompt" ""))
        "空にできないと、欄は空なのに db は古い文を持ったままになる")
    (is (= [:set-gen-prompt "教室"] (view/change-act->action "gen-prompt" "教室")))))
