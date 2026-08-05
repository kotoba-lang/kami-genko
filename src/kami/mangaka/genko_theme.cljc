(ns kami.mangaka.genko-theme
  "The genko editor's stylesheet, on **jp-go-dds** — the デジタル庁デザイン
  システム (DADS) mirror, which is this workspace's base design system.

  ── why DADS and not liquid-glass ───────────────────────────────────────────
  Owner decision, 2026-08-05. Measured at the time: 170 repos already depended
  on `jp-go-digital-design-system`, 12 on `kotoba-ui`/liquid-glass. genko had
  just been migrated onto the minority stack; this moves it to the one the
  workspace actually speaks.

  ── what did NOT have to change ─────────────────────────────────────────────
  `genko-view/app-css` — every value in it is a `--hig-*` token, and
  `jp-go-dds.tokens/bridge-css` redefines those on top of DADS primitives. A
  stylesheet written to the token contract survives a change of base
  untouched, which is the argument for having the contract at all. (It was not
  free: the bridge only mapped colour, so the 4pt grid, the radius scale and
  the text sizes had to be added upstream first — an app that takes DADS as
  its base drops `shitsuke.hig` and has nothing to fall back to.)

  What did change is the component layer: liquid-glass's toolbar / tab-bar /
  list-view / badge became DADS buttons, selects, chip-labels and semantic
  `<ul>` markup. Components are a library's own vocabulary; tokens are the
  shared one.

  ── the CSS is passed in, not read here ─────────────────────────────────────
  The vendored `dds.css` lives in the library's resources, and the caller
  reads it: on the JVM via `io/resource`, from nbb via the file path (nbb has
  no resource loader). Same shape every other consumer in the workspace uses —
  see `gftdcojp/itad`'s `web/generate.cljs`."
  (:require [jp-go-dds.core :as dds]
            [jp-go-dds.tokens :as dds-tokens]
            [kami.mangaka.genko-view :as view]))

(def full-page-css
  "The rule a page adds when the editor IS the page: the height chain from the
  document down to the mount node. `.genko-editor` is `100dvh`, so on a
  full-page host nothing else is needed — but the mount node still has to not
  collapse, and only the host knows it owns the viewport.

  Not part of `genko-view/app-css`: that stylesheet travels with the embedded
  editor too (app-aozora mounts it under its own header), and an embed has no
  business claiming the document's height."
  "html,body,#app{height:100%;margin:0}\n")

(def app-css
  "Everything the app contributes to a DADS page's `:app-css` slot: the
  `--hig-*` bridge, then the editor's own rules.

  The bridge comes first so the app can still override a token; the app's
  rules come last so they win. `jp-go-dds.page` emits `dds/ext-css` itself,
  between the vendored bundle and this, so it is deliberately not repeated
  here — emitting it twice would duplicate every ext custom property."
  (str dds-tokens/bridge-css "\n" view/app-css))

(defn stylesheet
  "The complete CSS for a host that is NOT going through `jp-go-dds.page` —
  an embed that builds its own document. `dds-css` is the vendored DADS
  stylesheet, read by the caller.

  Order is the one `page` uses: vendored DADS → `ext` → the `--hig-*` bridge →
  the app's own rules."
  ([dds-css] (stylesheet dds-css nil))
  ([dds-css {:keys [full-page?]}]
   (str dds-css "\n"
        dds/ext-css "\n"
        app-css
        (when full-page? full-page-css))))
