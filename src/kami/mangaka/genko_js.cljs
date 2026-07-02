(ns kami.mangaka.genko-js
  "ClojureScript interop bridge — exposes kami.mangaka.genko (the cljc genko
  document-model SSoT) as `globalThis.KamiGenko` so genko-embed.ts の inline JS
  ランタイムが、自前の JS 再実装ではなく cljc を呼べる (ADR-2607020200)。

  境界で JS(JSON) ⇄ clj を変換する: genko doc は JSON 形の keyword-key data なので
  `js->clj :keywordize-keys true` / `clj->js` で verbatim round-trip する
  (:activePageIdx :_nid :fukiType :x1 …)。GPU 描画・DOM・network は inline JS 側の
  host interop に残り、model/tree/oplog/serialize/bridge のみ本 API に委譲する。

  shadow-cljs :genko build の init-fn = `install!`。release 出力 (dist/kami-genko.js)
  を genko-embed.ts が inline `<script>` で読み込むと globalThis.KamiGenko が生える。

  KamiGenko.editor は shitsuke の kotoba.editor(portable editor kernel) を用いた
  全ドキュメント snapshot undo/redo (ADR-2607020300, 移植段階1)。undo/redo の stack は
  この bundle 内 atom に保持し、JS 越しは doc-JSON 文字列のみ受け渡す(全 doc を毎回
  marshal しない)。genko の stroke 限定 undo を全ドキュメント undo に格上げする。"
  (:require [kami.mangaka.genko :as g]
            [kotoba.editor :as ed]))

(defn- <-js [x] (js->clj x :keywordize-keys true))
(defn- ->js [x] (clj->js x))

;; all-nodes / node-tree の row は :has-children(kebab) を持つので camelCase の
;; JS へ明示変換 (inline JS は hasChildren を読む)。
(defn- row->js [row]
  #js {:gi (:gi row) :nid (:nid row) :par (:par row) :vis (:vis row)
       :type (:type row) :kind (:kind row) :idx (:idx row) :nm (:nm row)
       :agent (:agent row) :hasChildren (:has-children row)
       ;; ref は genko runtime が期待する raw payload (wrapper の :data)。renderNodeRow は
       ;; node.ref.type / _genImage / _href / _subtitle を読む (display のみ、mutate なし)。
       :ref (->js (get-in row [:ref :data]))})

(defn- tree->js [nodes]
  (->> nodes
       (map (fn [{:keys [node children]}]
              #js {:node (row->js node) :children (tree->js children)}))
       into-array))

;; ── exported API (JS-friendly) ──────────────────────────────────────────────

(defn ^:export readDoc [json-or-obj]
  (->js (g/read-doc (if (string? json-or-obj) json-or-obj (<-js json-or-obj)))))

(defn ^:export writeDoc [doc] (g/write-doc (<-js doc)))
(defn ^:export normalize [doc] (->js (g/normalize (<-js doc))))
(defn ^:export validDoc [doc] (g/valid-doc? (<-js doc)))

(defn ^:export allNodes [nodes]
  (into-array (map row->js (g/all-nodes (<-js nodes)))))

(defn ^:export findByNid [nodes id] (->js (g/find-by-nid (<-js nodes) id)))
(defn ^:export wouldCycle [nodes child-id parent-id]
  (g/would-cycle? (<-js nodes) child-id parent-id))
(defn ^:export nodeVisible [nodes id] (g/node-visible? (<-js nodes) id))
(defn ^:export setNodeParent [nodes child-id parent-id]
  (->js (g/set-node-parent (<-js nodes) child-id parent-id)))
(defn ^:export reorderNodes [nodes from-id to-id position]
  (->js (g/reorder-nodes (<-js nodes) from-id to-id position)))
(defn ^:export nodeTree [nodes] (tree->js (g/node-tree (<-js nodes))))

(defn ^:export recordOp
  ([oplog type data page] (recordOp oplog type data page 0))
  ([oplog type data page t] (->js (g/record-op (<-js oplog) type (<-js data) page t))))
(defn ^:export replayOplog
  ([ops] (replayOplog ops nil))
  ([ops base] (->js (g/replay-oplog (<-js ops) (<-js base)))))

(defn ^:export pageToStoryboard [page] (->js (g/page->storyboard (<-js page))))
(defn ^:export docToStoryboards [doc] (->js (g/doc->storyboards (<-js doc))))

;; ── editor history: kotoba.editor snapshot undo/redo over the genko doc-JSON ──
;; state = {:doc <jsonstring> :undo-stack [] :redo-stack []}; snapshot = (:doc)。
;; push は「直前の :doc(=baseline) を undo に積み、:doc を新 doc に更新」。
(defonce ^:private hist (atom {:doc nil :undo-stack [] :redo-stack []}))
(defn- snap [db] (ed/snapshot db [:doc]))
(defn ^:export editorInit [doc-json] (reset! hist {:doc doc-json :undo-stack [] :redo-stack []}) nil)
(defn ^:export editorPush [doc-json]
  (swap! hist (fn [db] (-> (ed/push-undo db (snap db) ed/default-history-limit)
                           (assoc :doc doc-json))))
  nil)
(defn ^:export editorUndo []
  (let [db @hist]
    (when (ed/can-undo? db)
      (let [db2 (ed/undo db snap ed/restore)] (reset! hist db2) (:doc db2)))))
(defn ^:export editorRedo []
  (let [db @hist]
    (when (ed/can-redo? db)
      (let [db2 (ed/redo db snap ed/restore)] (reset! hist db2) (:doc db2)))))
(defn ^:export editorCanUndo [] (boolean (ed/can-undo? @hist)))
(defn ^:export editorCanRedo [] (boolean (ed/can-redo? @hist)))

(def ^:export editor
  #js {:init editorInit :push editorPush :undo editorUndo :redo editorRedo
       :canUndo editorCanUndo :canRedo editorCanRedo})

(def ^:export api
  #js {:readDoc readDoc :writeDoc writeDoc :normalize normalize :validDoc validDoc
       :allNodes allNodes :findByNid findByNid :wouldCycle wouldCycle
       :nodeVisible nodeVisible :setNodeParent setNodeParent :reorderNodes reorderNodes
       :nodeTree nodeTree :recordOp recordOp :replayOplog replayOplog
       :pageToStoryboard pageToStoryboard :docToStoryboards docToStoryboards
       :editor editor})

(defn ^:export install! []
  (set! (.-KamiGenko js/globalThis) api)
  api)
