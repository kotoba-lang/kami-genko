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
  (testing "the stylesheet stays small — an editor frame, not a second design system"
    ;; It grew from 4 rules to ~15 when the base changed. That is the honest
    ;; cost of leaving kotoba-ui: `app-shell {:fill true}` supplied the
    ;; viewport-bounded frame, and DADS's dds-ext-* layer is government-site
    ;; shaped (container / section / grid / stack / row / card) with no editor
    ;; frame in it. The bound is here to catch a second design system creeping
    ;; back in, not to pretend the frame is free.
    (is (< (count (str/split-lines (str/trim view/app-css))) 25))))

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
  (testing "no control in the chrome emits an act nothing can handle"
    ;; The failure this guards is a rename on one side only: a live-looking
    ;; control that silently does nothing when clicked.
    (let [d (-> (g/new-doc "T" {:page-id "p1" :youshi-id "y1"})
                (assoc-in [:pages 0 :nodes] [(g/panel-node "n1" {:x1 0 :y1 0 :x2 1 :y2 1})]))
          out (html (view/editor-view (db {:doc d :tool "fukidashi"}) {:sync? true}))
          acts (map second (re-seq #"data-act=\"([^\"]+)\"" out))]
      (is (seq acts))
      (doseq [a acts]
        (is (or (view/act->action a)
                (contains? view/host-acts a)
                (contains? view/change-acts a))
            (str "emitted act with no handler: " a))))))

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
