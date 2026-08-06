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

(def default-out
  "public/index.html. Published through cloud-itonami's sites plane
  (network-awai/cloud-itonami sites.edn, ADR-2607301300), whose generator
  copies this directory into public/sites/{org}/{repo}/ — so the page lives
  under a path prefix and all its references are relative."
  "public/index.html")

(defn- arg-after
  "`--flag value` → value, else nil. Not a general option parser on purpose:
  this script takes two options and `nbb --classpath <cp>` has already put
  things in argv that a general parser would have to know to ignore."
  [args flag]
  (second (drop-while #(not= flag %) args)))

(defn -main [& args]
  ;; The page itself comes from `genko-theme/->page-html` — the library, shared
  ;; with the studio surface in cloud-itonami/mangaka. Two repos generating the
  ;; same page from two copies of the generator is exactly how the two drift.
  (let [out (or (arg-after args "--out") default-out)
        html (theme/->page-html {:dds-css dds-css
                                 :catalog (arg-after args "--catalog")})
        check? (some #{"--check"} args)
        current (when (fs/existsSync out) (str (fs/readFileSync out "utf8")))]
    (cond
      (and check? (= current html))
      (println (str out " up to date"))

      check?
      (do (println (str "STALE: " out " differs from its generator.")
                   "Run: nbb scripts/gen-page.cljs")
          (process/exit 1))

      :else
      (do (fs/writeFileSync out html)
          (println "wrote" out (count html) "bytes")))))

(apply -main (drop 2 (js->clj (.-argv process))))
