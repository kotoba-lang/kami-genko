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
            [kotoba-ui.core :as ui]
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
    :viewport gr/default-viewport :pan-from nil :kotoba-status nil}))

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
   "toggle-vis"  :toggle-vis})

(def nullary-acts
  "Click acts that are their own action op and take no argument."
  #{:undo :redo :reset-viewport :delete-selected :toggle-youshi-vis})

(def host-acts
  "Click acts that are NOT editor actions: they are effects the host owns
  (a download, a file picker, a network round-trip). `act->action` returns nil
  for these on purpose — `genko-ui`'s handler dispatches them itself, so a doc
  action and a side effect can never be confused for one another."
  #{"export" "import" "cloud-save" "cloud-load"})

(def change-acts
  "`change` acts (the `<select>`s) -> action op. The selected value is the
  action's argument, so these cannot use the click vocabulary above."
  {"youshi-type"  :set-youshi-type
   "panel-preset" :apply-preset
   "fuki-type"    :set-fuki-type
   "fuki-tail"    :set-fuki-tail
   "tone-pattern" :set-tone-pattern})

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
       (ui/badge
        (case status
          :saving "…" :saved "☁✓" :loading "…" :loaded "☁✓"
          (str "☁✗ " (second status)))
        {:class (when error? "genko-status--error")})])))

(defn toolbar-view
  "Editor toolbar. `db` is the editor db; opts `{:title :sync?}` — `:sync?`
  shows the cloud save/load pair (the host only has those when its adapter
  carries a `:sync`)."
  ([db] (toolbar-view db nil))
  ([db {:keys [title sync?]}]
   (let [tool (:tool db)
         youshi (active-youshi db)
         zoom (get-in db [:viewport :zoom] 1.0)]
     (ui/toolbar
      (remove
       nil?
       [(when title [:strong {:class "hig-subheadline"} title])
        (ui/tab-bar (mapv (fn [t] [(keyword "tool" t) (get tool-labels t t)]) tool-names)
                    (keyword "tool" tool))
        ;; None of these dropdowns has a visible <label> (a toolbar has no room
        ;; for six of them), so each states its own name through :attrs. That
        ;; opt had to be added to shitsuke.components/select first — it used to
        ;; drop aria-label without a word, which is how a control ends up
        ;; announced as nothing while the code reads as accessible.
        (ui/menu-select youshi-options
                        {:act "youshi-type"
                         :value (or (:type youshi) "none")
                         :attrs {:aria-label "原稿用紙"}})
        (ui/menu-select panel-preset-options
                        {:act "panel-preset" :value ""
                         :attrs {:aria-label "コマ割り"}})
        ;; The three tool-specific menus appear only for the tool they belong
        ;; to — the toolbar stays one row instead of carrying six dead controls.
        (when (= "fukidashi" tool)
          (ui/menu-select (mapv (fn [ft] [ft ft]) (sort g/fukidashi-types))
                          {:act "fuki-type" :value (:fuki-type db)
                           :attrs {:aria-label "吹き出し種別"}}))
        (when (= "fukidashi" tool)
          (ui/menu-select (mapv (fn [ft] [ft ft]) (sort g/fukidashi-tails))
                          {:act "fuki-tail" :value (:fuki-tail db)
                           :attrs {:aria-label "しっぽの向き"}}))
        (when (= "tone" tool)
          (ui/menu-select (mapv (fn [tp] [tp tp]) (sort g/tone-patterns))
                          {:act "tone-pattern" :value (:tone-pattern db)
                           :attrs {:aria-label "トーンパターン"}}))
        (ui/spacer)
        (ui/button "⇩ export" {:act "export" :title "JSON を書き出す"})
        (ui/button "⇧ import" {:act "import" :title "JSON を読み込む"})
        (when sync? (ui/button "☁ save" {:act "cloud-save" :title "kotobase.net に保存"}))
        (when sync? (ui/button "☁ load" {:act "cloud-load" :title "kotobase.net から復元"}))
        (when sync? (sync-status (:kotoba-status db)))
        (ui/button "↶ undo" {:act :undo :disabled (not (ed/can-undo? db))})
        (ui/button "↷ redo" {:act :redo :disabled (not (ed/can-redo? db))})
        (ui/button "⌂ view" {:act :reset-viewport
                             :title "空白ドラッグ=pan、ホイール=zoom"})
        [:span {:class "hig-caption1 genko-readout"}
         (str (count (active-nodes db)) " nodes · "
              (Math/round (double (* 100 zoom))) "%")]])
      {:class "genko-toolbar"}))))

;; ── node tree ────────────────────────────────────────────────────────────────

(defn- node-row
  "One tree row. `:draggable` + `data-nid` are the reorder handles the host's
  delegated drag listeners read; `aria-selected` is why `list-row` grew its
  `:attrs` opt — a selected row has to say so to a screen reader, not only to
  the eye."
  [{:keys [nid nm vis]} selected?]
  (ui/list-row
   [:span {:class (when-not vis "genko-node--hidden")} nm]
   {:act (str "select-node/" nid)
    :trailing (ui/icon-button (if vis "👁" "🚫")
                              {:act (str "toggle-vis/" nid)
                               :title (if vis "隠す" "表示する")})
    ;; The string, not the boolean: `draggable` is an ENUMERATED attribute, not
    ;; a boolean one, so a bare `draggable` is an invalid value and falls back
    ;; to `auto` — which for a <div> means not draggable at all. reagent/React
    ;; happens to serialise `true` as `"true"`, but the SSR path renders the
    ;; bare form, and the dual-render contract means both must be right.
    :attrs {:draggable "true"
            :data-nid nid
            :aria-selected (if selected? "true" "false")}}))

(defn tree-view
  "Node tree: 原稿用紙 first (it is a page-level node, not one of `:nodes`),
  then the drawn nodes in paint order."
  [db]
  (let [rows (g/all-nodes (active-nodes db))
        youshi (active-youshi db)
        selection (:selection db)]
    (ui/list-view
     (remove
      nil?
      (cons
       (when youshi
         (let [vis? (not (false? (:visible youshi)))]
           (ui/list-row
            [:span {:class (when-not vis? "genko-node--hidden")}
             (str "genkouyoushi (" (or (:type youshi) "none") ")")]
            {:trailing (ui/icon-button (if vis? "👁" "🚫")
                                       {:act :toggle-youshi-vis
                                        :title (if vis? "隠す" "表示する")})})))
       (map (fn [row] (node-row row (contains? selection (:nid row)))) rows)))
     {:class "genko-tree"})))

;; ── canvas + frame ───────────────────────────────────────────────────────────

(defn canvas-view
  "The drawing surface. Attribute width/height are the world-coordinate
  contract (1000x720) that `genko-render` assumes; display size is the CSS
  box, which `:fill` gives it."
  ([] (canvas-view nil))
  ([{:keys [id width height]}]
   [:canvas {:id (or id "gl")
             :class "genko-canvas"
             :width (or width 1000)
             :height (or height 720)
             :aria-label "原稿 canvas"}]))

(defn editor-view
  "The whole editor: toolbar as the shell's nav, node tree as its sidebar,
  canvas as its content. `:fill` is what makes this an editor rather than a
  document — the frame is bounded and the canvas takes the leftover space, so
  this ns writes no layout CSS at all (kotoba-ui agent-guide rule 4)."
  ([db] (editor-view db nil))
  ([db {:keys [canvas] :as opts}]
   (ui/app-shell
    {:fill true
     :nav (toolbar-view db (select-keys opts [:title :sync?]))
     :sidebar (tree-view db)
     :class "genko-editor"}
    (canvas-view canvas))))

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

(def app-css
  "The editor's own CSS — unlayered, so it wins over the library layers
  without a single compound selector (agent-guide rule 3).

  Deliberately four rules. Layout comes from the shell, color and type from
  tokens; what remains is genuinely app-specific: how a `<canvas>` (a replaced
  element with intrinsic dimensions) fills its flex parent, and the pre-paint
  fallback for the desk color the WebGL renderer owns."
  (str
   ".genko-canvas{flex:1;min-width:0;min-height:0;width:100%;"
   "touch-action:none;cursor:crosshair;background:" (rgba-css gr/desk-color) "}\n"
   ;; A hidden node is still listed, just de-emphasized. tertiary-label is the
   ;; HIG token for exactly that, and it carries its own dark value.
   ".genko-node--hidden{color:var(--hig-color-tertiary-label)}\n"
   ".genko-status--error{color:var(--hig-palette-red)}\n"
   ;; The readout is a measurement, not a heading — secondary label keeps it
   ;; legible without competing with the tool tabs beside it.
   ".genko-readout{color:var(--hig-color-secondary-label);white-space:nowrap}\n"))
