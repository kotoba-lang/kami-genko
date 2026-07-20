(ns kami.mangaka.genko-project
  "Storyboard page + generated layer images -> editable Genko document.
  The flattened PNG is a preview only; background, every character, props,
  SFX, balloons and lettering remain independently addressable nodes."
  (:require [clojure.string :as str]
            [kami.mangaka.genko :as g]))

(defn- rect->world [[x y w h]]
  (let [{fx1 :x1 fy1 :y1 fx2 :x2 fy2 :y2} g/youshi-safe-bounds
        fw (- fx2 fx1) fh (- fy2 fy1)]
    {:x1 (+ fx1 (* x fw)) :y1 (+ fy1 (* y fh))
     :x2 (+ fx1 (* (+ x w) fw)) :y2 (+ fy1 (* (+ y h) fh))}))

(defn- panel-rect [panel idx]
  (or (:rect panel) (:panel/rect panel)
      [0.05 (+ 0.05 (* idx 0.22)) 0.9 0.2]))

(defn- layer-node [id name parent z]
  (g/wrap-node id "layer" {:layerName name :blendMode "normal"
                            :opacity 1.0 :locked false :zIndex z} parent))

(defn- image-node [id parent rect image]
  (g/wrap-node id "ai-image"
               (merge rect
                      {:_genImage (or (:image-b64 image) (:imageB64 image) "")
                       :imageKey (:image-key image)
                       :mime (or (:mime image) "image/png")
                       :opacity (double (or (:opacity image) 1.0))
                       :blendMode (or (:blend-mode image) "normal")
                       :characterId (or (:character/id image) (:character-id image))
                       :_genPrompt (or (:prompt image) "")})
               parent))

(defn- art-nodes [pid rect render]
  (let [layers (:layers render)]
    (if (map? layers)
      (concat
       (when-let [bg (:background layers)]
         [(image-node (str pid "/background/0") (str pid "/layer/background") rect bg)])
       (map-indexed
        (fn [idx image]
          (let [cid (or (:character/id image) (:character-id image) idx)]
            (image-node (str pid "/character/" cid)
                        (str pid "/layer/character/" cid) rect image)))
        (:characters layers))
       (map-indexed #(image-node (str pid "/prop/" %1)
                                 (str pid "/layer/props") rect %2)
                    (:props layers)))
      [(image-node (str pid "/art-base") (str pid "/layer/background") rect render)])))

(defn- bubble-nodes [pid rect bubbles]
  (mapcat
   (fn [idx bubble]
     (let [bid (str pid "/bubble/" idx)
           w (- (:x2 rect) (:x1 rect)) h (- (:y2 rect) (:y1 rect))
           [rx ry] (or (:pos bubble) (:bubble/pos bubble) [0.58 0.06])
           bw (* w (double (or (:width bubble) (:bubble/width bubble) 0.36)))
           bh (* h (double (or (:height bubble) (:bubble/height bubble) 0.30)))
           x1 (max (:x1 rect) (min (+ (:x1 rect) (* rx w)) (- (:x2 rect) bw)))
           y1 (max (:y1 rect) (min (+ (:y1 rect) (* ry h)) (- (:y2 rect) bh)))
           shape (or (:shape bubble) (:bubble/shape bubble) "oval")
           text (or (:text bubble) (:bubble/text bubble) "")]
       [(g/fukidashi-node bid {:x1 x1 :y1 y1 :x2 (+ x1 bw) :y2 (+ y1 bh)
                               :fukiType (if (= shape "caption") "square" shape)
                               :fukiTail (or (:tail bubble) "bottom")
                               :parent (str pid "/layer/fukidashi")})
        (g/text-node (str bid "/text")
                     {:x (+ x1 8) :y (+ y1 8) :text text
                      :size (or (:font-size bubble) (:bubble/font-size bubble) 18)
                      :font (or (:font bubble) (:bubble/font bubble) "sans")
                      :dir (if (= "vertical-rtl" (or (:writing-mode bubble)
                                                     (:bubble/writing-mode bubble)))
                             "vertical" "horizontal")
                      :parent (str pid "/layer/lettering")})]))
   (range) bubbles))

(defn- sfx-nodes [pid rect effects]
  (mapv
   (fn [idx effect]
     (let [[rx ry] (or (:pos effect) (:sfx/pos effect) [0.2 0.2])
           w (- (:x2 rect) (:x1 rect)) h (- (:y2 rect) (:y1 rect))]
       (g/wrap-node
        (str pid "/sfx/" idx) "text"
        {:x (+ (:x1 rect) (* rx w)) :y (+ (:y1 rect) (* ry h))
         :text (or (:text effect) (:sfx/text effect) "")
         :size (* 32 (double (or (:scale effect) (:sfx/scale effect) 1.0)))
         :font (or (:font effect) (:sfx/font effect) "sans")
         :dir "horizontal" :role "sfx"
         :rotation (double (or (:rot effect) (:sfx/rot effect) 0))
         :zIndex (long (or (:z-index effect) (:sfx/z-index effect) idx))}
        (str pid "/layer/sfx"))))
   (range) effects))

(defn page->doc
  "Project `page` and parallel `renders` into a valid editable Genko document.
  A render may contain {:layers {:background image :characters [image ...]
  :props [image ...]}}; multiple people always get distinct layer parents."
  [doc-id page renders]
  (let [panels (vec (or (:panels page) (:page/panels page) []))
        nodes
        (vec
         (mapcat
          (fn [idx panel render]
            (let [pid (str doc-id "/panel/" idx)
                  rect (rect->world (panel-rect panel idx))
                  chars (vec (get-in render [:layers :characters]))
                  layers (concat [["background" 0]]
                                 (map-indexed
                                  (fn [ci image]
                                    [(str "character/" (or (:character/id image)
                                                            (:character-id image) ci))
                                     (+ 10 ci)]) chars)
                                 [["props" 30] ["effects" 40] ["sfx" 50]
                                  ["fukidashi" 60] ["lettering" 70]])]
              (concat
               [(g/panel-node pid (merge rect {:panelName (str (inc idx))}))]
               (map (fn [[name z]]
                      (layer-node (str pid "/layer/" name) name pid z)) layers)
               (art-nodes pid rect render)
               (bubble-nodes pid rect (or (:bubbles panel) (:panel/bubbles panel) []))
               (sfx-nodes pid rect (or (:sfx panel) (:panel/sfx panel) [])))))
          (range) panels renders))]
    {:name (or (:title page) (:page/title page) doc-id)
     :docId (str doc-id "/genko") :activePageIdx 0
     :pages [(g/page (str doc-id "/page")
                     (str "Page " (or (:number page) (:page/number page) 1))
                     (g/youshi (str doc-id "/youshi")) nodes)]}))

(defn- world->rect [{:keys [x1 y1 x2 y2]}]
  (let [{fx1 :x1 fy1 :y1 fx2 :x2 fy2 :y2} g/youshi-safe-bounds
        fw (- fx2 fx1) fh (- fy2 fy1)]
    [(/ (- x1 fx1) fw) (/ (- y1 fy1) fh)
     (/ (- x2 x1) fw) (/ (- y2 y1) fh)]))

(defn- descendant-of? [by-id node ancestor-id]
  (loop [pid (g/parent-of node) seen #{}]
    (cond (= pid ancestor-id) true
          (or (empty? pid) (seen pid)) false
          :else (recur (some-> (by-id pid) g/parent-of) (conj seen pid)))))

(defn- image-map [node]
  (let [d (g/node-data node)]
    {:image-b64 (:_genImage d) :image-key (:imageKey d) :mime (:mime d)
     :character/id (:characterId d) :prompt (:_genPrompt d)
     :opacity (:opacity d) :blend-mode (:blendMode d)}))

(defn doc->page
  "Reverse an edited Genko document into {:page ... :renders ...}. Panel and
  balloon coordinates, text, SFX and every image layer are read from nodes;
  therefore page->doc -> edit -> doc->page is a lossless authoring loop for
  the fields owned by this projection."
  [doc]
  (let [nodes (vec (get-in doc [:pages (or (:activePageIdx doc) 0) :nodes]))
        by-id (into {} (map (juxt g/nid-of identity)) nodes)
        panels (->> nodes (filter #(= "panel" (g/type-of %)))
                    (sort-by #(let [n (get-in % [:data :panelName])]
                                (or (parse-long (str n)) 9007199254740991))))
        projected
        (mapv
         (fn [idx panel]
           (let [pid (g/nid-of panel)
                 owned (filter #(descendant-of? by-id % pid) nodes)
                 images (filter #(= "ai-image" (g/type-of %)) owned)
                 bubbles (filter #(= "fukidashi" (g/type-of %)) owned)
                 texts (filter #(and (= "text" (g/type-of %))
                                     (not= "sfx" (get-in % [:data :role]))) owned)
                 sfx (filter #(and (= "text" (g/type-of %))
                                   (= "sfx" (get-in % [:data :role]))) owned)
                 text-by-bubble
                 (fn [bubble]
                   (let [prefix (str (g/nid-of bubble) "/text")]
                     (some #(when (= prefix (g/nid-of %)) %) texts)))
                 bubble-edn
                 (mapv (fn [b]
                         (let [d (g/node-data b) t (some-> (text-by-bubble b) g/node-data)
                               [rx ry rw rh] (world->rect d)
                               [px py pw ph] (world->rect (g/node-data panel))]
                           {:bubble/text (or (:text t) "")
                            :bubble/shape (if (= "square" (:fukiType d)) "caption" (:fukiType d))
                            :bubble/pos [(/ (- rx px) pw) (/ (- ry py) ph)]
                            :bubble/width (/ rw pw) :bubble/height (/ rh ph)
                            :bubble/font-size (:size t) :bubble/font (:font t)
                            :bubble/writing-mode (if (= "vertical" (:dir t))
                                                   "vertical-rtl" "horizontal")}))
                       bubbles)
                 sfx-edn
                 (mapv (fn [n]
                         (let [d (g/node-data n)
                               [px py pw ph] (world->rect (g/node-data panel))]
                           {:sfx/text (:text d)
                            :sfx/pos [(/ (- (:x d) px) pw) (/ (- (:y d) py) ph)]
                            :sfx/scale (/ (double (or (:size d) 32)) 32.0)
                            :sfx/rot (:rotation d) :sfx/font (:font d)
                            :sfx/z-index (:zIndex d)})) sfx)
                 backgrounds (filter #(str/includes? (g/parent-of %) "/layer/background") images)
                 characters (filter #(str/includes? (g/parent-of %) "/layer/character/") images)
                 props (filter #(str/includes? (g/parent-of %) "/layer/props") images)]
             {:panel {:panel/index idx :panel/rect (world->rect (g/node-data panel))
                      :panel/bubbles bubble-edn :panel/sfx sfx-edn}
              :render {:layers {:background (some-> backgrounds first image-map)
                                :characters (mapv image-map characters)
                                :props (mapv image-map props)}}}))
         (range) panels)]
    {:page {:page/title (:name doc)
            :page/number (inc (or (:activePageIdx doc) 0))
            :page/panels (mapv :panel projected)}
     :renders (mapv :render projected)}))
