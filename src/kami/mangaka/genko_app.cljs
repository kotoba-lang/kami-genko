(ns kami.mangaka.genko-app
  "cljs-only genko manga editor — standalone app (ADR-2607020300)。UI 本体は
  kami.mangaka.genko-ui のコンポーネント・ライブラリ(toolbar/tree/canvas +
  pure `step` transitions)へ切り出し済みで、この ns はそれを module-level ratom
  の state adapter で配線する thin wrapper: doc モデル(kami.mangaka.genko)+
  全ドキュメント undo/redo(shitsuke kotoba.editor)+ WebGL2 2D 描画 + ツール
  (select/draw/panel/fukidashi/tone/text)+ コマ割りプリセット + node-tree +
  localStorage 永続(export/import 併設)+ pan/zoom。genko-ui の adapter contract
  は genko-ui の ns docstring 参照(re-frame host = app-aozora も同じ components
  を使う)。

  kotoba-server(kotobase.net)への CACAO 自己発行永続(kotoba-lang/kotobase-client の
  kotobase.{cid,cacao,client} — deps.edn の git dep。旧 vendored copy は 2026-07-09、
  apex CACAO 形式 401 修正を機に削除し正本へ寄せた)を任意同期として搭載。既定の
  自動保存は引き続き localStorage(信頼性優先、
  ネットワーク往復をキー入力のたびに走らせない)。この kotobase 同期はこの
  standalone wrapper 側の持ち物で、genko-ui には入れない(host 差し込み)。"
  (:require [reagent.dom :as rdom]
            [reagent.core :as r]
            [kami.mangaka.genko :as g]
            [kami.mangaka.genko-render :as gr]
            [kami.mangaka.genko-ui :as ui]
            [kotoba.editor :as ed]
            ["@noble/curves/ed25519.js" :refer [ed25519]]
            [kotobase.cid :as kcid]
            [kotobase.cacao :as kcacao]
            [kotobase.client :as kc]))

;; ── editor state (genko-ui editor db shape; ratom host) ──────────────────────
(defonce state (r/atom (ui/initial-db)))

(defn dispatch!
  "ratom adapter の action 適用: genko-ui の pure `step` を swap! するだけ。"
  [action]
  (swap! state ui/step action))

;; ── persistence: host-injectable :save/:load port; default = localStorage ─────
;; genko doc は g/write-doc(cljs=JSON.stringify) / g/read-doc(cljs=parse+normalize)。
;; SDK/host が B2/PDS transport を後から差し込める(port は kotoba の host-injection 流儀)。
(def store-key "genko/doc")
(defn- local-save! [doc] (js/localStorage.setItem store-key (g/write-doc doc)))
(defn- local-load []
  (when-let [s (js/localStorage.getItem store-key)] (g/read-doc s)))
(defonce persist (atom {:save local-save! :load local-load}))
(defn save-doc! [] (when-let [f (:save @persist)] (f (:doc @state))))
(defn load-doc! [] (when-let [d (some-> (:load @persist) (apply []))] (dispatch! [:set-doc d])))

;; ── kotoba-server(kotobase.net) 永続: actor 自身の鍵で CACAO を自己発行 ───────────
;; 秘密鍵(32byte Ed25519 seed)は localStorage に保持(JVM 版の .<actor>/identity.edn
;; に相当するブラウザの等価物; ブラウザはファイルを書けない)。既定の自動保存には
;; 使わず(ネットワーク往復をキー入力のたびに走らせたくない)、明示操作(⛅ボタン)で
;; 呼ぶ任意同期として persist port とは別に用意する。
(def kotoba-endpoint "https://kotobase.net")
(def kotoba-db-name "genko-mangaka")
(def ^:private kotoba-identity-key "genko/kotoba-identity")

(defn- load-or-create-kotoba-secret-key! []
  (if-let [s (js/localStorage.getItem kotoba-identity-key)]
    (kcacao/base64->bytes s)
    (let [seed (.getRandomValues js/crypto (js/Uint8Array. 32))]
      (js/localStorage.setItem kotoba-identity-key (kcacao/bytes->base64 seed))
      seed)))

(defonce kotoba-secret-key (load-or-create-kotoba-secret-key!))
(defonce kotoba-did (kcid/did-key-from-ed25519-pub (.getPublicKey ed25519 kotoba-secret-key)))
(defonce kotoba-client (kc/make-client {:endpoint kotoba-endpoint
                                        :secret-key kotoba-secret-key
                                        :operator-did kotoba-did}))

;; kotoba-server の datomic 面は AT Protocol の at://did/collection/rkey では
;; なく CID(kotobase/db/<did>/<db-name>)で addressing する(実測、ADR注記参照)。
;; ユーザ向けの安定した「この doc の場所」表示としては at:// 形の識別子が
;; 読みやすいので、実ストレージキー(CID graph)とは別に表示用の規約として作る
;; (このURI自体を解決する汎用 AT Protocol リゾルバは想定しない)。
(def kotoba-at-uri (str "at://" kotoba-did "/kotoba.genko.doc/" kotoba-db-name))

;; 実測で判明した kotobase.net の datomic 面の制約(ライブ検証、2026-07-02;
;; q/datoms field 差異は 2026-07-09 の kotobase-client 移行に伴う live probe で再確認):
;; - :db/id は整数 tempid でなければならない(文字列 tempid は黙って 0 datom で
;;   no-op になる) — client_request_test.cljc の `-1` 慣習どおり。
;; - entity id は(整数 eid でなく)不透明な CID 文字列 — "最新" は eid 順序でなく
;;   自前の :genko/updated-at(ISO8601 文字列, 辞書順=時系列順)で判定する。
;; - サーバの EDN reader は文字列値中のエスケープ済みダブルクォート(\")を正しく
;;   扱えない(2属性目以降が黙って消える)。JSON を直接埋め込まず base64 で包む。
;; - `kc/q`(datalog)はこの環境で rows が空/期待 field 不一致になる(ADR-2607091800
;;   の既知の実測知見。2026-07-09 kotobase-client 移行後の live probe でも再現)。
;;   読出しは genko_store(app-aozora)と同じく `kc/datoms`(:eavt index scan)を使う。
(defn kotoba-save!
  "現在の doc を kotobase.net の operator db(kotoba-db-name)へ transact する
  (CACAO は kc/transact が呼ぶたびに自己発行、ttl 300s)。Promise を返す。"
  []
  (let [tx (str "[{:db/id -1 :genko/doc-json \"" (js/btoa (g/write-doc (:doc @state)))
               "\" :genko/updated-at \"" (.toISOString (js/Date.)) "\"}]")]
    (kc/transact kotoba-client kotoba-db-name tx)))

(defn kotoba-load!
  "kotoba-db-name を `:eavt` datoms scan で全走査し、entity(不透明な `:e`)ごとに
  ログ順 last-wins(cardinality-one スキーマが無いため再 assert は蓄積する — ADR
  注記参照)で attr map に fold、:genko/doc-json を持つ entity を
  :genko/updated-at 降順に並べ、最初にデコード成功したものを doc として復元する
  (壊れた/異物の entity が新しい timestamp を騙っていても無視して次点に
  フォールバックする)。1件も無ければ resolve(nil)。"
  []
  (-> (kc/datoms kotoba-client kotoba-db-name ":eavt")
      (.then (fn [^js res]
               (let [ds (js->clj (.-datoms res) :keywordize-keys true)
                     entities (->> ds
                                   (group-by :e)
                                   vals
                                   (map (fn [entity-datoms]
                                          (reduce (fn [m {:keys [a v_edn added]}]
                                                    (if added
                                                      (assoc m a (kc/decode-edn-scalar v_edn))
                                                      (dissoc m a)))
                                                  {} entity-datoms))))
                     newest-first (->> entities
                                       (filter #(get % ":genko/doc-json"))
                                       (sort-by #(get % ":genko/updated-at"))
                                       reverse)]
                 (some (fn [e]
                         (try (g/read-doc (js/atob (get e ":genko/doc-json")))
                              (catch :default _ nil)))
                       newest-first))))))

(defn kotoba-sync-save! []
  (swap! state assoc :kotoba-status :saving)
  (-> (kotoba-save!)
      (.then (fn [_] (swap! state assoc :kotoba-status :saved)))
      (.catch (fn [err] (js/console.error "kotoba save failed:" err)
                (swap! state assoc :kotoba-status [:error (.-message err)])))))

(defn kotoba-sync-load! []
  (swap! state assoc :kotoba-status :loading)
  (-> (kotoba-load!)
      (.then (fn [doc]
               (if doc
                 (do (dispatch! [:set-doc doc])
                     (swap! state assoc :kotoba-status :loaded))
                 (swap! state assoc :kotoba-status [:error "no doc on server"]))))
      (.catch (fn [err] (js/console.error "kotoba load failed:" err)
                (swap! state assoc :kotoba-status [:error (.-message err)])))))

;; ── adapter (genko-ui contract; ratom host + kotobase sync plugged in) ────────
(def adapter
  {:db* state
   :dispatch! dispatch!
   :sync {:save! kotoba-sync-save!
          :load! kotoba-sync-load!
          :title (str kotoba-at-uri " (" kotoba-endpoint ")")}})

;; ── 旧 API 互換の薄い便宜関数(genkoApi / 手元スクリプトが使う) ─────────────────
(defn- active-nodes [db] (ui/active-nodes db))
(defn apply-panel-preset! [preset-key] (dispatch! [:apply-preset preset-key]))
(defn toggle-vis! [nid] (dispatch! [:toggle-vis nid]))
(defn reorder-node! [from-id to-id position] (dispatch! [:reorder from-id to-id position]))
(defn do-undo! [] (dispatch! [:undo]))
(defn do-redo! [] (dispatch! [:redo]))
(defn delete-selected! [] (dispatch! [:delete-selected]))
(defn export-json! [] (ui/export-json! (:doc @state)))
(defn import-json! [] (ui/import-json! dispatch!))

;; ── boot ─────────────────────────────────────────────────────────────────────
(defn ^:export main []
  (let [cv (js/document.getElementById "gl")]
    ;; genko.html は fixed レイアウト(#bar/#side/#gl)なので composed [ui/editor]
    ;; でなく個別 component + 静的 canvas への attach-canvas! を使う。
    (ui/attach-canvas! cv adapter)
    (js/window.addEventListener "keydown"
      (fn [e] (when-let [a (ui/keydown-action e)] (dispatch! a))))
    (load-doc!) ; localStorage から復元(あれば)
    (rdom/render [ui/toolbar adapter {:title "原稿 genko (cljs)"}]
                 (js/document.getElementById "bar"))
    (rdom/render [ui/tree adapter] (js/document.getElementById "side"))
    (add-watch state :autosave (fn [_ _ old new] (when (not= (:doc old) (:doc new)) (save-doc!))))
    (set! (.-genkoState js/globalThis) state) ; browser 検証フック
    (set! (.-genkoApi js/globalThis)          ; verification helpers
          #js {:doUndo do-undo! :doRedo do-redo!
               :addPanel (fn [x1 y1 x2 y2] (dispatch! [:add-node (g/panel-node (g/gen-nid) {:x1 x1 :y1 y1 :x2 x2 :y2 y2})]))
               :addTone (fn [x1 y1 x2 y2 & [pattern]]
                          (dispatch! [:add-node (g/tone-node (g/gen-nid) {:x1 x1 :y1 y1 :x2 x2 :y2 y2 :tonePattern (or pattern "dot")})]))
               :addFukidashi (fn [x1 y1 x2 y2 & [fuki-type fuki-tail]]
                               (dispatch! [:add-node (g/fukidashi-node (g/gen-nid) {:x1 x1 :y1 y1 :x2 x2 :y2 y2
                                                                                    :fukiType (or fuki-type "oval")
                                                                                    :fukiTail (or fuki-tail "bottom")})]))
               :addStroke (fn [pts-js size] (dispatch! [:add-node (g/wrap-node (g/gen-nid) "stroke"
                                                                    {:points (js->clj pts-js :keywordize-keys true)
                                                                     :color gr/ink :size (or size 4)})]))
               :drawOpModes (fn [] (clj->js (mapv :mode (gr/draw-list (active-nodes @state) #{}))))
               :selectFirst (fn [] (when-let [n (first (active-nodes @state))] (dispatch! [:select #{(g/nid-of n)}]) true))
               :deleteSelected delete-selected!
               :nodeCount (fn [] (count (active-nodes @state)))
               :nodeTypes (fn [] (clj->js (mapv g/type-of (active-nodes @state))))
               :nodeIds (fn [] (clj->js (mapv g/nid-of (active-nodes @state))))
               :visibleNodeIds (fn [] (let [ns (active-nodes @state)]
                                         (clj->js (filterv #(g/node-visible? ns (g/nid-of %)) (mapv g/nid-of ns)))))
               :applyPreset apply-panel-preset!
               :toggleVis toggle-vis!
               :reorderNode reorder-node!
               :getViewport (fn [] (clj->js (:viewport @state)))
               :setViewport (fn [x y zoom] (swap! state assoc :viewport {:x x :y y :zoom zoom}))
               :panBy (fn [dx dy] (swap! state update :viewport gr/pan-viewport dx dy))
               :zoomAt (fn [new-zoom sx sy] (swap! state update :viewport gr/zoom-viewport new-zoom [sx sy]))
               :worldToScreen (fn [wx wy] (clj->js (gr/world->screen (:viewport @state) [wx wy])))
               :screenToWorld (fn [sx sy] (clj->js (gr/screen->world (:viewport @state) [sx sy])))
               :canUndo (fn [] (boolean (ed/can-undo? @state)))
               :canRedo (fn [] (boolean (ed/can-redo? @state)))
               :kotobaDid (fn [] kotoba-did)
               :kotobaAtUri (fn [] kotoba-at-uri)
               :kotobaGraph (fn [] (kcid/canonical-graph kotoba-did kotoba-db-name))
               :kotobaSave (fn [] (kotoba-save!))
               :kotobaLoad (fn [] (kotoba-load!))
               :kotobaClient (fn [] kotoba-client)
               :kotobaDatoms (fn [] (.then (kc/datoms kotoba-client kotoba-db-name ":eavt") clj->js))
               :kotobaQ (fn [query-edn] (.then (kc/q kotoba-client kotoba-db-name query-edn) clj->js))
               :kotobaTransact (fn [tx-edn] (.then (kc/transact kotoba-client kotoba-db-name tx-edn) clj->js))
               :kotobaPull (fn [entity pattern-edn] (.then (kc/pull kotoba-client kotoba-db-name entity pattern-edn) clj->js))})))
