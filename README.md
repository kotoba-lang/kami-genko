# kami-genko

原稿 (**genko**) manga-editor の **document model + 純ロジック**の cljc SSoT と、
それを `globalThis.KamiGenko` に生やす cljs バンドル (ADR-2607020200 / 2607020300)。

`kami-engine-sdk` の `genko-embed.ts`（WebGPU pentab エディタ = 自己完結 HTML を返す関数）が
inline JS に抱えていた

- doc / page / node **データモデル**（`{:pages [{:nodes [{:id :type :visible :data}]}]}`）
- **node-tree ops**（`all-nodes` / `find-by-nid` / `set-node-parent` / `would-cycle?` /
  `node-visible?` / `reorder-nodes` / `node-tree`）
- **oplog（event-sourcing）**（`record-op` / `replay-oplog`）
- **serialize / deserialize**（`read-doc` / `write-doc` / `normalize`）

を忠実に純 cljc へ切り出した。WebGPU 描画・DOM・B2/PDS I/O は host（TS/Svelte ランタイム、
langgraph host-fn）に残す。以前 `kotoba-lang/kami-engine` の `kami-mangaka-genko-clj`
サブディレクトリにあったものを、SDK/他消費者から使いやすいよう独立 repo に分離した。

## 使い方（cljc / JS 両面）

```clojure
(require '[kami.mangaka.genko :as g])
(-> (g/read-doc json)                 ; B2 の genko doc(JSON) を読む(parse+normalize)
    :pages first g/page->storyboard)   ; → kami.mangaka.text / analyzeExpression 形
```

```bash
# JS バンドル(globalThis.KamiGenko)をビルド — genko-embed.ts が inline して委譲する
npm install && npm run build     # → dist/kami-genko.js
node -e 'globalThis.window=globalThis;require("./dist/kami-genko.js");console.log(Object.keys(globalThis.KamiGenko))'
```

`KamiGenko` API: `readDoc / writeDoc / normalize / allNodes / findByNid / wouldCycle /
nodeVisible / nodeVisibleMap / setNodeParent / reorderNodes / nodeTree / recordOp /
replayOplog / pageToStoryboard / docToStoryboards`。境界で JS(JSON)⇄clj を変換し
verbatim round-trip。`nodeVisibleMap` は render loop 用の一括可視判定 {nid: bool}。

## 忠実点 / expression 語彙の共有

- 親子は `:_parent` 文字列ポインタ（`""`=root、`:_layer` 旧別名）、可視は `!==false`。
- text node の 3 スキーム併存、tone/fukidashi 共有リテラル等を rename せず再現。
- fukidashi 形（oval/jagged/cloud/square/wavy）は `kami.mangaka.expression` の `:bubble`
  語彙の部分集合、tone-pattern（dot/line/cross/grad）→ 背景トーンへ写像。

## テスト

```bash
clojure -M:test              # model / node-tree / cycle / visibility / reorder / JSON round-trip / oplog replay / bridge
npm run test:kotobase        # kotoba-lang/kotobase-client(deps.edn git dep)経由の node cljs.test(下記)
```

## cljs-only genko エディタ (`kami.mangaka.genko-app`)

`npx shadow-cljs release app` → `public/genko.html`。WebGL2 / reagent の全機能
エディタ（ツール一式・コマ割りプリセット・pentab 筆圧・pan/zoom・node-tree）に加え、
kotoba-server(kotobase.net) への永続を任意同期(「☁ save」/「☁ load」ボタン)として搭載。

- **識別**: actor(ブラウザ)ごとに Ed25519 鍵(`localStorage` の `genko/kotoba-identity`,
  ブラウザはファイルを書けないため JVM 版 `.actor/identity.edn` の等価物)を生成し、
  did:key を自己発行 CACAO(SIWE/CAIP-122, DAG-CBOR、`:auth-profile :apex` 既定)で
  認証する(`kotobase.{cid,cacao,client}` — `kotoba-lang/kotobase-client` への
  deps.edn git dep。旧 `src/kotobase/*.cljs` vendored copy は 2026-07-09 apex 401
  修正を機に削除)。
- **既定の自動保存は localStorage のまま**(信頼性優先、ネットワーク往復をキー入力の
  たびに走らせない)。kotoba-server 同期は明示ボタン操作。
- graph アドレスは `kotobase/db/<did>/<db-name>` の CID(AT Protocol の `at://` では
  ない)。UI 表示用に `at://<did>/kotoba.genko.doc/<db-name>` 形の識別子も用意するが、
  これはこの app 内の表示規約であり汎用 AT-URI リゾルバでの解決は想定しない。

