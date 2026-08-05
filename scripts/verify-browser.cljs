(ns verify-browser
  "Drive the built standalone editor in a real headless Chromium and assert the
  parts a JVM test cannot reach.

  This exists because the design-system migration replaced per-element reagent
  closures with ONE delegated listener per event type reading `data-act`. The
  pure views are covered by kami.mangaka.genko-view-test and the bundle is
  covered by the compiler, but neither can tell you whether clicking a tab
  actually reaches dispatch! — that is exactly what changed, so that is what
  this checks, against the real DOM, the real bundle and the real listeners.

  Run (after `shadow-cljs release app` and `nbb scripts/gen-page.cljs`, with
  public/ served):
    GENKO_URL=http://localhost:8734/ \\
      npx nbb --classpath <cp> scripts/verify-browser.cljs"
  (:require ["node:process" :as process]
            ["playwright-core$default" :as pw]
            [clojure.string :as str]
            [promesa.core :as p]))

;; From the environment, not argv: `--classpath <cp>` shifts argv, and reading
;; a fixed index there navigated the browser to the classpath string.
(def url (or (.. process -env -GENKO_URL) "http://localhost:8734/"))

(defonce results (atom []))

(defn- check! [label ok? detail]
  (swap! results conj {:label label :ok? (boolean ok?) :detail detail})
  (println (if ok? "  PASS" "  FAIL") label (if ok? "" (str "-- " detail))))

(defn- settle
  "Let reagent flush. Its re-render is rAF-batched, so reading the DOM in the
  same tick as the click that changed the state reads the PREVIOUS frame —
  which produced two false failures on the first sequential run of this
  harness (a row that did select, reported as not selecting)."
  [page]
  (.waitForTimeout page 250))

(defn- checks
  "Every assertion, as THUNKS. They must be thunks: `p/let` starts its promise
  the moment it is evaluated, so a vector of p/let forms would fire all of
  them at once — which is what happened on the first run of this harness and
  produced three false failures (a tab click 'not working' that was simply
  read before it happened). Several checks depend on the state the previous
  one leaves behind, so the runner walks these strictly in order."
  [page errors]
  [;; The bundle booted at all.
   (fn [] (p/let [t (.evaluate page "typeof globalThis.genkoApi")]
            (check! "bundle booted (genkoApi present)" (= "object" t) t)))

   ;; The canvas got a live WebGL2 context — i.e. `editor`'s did-mount found
   ;; the canvas it renders itself and ran attach-canvas! on it.
   (fn [] (p/let [v (.evaluate page "(() => { const c = document.querySelector('canvas.genko-canvas'); return !!(c && c.getContext('webgl2')); })()")]
            (check! "canvas has a live WebGL2 context" v v)))

   ;; DELEGATED CLICK — a tool tab must reach dispatch! and change :tool.
   (fn [] (p/let [_ (.click page "[data-act='tool/panel']")
                  _ (settle page)
                  t (.getAttribute (.first (.locator page "[data-act='tool/panel']")) "data-type")
                  pressed (.getAttribute (.first (.locator page "[data-act='tool/panel']")) "aria-pressed")]
            ;; DADS says "current" with the button's own type, not a separate
            ;; segmented-control class.
            (check! "delegated click: tool button becomes the current one"
                    (and (= "solid-fill" (str t)) (= "true" (str pressed)))
                    (str t " / " pressed))))

   ;; DELEGATED CHANGE — the コマ割り <select> must apply a preset.
   (fn [] (p/let [before (.evaluate page "globalThis.genkoApi.nodeCount()")
                  _ (.selectOption page "select[aria-label='コマ割り']" "2x2")
                  _ (settle page)
                  after (.evaluate page "globalThis.genkoApi.nodeCount()")]
            (check! "delegated change: コマ割り preset adds panels"
                    (> after before) (str before " -> " after))))

   ;; ...and snaps back to its placeholder row, so it never shows a stale
   ;; "current preset" that is not a property of the page.
   (fn [] (p/let [v (.inputValue page "select[aria-label='コマ割り']")]
            (check! "コマ割り menu resets to its placeholder" (= "" v) (pr-str v))))

   ;; A control that looked disabled must become live once there is history.
   (fn [] (p/let [can (.evaluate page "globalThis.genkoApi.canUndo()")]
            (check! "undo became available after an edit" can can)))

   (fn [] (p/let [before (.evaluate page "globalThis.genkoApi.nodeCount()")
                  _ (.click page "[data-act='undo']")
                  _ (settle page)
                  after (.evaluate page "globalThis.genkoApi.nodeCount()")]
            (check! "delegated click: undo reverts the preset"
                    (< after before) (str before " -> " after))))

   ;; Tree rows select through the delegated row act.
   (fn [] (p/let [_ (.evaluate page "globalThis.genkoApi.addPanel(10,10,80,80)")
                  _ (settle page)
                  row (.first (.locator page "[data-nid]"))
                  _ (.click row)
                  _ (settle page)
                  v (.getAttribute (.first (.locator page "[data-nid]")) "aria-selected")]
            (check! "delegated click: tree row selects (aria-selected)"
                    (= "true" v) (pr-str v))))

   ;; The eye toggle sits INSIDE a clickable row. The nearest data-act ancestor
   ;; must win — this is the case the old code needed .stopPropagation for.
   (fn [] (p/let [before (.evaluate page "globalThis.genkoApi.visibleNodeIds().length")
                  glyph0 (.textContent (.first (.locator page "[data-nid] [data-act^='toggle-vis/']")))
                  _ (.click (.first (.locator page "[data-nid] [data-act^='toggle-vis/']")))
                  _ (settle page)
                  after (.evaluate page "globalThis.genkoApi.visibleNodeIds().length")
                  glyph1 (.textContent (.first (.locator page "[data-nid] [data-act^='toggle-vis/']")))]
            ;; Both the state and the pixels: it was the disagreement between
            ;; these two that exposed the broken visibleNodeIds helper.
            (check! "nested eye act wins over the row act"
                    (and (< after before) (not= (str glyph0) (str glyph1)))
                    (str before " -> " after " | glyph " glyph0 " -> " glyph1))))

   ;; ...and toggling visibility must NOT have changed the selection, which is
   ;; the other half of the same guarantee.
   (fn [] (p/let [_ (settle page)
                  v (.getAttribute (.first (.locator page "[data-nid]")) "aria-selected")]
            (check! "the eye toggle does not also re-select its row"
                    (= "true" v) (pr-str v))))

   ;; :fill really bounds the frame — the page must not scroll.
   (fn [] (p/let [m (.evaluate page "(() => { const a = document.querySelector('.genko-editor'); const r = a.getBoundingClientRect(); return {h: Math.round(r.height), vh: window.innerHeight, scrolls: document.documentElement.scrollHeight > window.innerHeight + 1}; })()")]
            (let [m (js->clj m :keywordize-keys true)]
              (check! "editor frame fills the viewport and the page does not scroll"
                      (and (= (:h m) (:vh m)) (not (:scrolls m))) (pr-str m)))))

   ;; The accent reaches the rendered chrome as a real computed color.
    ;; The token contract still answers — but now resolved through DADS.
   ;; getPropertyValue returns the DECLARED value, so a bridged token reads
   ;; back as its var() reference; what proves the chain resolves is that a
   ;; property computed FROM it has a real length.
   (fn [] (p/let [tint (.evaluate page "getComputedStyle(document.documentElement).getPropertyValue('--hig-color-tint').trim()")
                  key (.evaluate page "getComputedStyle(document.documentElement).getPropertyValue('--color-key-900').trim()")
                  gap (.evaluate page "getComputedStyle(document.querySelector('.genko-toolbar')).gap")
                  radius (.evaluate page "getComputedStyle(document.querySelector('.genko-node')).borderRadius")]
            ;; getPropertyValue resolves var() chains, so a bridged token reads
            ;; back as the DADS value itself — which is the proof: --hig-color-tint
            ;; IS --color-key-900 (デジタル庁ブルー), and the grid/radius the
            ;; bridge added resolve to real lengths rather than to nothing.
            (check! "the --hig-* contract resolves onto DADS primitives"
                    (and (= (str tint) (str key))
                         (re-find #"^#[0-9a-f]{6}$" (str key))
                         (re-find #"^\d" (str gap))
                         (re-find #"^\d" (str radius)))
                    (str "tint=" tint " key=" key " gap=" gap " radius=" radius))))

   ;; Nothing above may have logged an error along the way.
   (fn [] (p/resolved (check! "no page errors or console errors"
                              (empty? @errors) (pr-str @errors))))])

(defn -main []
  (p/let [browser (.launch (.-chromium pw) #js {:headless true})
          page (.newPage browser)
          errors (atom [])
          _ (.on page "pageerror" (fn [e] (swap! errors conj (str e))))
          ;; Cloudflare injects its own Real User Monitoring beacon at
          ;; /cdn-cgi/rum, which 404s on zones where RUM is not enabled. It is
          ;; the platform's request, not the app's, and a check that fails on
          ;; every deploy is a check people learn to ignore — so it is excluded
          ;; by name, not by loosening the check.
          platform-noise? (fn [t] (re-find #"cdn-cgi" (str t)))
          ;; The console message for a failed subresource does not name the URL
          ;; in its text ("Failed to load resource: … 404 ()") — the URL is in
          ;; its location. Filtering on the text alone let the RUM beacon
          ;; straight through.
          _ (.on page "console" (fn [m]
                                  (let [loc (some-> (.location m) (aget "url"))]
                                    (when (and (= "error" (.type m))
                                               (not (platform-noise? (.text m)))
                                               (not (platform-noise? loc)))
                                      (swap! errors conj (str (.text m) " @ " loc))))))
          _ (.on page "response" (fn [r] (when (and (>= (.status r) 400)
                                                    (not (platform-noise? (.url r))))
                                           (swap! errors conj (str (.status r) " " (.url r))))))
          _ (.goto page url #js {:waitUntil "networkidle"})
          _ (.waitForSelector page "canvas.genko-canvas" #js {:timeout 15000})
          ;; strictly sequential: each thunk is only called once the previous
          ;; check has resolved
          _ (reduce (fn [acc thunk] (p/then acc (fn [_] (thunk))))
                    (p/resolved nil)
                    (checks page errors))
          _ (.close browser)]
    (let [failed (remove :ok? @results)]
      (println)
      (println (str (count (filter :ok? @results)) "/" (count @results) " checks passed"))
      (doseq [f failed] (println " FAILED:" (:label f) "--" (:detail f)))
      (process/exit (if (seq failed) 1 0)))))

(-> (-main)
    (p/catch (fn [e] (println "harness error:" (str e)) (process/exit 1))))
