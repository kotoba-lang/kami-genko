(ns gen-page
  "Generate `public/index.html` — the standalone editor's host page.

  The page used to be hand-written HTML carrying its own `#f0ead6` background,
  its own font stack, and three `position: fixed` boxes (#bar / #side / #gl)
  that re-implemented the editor layout in CSS. All four are decisions the
  design system already owns, so the page now comes from the same
  `kotoba-ui.core/->page` every other surface on this stack uses: charset,
  viewport with `viewport-fit=cover`, theme-color metas per color scheme, the
  full token/material/shell bundle inlined, `data-appearance` — from one call.

  The chrome is server-rendered from `genko-view` with an empty document, so
  the first frame is the real editor rather than a blank page; reagent then
  renders the same hiccup over it with the doc restored from localStorage.
  That the SAME pure hiccup produces both is shitsuke's dual-render contract,
  and this script is what proves it outside a test.

  Run:   nbb scripts/gen-page.cljs
  Check: nbb scripts/gen-page.cljs --check   (exit 1 if the file is stale)"
  (:require ["node:fs" :as fs]
            ["node:process" :as process]
            [kotoba-ui.core :as ui]
            [kami.mangaka.genko-view :as view]
            [kami.mangaka.genko-theme :as theme]))

(def out-path
  "index.html, not genko.html: this page is now published (genko.gftd.ai), and
  a web host wants the document its bare path resolves to. Nothing outside
  this repo ever linked to the old name."
  "public/index.html")

(defn page []
  (ui/->page
   {:title "原稿 genko"
    :description "manga 原稿エディタ — コマ割り・吹き出し・トーン・原稿用紙。"
    :lang "ja"
    :theme theme/theme
    ;; app-css and the full-page height chain, appended after the design
    ;; system's bundle so the unlayered app rules win (agent-guide rule 3).
    :head [:style [:hiccup/raw (str view/app-css theme/full-page-css)]]}
   [:div {:id "app"}
    (view/editor-view (view/initial-db))]
   [:script {:src "js/genko-app.js"}]))

(defn -main [& args]
  (let [html (page)
        check? (some #{"--check"} args)
        current (when (fs/existsSync out-path) (str (fs/readFileSync out-path "utf8")))]
    (cond
      (and check? (= current html))
      (println "index.html up to date")

      check?
      (do (println "STALE: public/index.html differs from its generator."
                   "Run: nbb scripts/gen-page.cljs")
          (process/exit 1))

      :else
      (do (fs/writeFileSync out-path html)
          (println "wrote" out-path (count html) "bytes")))))

(apply -main (drop 2 (js->clj (.-argv process))))
