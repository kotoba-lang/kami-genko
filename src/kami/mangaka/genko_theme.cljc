(ns kami.mangaka.genko-theme
  "The genko editor's theme — one map, and the stylesheet it implies.

  Rule 5 of the kotoba-ui agent guide: a theme map is the only place a hex
  color legitimately appears in app code. This ns is that one place for the
  whole editor; `kami.mangaka.genko-view` contains no hex at all, and neither
  does any host that mounts it."
  (:require [kotoba-ui.core :as ui]
            [kami.mangaka.genko-view :as view]))

(def theme
  "`#e06090` is the ink-pink the editor already used to mark the active tool;
  promoted from a one-off literal buried in the toolbar's inline styles to the
  accent the whole design system derives from — tab highlight, focus ring,
  selection tint and the nav wash now all follow it, in both appearances.

  `:appearance :auto` because the chrome should follow the OS. The drawing
  surface does not: `genko-render` paints the same cream desk either way, the
  way an artboard stays white inside a dark editor."
  {:accent "#e06090"
   :appearance :auto})

(def full-page-css
  "The rule a page adds when the editor IS the page. `app-shell {:fill true}`
  fills its container, deliberately — the same editor also mounts inside
  app-aozora's chrome, where a viewport-locked frame would overflow by the
  height of aozora's header. So whether the container is the viewport is the
  host page's fact to state, and this is it: the height chain from the
  document down to the mount node.

  Not part of `genko-view/app-css`: that stylesheet travels with the embedded
  editor too, and an embed has no business claiming the document's height."
  "html,body,#app{height:100%}\n")

(defn stylesheet
  "The complete CSS for a page hosting the editor: the design system's bundle
  for `theme` (HIG tokens, glass material, shell structure — layer-ordered)
  followed by the editor's own unlayered rules, which therefore win without
  needing a single compound selector.

  `:full-page?` adds the html/body height rule described above — true for the
  standalone genko page, false (the default) for an embed."
  ([] (stylesheet theme nil))
  ([t] (stylesheet t nil))
  ([t {:keys [full-page?]}]
   (str (ui/theme-css t) "\n"
        view/app-css
        (when full-page? full-page-css))))
