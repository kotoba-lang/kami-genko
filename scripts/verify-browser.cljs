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
(def url (or (.. process -env -GENKO_URL) "http://localhost:8737/genko/"))

(def channel
  "`GENKO_BROWSER_CHANNEL=chrome` runs against the machine's installed Chrome
  instead of playwright's own bundled build.

  Needed because that bundle is not always obtainable: on this workstation
  (2026-08-06) `playwright install chromium-headless-shell` downloads all 92 MiB
  and then extracts only ABOUT and LICENSE.headless_shell — four attempts, no
  binary, exit 0 each time. A check that cannot be run reports nothing, which
  reads exactly like a check that passed, so the escape hatch is worth more than
  the uniformity of always using the pinned build.

  Default stays unset: the pinned build is the reproducible one, and Chrome
  tracks stable on its own schedule. When this is used, say so in the run's
  report — it is a different browser than CI would use."
  (some-> (.. process -env -GENKO_BROWSER_CHANNEL) not-empty))

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

   ;; The frame is bounded and the controls are above the fold.
   ;;
   ;; Not "the page never scrolls": that is only true where the editor IS the
   ;; page. Published through cloud-itonami's sites plane the page also carries
   ;; the shared header, breadcrumb and footer, so it scrolls by design and the
   ;; editor is deliberately bounded to leave room for them. What has to hold on
   ;; both surfaces is that the frame does not exceed the viewport and the
   ;; toolbar is reachable without scrolling — those are the things that break.
   (fn [] (p/let [m (.evaluate page "(() => { const a = document.querySelector('.genko-editor'); const r = a.getBoundingClientRect(); const t = document.querySelector('.genko-toolbar').getBoundingClientRect(); return {h: Math.round(r.height), vh: window.innerHeight, chrome: !!document.querySelector('.itonami-chrome'), toolbarBottom: Math.round(t.bottom), scrolls: document.documentElement.scrollHeight > window.innerHeight + 1}; })()")]
            (let [m (js->clj m :keywordize-keys true)]
              (check! "frame is bounded and the toolbar is above the fold"
                      (and (<= (:h m) (:vh m))
                           (<= (:toolbarBottom m) (:vh m))
                           ;; only the standalone page must not scroll
                           (or (:chrome m) (not (:scrolls m))))
                      (pr-str m)))))

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

   ;; SCREEN SWITCH — the delegated listeners live on the root element, and the
   ;; root is now shared by two screens (`view/app-view` renders the library or
   ;; the editor from `:screen`). If a switch replaced that element, every
   ;; listener would be left on a detached node and the app would render
   ;; perfectly while responding to nothing — the one failure mode with no
   ;; visual tell. This page declares no catalog, so the screen is driven
   ;; directly through the state atom rather than through a work list.
   (fn [] (p/let [_ (.evaluate page "globalThis.genkoApi.showLibrary()")
                  _ (settle page)
                  lib (.evaluate page "!!document.querySelector('.genko-library')")
                  s (.evaluate page "globalThis.genkoApi.screen()")]
            (check! "screen switches to the library" (and lib (= "library" (str s)))
                    (str "dom=" lib " screen=" s))))

   (fn [] (p/let [_ (.evaluate page "globalThis.genkoApi.showEditor()")
                  _ (settle page)
                  ed (.evaluate page "!!document.querySelector('.genko-editor')")
                  cv (.evaluate page "(() => { const c = document.querySelector('canvas.genko-canvas'); return !!(c && c.getContext('webgl2')); })()")]
            (check! "screen switches back, canvas is live again" (and ed cv)
                    (str "editor=" ed " canvas=" cv))))

   ;; ...and the click delegation still reaches dispatch! after the round trip.
   (fn [] (p/let [_ (.click page "[data-act='tool/tone']")
                  _ (settle page)
                  pressed (.getAttribute (.first (.locator page "[data-act='tool/tone']")) "aria-pressed")]
            (check! "delegated click still works after a screen round trip"
                    (= "true" (str pressed)) (pr-str pressed))))

   ;; PAGE NAVIGATION — `:activePageIdx` was in the doc model from the start but
   ;; nothing could move it, so a multi-page 原稿 opened and showed page 1 only.
   (fn [] (p/let [before (.evaluate page "globalThis.genkoApi.pageCount()")
                  _ (.evaluate page "globalThis.genkoApi.addPage()")
                  _ (settle page)
                  after (.evaluate page "globalThis.genkoApi.pageCount()")
                  active (.evaluate page "globalThis.genkoApi.activePage()")]
            (check! "adding a page lands you on the page you added"
                    (and (= after (inc before)) (= active (dec after)))
                    (str before " -> " after " active=" active))))

   (fn [] (p/let [_ (.click page "[data-act='set-page/0']")
                  _ (settle page)
                  active (.evaluate page "globalThis.genkoApi.activePage()")
                  nodes (.evaluate page "globalThis.genkoApi.nodeCount()")]
            ;; nodeCount reads the ACTIVE page, so this also proves the canvas
            ;; and the tree followed the switch rather than the model alone.
            (check! "delegated click: ◀ turns back to the first page"
                    (= 0 active) (str "active=" active " nodes=" nodes))))

   ;; Nothing above may have logged an error along the way.
   (fn [] (p/resolved (check! "no page errors or console errors"
                              (empty? @errors) (pr-str @errors))))])

(defn -main []
  (when channel (println "browser channel:" channel "(not playwright's pinned build)"))
  (p/let [browser (.launch (.-chromium pw)
                           (clj->js (cond-> {:headless true}
                                      channel (assoc :channel channel))))
          page (.newPage browser)
          errors (atom [])
          _ (.on page "pageerror" (fn [e] (swap! errors conj (str e))))
          ;; Cloudflare injects its own Real User Monitoring beacon at
          ;; /cdn-cgi/rum, which 404s on zones where RUM is not enabled. It is
          ;; the platform's request, not the app's, and a check that fails on
          ;; every deploy is a check people learn to ignore — so it is excluded
          ;; by name, not by loosening the check.
          ;; /favicon.ico is the same shape of exclusion: the BROWSER asks for it
          ;; on every navigation whether or not the page links one, so on a bare
          ;; static server it 404s regardless of the app. The published surface
          ;; does serve one (itonami.cloud/favicon.ico → 200, checked 2026-08-06),
          ;; so failing on it here would only ever report the test server.
          platform-noise? (fn [t] (re-find #"cdn-cgi|/favicon\.ico" (str t)))
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
