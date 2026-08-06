(ns kami.mangaka.genko-theme-test
  "The host page. Two surfaces generate it from this one function — kami-genko's
  own published page and the mangaka studio Worker — so what has to hold is that
  declaring a catalog is the *only* difference between them."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [kami.mangaka.genko-theme :as theme]))

(def ^:private stub-css
  ;; The vendored DADS bundle is read by the caller; these assertions are about
  ;; what the page puts around it, so a marker string is enough and keeps the
  ;; test from depending on a file outside the classpath.
  ":root{--dds-stub:1}")

(defn- html [opts] (theme/->page-html (merge {:dds-css stub-css} opts)))

(deftest page-shape-test
  (let [out (html nil)]
    (testing "a complete document with the vendored CSS inlined"
      (is (str/starts-with? out "<!DOCTYPE html>"))
      (is (str/includes? out "--dds-stub"))
      (is (str/includes? out "lang=\"ja\"")))
    (testing "the mount node and the bundle, by RELATIVE path"
      ;; The page is served under a path prefix on every surface it has
      ;; (itonami.cloud/kotoba-lang/kami-genko/, app.itonami.cloud/mangaka/).
      ;; An absolute src would work on neither.
      (is (str/includes? out "id=\"app\""))
      (is (str/includes? out "src=\"js/genko-app.js\""))
      (is (not (str/includes? out "src=\"/js/"))))
    (testing "the chrome is server-rendered, not an empty div"
      (is (str/includes? out "genko-toolbar")))))

;; Both screens' RULES ship in either page — `app-css` is one stylesheet — so
;; these look for the rendered element, not for the class name anywhere in the
;; document. Asserting on the bare name passed while proving nothing.
(defn- renders? [out cls] (str/includes? out (str "<div class=\"" cls "\"")))

(deftest catalog-is-the-only-difference-test
  (testing "no catalog → the editor, and nothing tells the app to look for works"
    (let [out (html nil)]
      (is (not (str/includes? out "genko-catalog")))
      (is (renders? out "genko-editor"))
      (is (not (renders? out "genko-library")))))
  (testing "a catalog → the work list, declared where the app reads it"
    (let [out (html {:catalog "https://mangaka.itonami.cloud/api"})]
      (is (str/includes? out "name=\"genko-catalog\""))
      (is (str/includes? out "content=\"https://mangaka.itonami.cloud/api\""))
      (is (renders? out "genko-library"))
      (is (not (renders? out "genko-editor")))))
  (testing "the studio's first frame is already the list, not the editor"
    ;; SSR'ing the editor and then swapping to the list on boot reads as a
    ;; broken startup rather than as loading.
    (is (str/includes? (html {:catalog "api"}) "読み込んでいます"))))

(deftest page-title-follows-the-surface-test
  (is (str/includes? (html nil) "<title>原稿 genko</title>"))
  (is (str/includes? (html {:catalog "api"}) "<title>原稿スタジオ mangaka</title>"))
  (testing "the caller can still say"
    (is (str/includes? (html {:catalog "api" :title "T"}) "<title>T</title>"))))
