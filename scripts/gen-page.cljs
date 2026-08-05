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
            [jp-go-dds.page :as dds-page]
            [kami.mangaka.genko-view :as view]
            [kami.mangaka.genko-theme :as theme]))

(def dds-root
  "Where the vendored DADS CSS lives. nbb has no resource loader, so the path
  is resolved the way every other nbb consumer here does it — env override
  first, because a temp worktree outside the superproject (the standard shape
  for parallel agents) defeats every relative guess. Mirrors
  gftdcojp/itad's web/generate.cljs."
  (or (first (filter #(and % (fs/existsSync (str % "/resources/jp_go_dds/dds.css")))
                     [(some-> js/process .-env .-DDS_ROOT)
                      "orgs/kotoba-lang/jp-go-digital-design-system"
                      "../jp-go-digital-design-system"
                      "../../kotoba-lang/jp-go-digital-design-system"]))
      (throw (js/Error. (str "jp-go-digital-design-system の dds.css が見つからない。"
                             "DDS_ROOT で場所を渡すこと。")))))

(def dds-css (str (fs/readFileSync (str dds-root "/resources/jp_go_dds/dds.css") "utf8")))

(def out-path
  "public/index.html — the app is published at genko.itonami.cloud, so it sits
  at the host root."
  "public/index.html")

(defn page []
  (dds-page/->page
   {:title "原稿 genko"
    :description "manga 原稿エディタ — コマ割り・吹き出し・トーン・原稿用紙。"
    :lang "ja"
    ;; The library's own bundle is passed in whole; the app's additions ride
    ;; in :app-css, which `page` emits last.
    :css dds-css
    :app-css (str theme/app-css theme/full-page-css)}
   [:div {:id "app"}
    (view/editor-view (view/initial-db))]
   [:script {:src "/js/genko-app.js"}]))

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
