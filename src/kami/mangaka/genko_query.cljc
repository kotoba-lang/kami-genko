(ns kami.mangaka.genko-query
  "genko doc/page EDN → DataScript in-memory Datalog query projection
  (owner request 2026-07-09: \"can genko be queried like kotobase.net's
  {:q :transact! :db :pull :entid} engine — i.e. :find/:where Datalog, not
  just imperative tree-walking\"). Sibling namespace of `kami.mangaka.genko`
  — does NOT modify it, only reads its public accessors (`node-data` /
  `nid-of` / `type-of` / `agent-of` / `self-visible?` / `parent-of`).

  ── Scope decision (read this before using) ─────────────────────────────
  This is a LOCAL, READ-ONLY, DERIVED query view, not a new source of
  truth and not a write-back mechanism:
  - `kami.mangaka.genko`'s nested-map doc/page/node EDN remains canonical.
    `read-doc`/`write-doc`/`normalize` round-trip exactly as before,
    completely unaffected by this namespace's existence.
  - `doc->db`/`page->db` are pure functions: doc/page EDN in, a DataScript
    db VALUE out. Nothing here mutates the source doc, nothing here is
    kept alive/live-synced — callers re-derive a db whenever they want to
    query current state (cheap: DataScript dbs are plain immutable values).
  - This is DataScript (github.com/tonsky/datascript, `datascript/datascript`
    on Clojars/Maven, `datascript` on npm) — an embedded, in-memory,
    client-side Datalog engine. It is UNRELATED to kotobase.net's own
    server-side `{:q :transact! :db :pull :entid}` datom-plane engine
    (`kotoba.db`/`kotobase-client`) used elsewhere in this monorepo for
    actual persistence — no relationship, no shared wire format, no writes
    ever flow from this namespace to kotobase.net or anywhere else.
  - No write-back path is wired (and none is planned): mutating the
    DataScript db returned by `doc->db` has zero effect on the genko doc.
    If you need to change the doc, mutate the EDN via `kami.mangaka.genko`
    as before, then call `doc->db` again for a fresh query projection.

  ── Schema ───────────────────────────────────────────────────────────────
  Every node becomes one DataScript entity:
    :node/nid     — the genko `:_nid` (`nid-of`), :db.unique/identity so a
                    query result (which is always an internal integer eid
                    or an attribute *value*, never something recognizable
                    on its own) can ALWAYS be correlated back to the
                    original node id. Also usable as a lookup ref
                    `(d/entity db [:node/nid \"n3\"])`. This is the anchor
                    the rest of the schema hangs off of — every convenience
                    query below `:find`s `?nid`, never a bare eid.
    :node/type    — the genko node type string (\"panel\"/\"prompt\"/…),
                    `:db/index true` (one node-type per node — plain
                    indexed attribute, not an enum/ref: genko's node-types
                    set is closed today but nothing here depends on that).
    :node/agent   — the `:_agent` persona string when present (`agent-of`
                    defaults to \"\" for nodes without one — we OMIT the
                    attribute entirely rather than assert \"\", so
                    `nodes-by-agent` / `(not [?e :node/agent _])` both work
                    as expected). `:db/index true` — \"find all this
                    persona's nodes\" is exactly the owner's example query.
    :node/visible — boolean (`self-visible?`), plain cardinality-one, no
                    special schema entry needed (DataScript's default for
                    an undeclared attribute already is
                    :db.cardinality/one + no index — declaring it would be
                    decorative, so it is intentionally left undeclared).
    :node/parent  — REAL `:db.type/ref` to the PARENT NODE'S OWN entity
                    (not a plain string mirror of `:_parent`). Reasoning /
                    the choice this docstring promised to document:
                      * `:node/nid` is already a :db.unique/identity, so a
                        ref expressed as a lookup ref `[:node/nid parent-nid]`
                        is the idiomatic DataScript way to model \"points at
                        another entity in this same db\" — it turns
                        ancestor/descendant into a real graph edge query
                        (`[?c :node/parent ?p]`) instead of a second
                        string-equality join, and is consistent with how
                        Datomic/DataScript schemas normally model
                        adjacency.
                      * The gotcha (found by actually testing this, not
                        assumed): DataScript resolves a lookup-ref VALUE
                        (`{:node/parent [:node/nid \"n1\"]}`) against
                        entities that already exist in the db *before* the
                        transact call, OR earlier in the SAME `d/transact!`
                        tx-data collection — it does NOT do Datomic-style
                        two-phase tempid resolution across forward
                        references. Verified empirically: transacting
                        `[{:node/nid \"n2\" :node/parent [:node/nid \"n1\"]}
                           {:node/nid \"n1\" ...}]` (child before parent in
                        the vector) throws `Nothing found for entity id
                        [:node/nid \"n1\"]`; swapping the order (or splitting
                        into two `transact!` calls) works. genko's `:nodes`
                        vector has NO guaranteed parent-before-child order
                        (`reorder-nodes` moves nodes independently of the
                        `:_parent` tree), so `doc->db`/`page->db` transact
                        every node WITHOUT `:node/parent` first, then run a
                        SECOND transaction that adds `:node/parent` ref
                        datoms once every node's `:node/nid` is already
                        committed — this sidesteps the ordering hazard
                        entirely regardless of `:nodes` order or (accidental)
                        cycles. A dangling/unresolvable parent nid (parent
                        not present in this doc/page's node set — shouldn't
                        happen in a valid genko doc, but the projection
                        stays honest rather than throwing) is simply
                        skipped for the ref; the raw string survives
                        separately (next bullet).
    :node/parent-nid — the RAW `parent-of` string (root sentinel \"\" is
                    stored too, always present, plain unindexed attribute)
                    kept alongside the ref for round-trip fidelity/
                    debugging and so root nodes (`parent-of` = \"\", which
                    is never itself a `:node/nid`, so it can't be a ref
                    target) are still queryable by string equality if
                    ever needed, without forcing every caller through the
                    ref-join shape.
    :node/page    — `:db.type/ref` to the node's page entity (`:page/id`,
                    also :db.unique/identity). Safe as a same-tx lookup
                    ref because `doc->db` always transacts a page's entity
                    map before that page's node maps in the same call.
    all other `:data` keys (`:x1`/`:y1`/`:x2`/`:y2`/`:prompt`/`:text`/
    `:fukiType`/`:fukiTail`/`:tonePattern`/`:panelName`/`:groupName`/
    `:_genImage`/`:_genPrompt`/… — i.e. anything NOT already special-cased
    above as `:type`/`:_nid`/`:_visible`/`:_parent`/`:_layer`/`:_agent`) —
    pass through generically as `:node/<name-without-leading-_>` (e.g.
    `:_genImage` → `:node/genImage`, `:x1` → `:node/x1`), left undeclared
    in the schema (plain, unindexed — the honest default; nothing here
    claims every field is commonly query-useful, only type/parent/agent/
    visible/nid are).
  Page entities: `:page/id` (:db.unique/identity), `:page/name`,
  `:page/idx`, `:page/youshi-type`. Doc-level (only via `doc->db`, not
  `page->db`): a single entity `:doc/name` / `:doc/active-page-idx`.

  ── Usage ────────────────────────────────────────────────────────────────
    (require '[kami.mangaka.genko-query :as gq]
              '[datascript.core :as d])
    (def db (gq/doc->db doc))
    (d/q '[:find ?nid :where [?e :node/type \"panel\"] [?e :node/nid ?nid]] db)
    (gq/nodes-of-type db \"panel\")           ; => #{\"n1\" …}
    (gq/children-of db \"n1\")                ; => #{\"n2\" \"n4\" \"n6\" …}
    (gq/nodes-by-agent db \"shonen\")         ; => #{…}
  "
  (:require [clojure.string :as str]
            [datascript.core :as d]
            [kami.mangaka.genko :as genko]))

;; ---------------------------------------------------------------------------
;; Schema (see namespace docstring for the reasoning behind each choice)
;; ---------------------------------------------------------------------------

(def schema
  {:node/nid    {:db/unique :db.unique/identity}
   :node/type   {:db/index true}
   :node/agent  {:db/index true}
   :node/parent {:db/valueType :db.type/ref :db/index true}
   :node/page   {:db/valueType :db.type/ref}
   :page/id     {:db/unique :db.unique/identity}})

;; ---------------------------------------------------------------------------
;; doc/page EDN → tx-data
;; ---------------------------------------------------------------------------

;; :data keys already mapped to their own dedicated attribute above — excluded
;; from the generic pass-through so we don't also emit a redundant/confusing
;; `:node/_agent`-ish duplicate.
(def ^:private reserved-data-keys
  #{:type :_nid :_visible :_parent :_layer :_agent})

(defn- data-key->attr
  "Generic :data key → :node/* attribute, stripping one leading `_` so
  `:_genImage` reads as `:node/genImage` (debuggable, close to the source
  field name) rather than an awkward `:node/_genImage`."
  [k]
  (let [n (name k)]
    (keyword "node" (if (str/starts-with? n "_") (subs n 1) n))))

(defn- node->tx-map
  "One genko node wrapper → a DataScript entity tx-map (no :node/parent yet
  — see docstring on why that's a deliberate second-phase transaction)."
  [n]
  (let [d       (genko/node-data n)
        nid     (genko/nid-of n)
        typ     (genko/type-of n)
        agent   (genko/agent-of n)
        vis     (genko/self-visible? n)
        pnid    (genko/parent-of n)
        ;; DataScript rejects nil attribute values, so drop any :data entry
        ;; whose value is nil (e.g. an unset optional field) rather than
        ;; assert it — same reasoning as the :doc/name guard below.
        passthrough (into {}
                          (keep (fn [[k v]]
                                  (when (and (not (contains? reserved-data-keys k))
                                             (some? v))
                                    [(data-key->attr k) v])))
                          d)]
    (merge passthrough
           {:node/nid nid
            :node/type typ
            :node/visible vis
            :node/parent-nid pnid}
           (when (not-empty agent) {:node/agent agent}))))

(defn- page-tx-data
  "One page → [page-entity-map node-entity-map …], page map FIRST so
  `:node/page [:page/id pid]` lookup-refs on the node maps resolve against
  an already-asserted entity earlier in the same tx-data vector."
  [page-idx page]
  (let [pid (:id page)
        page-map (cond-> {:page/id pid :page/idx page-idx}
                   (some? (:name page)) (assoc :page/name (:name page))
                   (some? (get-in page [:youshi :type]))
                   (assoc :page/youshi-type (get-in page [:youshi :type])))
        node-maps (mapv (fn [n] (assoc (node->tx-map n) :node/page [:page/id pid]))
                        (:nodes page))]
    (into [page-map] node-maps)))

(defn- link-parents!
  "Second transaction: assert :node/parent ref datoms once every node's
  :node/nid is already committed (order-independent — see docstring)."
  [conn all-nodes]
  (let [known-nids (into #{} (map genko/nid-of) all-nodes)
        parent-tx (into []
                        (keep (fn [n]
                                (let [nid  (genko/nid-of n)
                                      pnid (genko/parent-of n)]
                                  (when (contains? known-nids pnid)
                                    [:db/add [:node/nid nid] :node/parent [:node/nid pnid]]))))
                        all-nodes)]
    (when (seq parent-tx)
      (d/transact! conn parent-tx))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn doc->db
  "genko doc (EDN, `kami.mangaka.genko` shape) → a DataScript db VALUE with
  one entity per page and one entity per node across ALL pages, plus a
  single doc-level entity (:doc/name :doc/active-page-idx). Pure: does not
  touch the input doc, does not keep anything live — call again after the
  doc changes to get a fresh projection."
  [doc]
  (let [pages (:pages doc)
        ;; DataScript rejects nil attribute values outright (:name may be nil
        ;; for the synthetic single-page doc `page->db` builds) — omit rather
        ;; than assert nil.
        doc-map (cond-> {:doc/active-page-idx (:activePageIdx doc 0)}
                  (some? (:name doc)) (assoc :doc/name (:name doc)))
        page-txs (mapcat page-tx-data (range) pages)
        conn (d/create-conn schema)]
    (d/transact! conn (into [doc-map] page-txs))
    (link-parents! conn (mapcat :nodes pages))
    @conn))

(defn page->db
  "Single genko page (EDN, `kami.mangaka.genko` `page` shape) → a DataScript
  db VALUE with one entity per node on that page. Implemented as `doc->db`
  on a synthetic single-page doc (no :doc-level entity content beyond the
  defaults) so both entry points share one code path."
  [page]
  (doc->db {:name nil :activePageIdx 0 :pages [page]}))

;; ---------------------------------------------------------------------------
;; Convenience queries — each a real `d/q` Datalog query (not a Clojure
;; filter over the source EDN) to prove the db is genuinely queryable.
;; ---------------------------------------------------------------------------

(defn nodes-of-type
  "All :node/nid of nodes whose :node/type = `type` (a genko node-type
  string, e.g. \"panel\")."
  [db type]
  (into #{} (map first)
        (d/q '[:find ?nid
               :in $ ?type
               :where [?e :node/type ?type]
                      [?e :node/nid ?nid]]
             db type)))

(defn children-of
  "All :node/nid whose :node/parent points at the node identified by
  `parent-nid` (a real ref-graph join via :node/nid → :node/parent, not a
  string-equality scan of :node/parent-nid)."
  [db parent-nid]
  (into #{} (map first)
        (d/q '[:find ?nid
               :in $ ?parent-nid
               :where [?p :node/nid ?parent-nid]
                      [?c :node/parent ?p]
                      [?c :node/nid ?nid]]
             db parent-nid)))

(defn nodes-by-agent
  "All :node/nid of nodes whose :_agent (persona) = `agent`, e.g. \"shonen\"."
  [db agent]
  (into #{} (map first)
        (d/q '[:find ?nid
               :in $ ?agent
               :where [?e :node/agent ?agent]
                      [?e :node/nid ?nid]]
             db agent)))
