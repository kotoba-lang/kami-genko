(ns kami.mangaka.genko-project-test
  (:require [clojure.test :refer [deftest is]]
            [kami.mangaka.genko :as g]
            [kami.mangaka.genko-project :as project]))

(deftest projects-multiple-characters-and-compose-layers
  (let [doc (project/page->doc
             "gh-p0"
             {:page/number 0
              :page/panels
              [{:panel/rect [0.05 0.05 0.9 0.9]
                :panel/bubbles [{:bubble/text "起動した" :bubble/pos [0.6 0.05]}]
                :panel/sfx [{:sfx/text "カタ" :sfx/pos [0.2 0.7]}]}]}
             [{:layers {:background {:image-b64 "BG"}
                        :characters [{:character/id "ren" :image-b64 "REN"}
                                     {:character/id "nei" :image-b64 "NEI"}]
                        :props [{:image-b64 "PHONE"}]}}])
        nodes (get-in doc [:pages 0 :nodes])
        by-id (into {} (map (juxt :id identity)) nodes)]
    (is (g/valid-doc? doc))
    (is (contains? by-id "gh-p0/panel/0/layer/character/ren"))
    (is (contains? by-id "gh-p0/panel/0/layer/character/nei"))
    (is (= "gh-p0/panel/0/layer/character/ren"
           (g/parent-of (by-id "gh-p0/panel/0/character/ren"))))
    (is (= "gh-p0/panel/0/layer/props"
           (g/parent-of (by-id "gh-p0/panel/0/prop/0"))))
    (is (= "gh-p0/panel/0/layer/sfx"
           (g/parent-of (by-id "gh-p0/panel/0/sfx/0"))))
    (is (= "gh-p0/panel/0/layer/lettering"
           (g/parent-of (by-id "gh-p0/panel/0/bubble/0/text"))))))

(deftest edited-genko-round-trips-to-page
  (let [doc (project/page->doc
             "rt" {:page/number 1 :page/panels
                   [{:panel/rect [0.1 0.1 0.8 0.8]
                     :panel/bubbles [{:bubble/text "前" :bubble/pos [0.6 0.1]}]
                     :panel/sfx [{:sfx/text "カタ"}]}]}
             [{:layers {:background {:image-b64 "BG"}
                        :characters [{:character/id "ren" :image-b64 "REN"}]}}])
        nodes (get-in doc [:pages 0 :nodes])
        edited (assoc-in doc [:pages 0 :nodes]
                         (mapv #(if (= "rt/panel/0/bubble/0/text" (:id %))
                                  (assoc-in % [:data :text] "編集後") %) nodes))
        out (project/doc->page edited)]
    (is (= "編集後" (get-in out [:page :page/panels 0 :panel/bubbles 0 :bubble/text])))
    (is (= "REN" (get-in out [:renders 0 :layers :characters 0 :image-b64])))
    (is (= "カタ" (get-in out [:page :page/panels 0 :panel/sfx 0 :sfx/text])))
    (is (< (Math/abs (- 0.1 (get-in out [:page :page/panels 0 :panel/rect 0]))) 1.0e-9))))
