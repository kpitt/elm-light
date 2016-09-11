(ns lt.plugins.elm-light.elm-ast
  (:require [lt.plugins.elm-light.utils :as util]
            [lt.objs.console :as console]
            [lt.object :as object]
            [lt.objs.editor :as editor]
            [lt.objs.editor.pool :as pool]
            [lt.objs.statusbar :as statusbar]
            [lt.objs.files :as files]
            [crate.binding :refer [bound]]
            [clojure.string :as s])
  (:require-macros [lt.macros :refer [defui behavior background]]))

(def elm-parser-path (files/join util/elm-plugin-dir "node" "elmparser"))
(def elm-parser (js/require elm-parser-path))


(defn- start-with? [the-str fragment]
  (when (and the-str fragment)
    (= 0 (.indexOf the-str fragment))))

(defn idx-of
  [pred coll]
  (first
    (keep-indexed
      (fn [idx x]
        (when (pred x)
          idx))
      coll)))


;; High level desc of ast structure
(comment
  [{:project "/somedir/another/projectDir"
    :file-asts [{:file "/somedir/another/projectDir/src/MyModule.elm"
                 :ast {:moduleDeclaration {}
                       :doc "some module doc"
                       :imports []
                       :declarations []}}
                {:file "/somedir/another/projectDir/elm-stuff/packages/elm-lang/core/4.0.5/src/Array.elm"
                 :ast {:moduleDeclaration {}
                       :doc "some module doc"
                       :imports []
                       :declarations []}
                 :package {:name "elm-lang/core"
                           :version "4.0.5"}}]}
   {:project "/somedir/another/projectDir2"
    :file-asts [{:file "/somedir/another/projectDir2/src/MyModule2.elm"
                 :ast {:moduleDeclaration {}
                       :doc "some module doc2"
                       :imports []
                       :declarations []}}
                {:file "/somedir/another/projectDir2/elm-stuff/packages/elm-lang/core/4.0.5/src/Array.elm"
                 :ast {:moduleDeclaration {}
                       :doc "some module doc2"
                       :imports []
                       :declarations []}
                 :package {:name "elm-lang/core"
                           :version "4.0.5"}}]}])

(defonce project-asts
  (atom []))



(defn parse-editor-sync [ed]
  (try
    (let [res (.parse elm-parser (editor/->val ed))]
      {:file (-> @ed :info :path)
       :ast (util/mod-js->clj res :keywordize-keys true)})
    (catch :default e
      (println "Parse editor contents failed" e)
      nil)))


(defn parse-editor-header-sync [ed]
  (try
    (let [res (.parse elm-parser (editor/->val ed) #js {:startRule "headerOnly"})]
       {:file (-> @ed :info :path)
        :ast (js->clj res :keywordize-keys true)})
    (catch :default e
      (println "Parse editor header block failed" e)
      nil)))

(def parse-editor-async
  (background
    (fn [obj-id  parser-path callback-behavior elm-code]
      (time (let [parser (js/require parser-path)]
              (try
                (let [res (parser/parse elm-code)]
                  (js/_send obj-id
                            callback-behavior
                            #js {:status "ok" :module res}))
                (catch :default e
                  (js/_send obj-id
                            callback-behavior
                            #js {:status "fail" :error e}))))))))



;; AST Mutants

(defn upsert-ast! [project file-ast]
  (let [prj-idx (idx-of #(= project (:project %)) @project-asts)]
     (if prj-idx
       (swap! project-asts update-in
              [prj-idx :file-asts]
              (fn [file-asts]
                (-> (filterv #(not= (:file file-ast) (:file %)) file-asts)
                    (conj file-ast))))
       (swap! project-asts conj {:project project :file-asts [file-ast]}))))


(defn delete-ast! [project file]
  (when-let [prj-idx (idx-of #(= project (:project %)) @project-asts)]
    (swap! project-asts update-in
           [prj-idx :file-asts]
           (fn [file-asts]
             (filterv #(not= file (:file %)) file-asts)))))

(defn delete-package-asts! [project]
  (when-let [prj-idx (idx-of #(= project (:project %)) @project-asts)]
    (swap! project-asts update-in
           [prj-idx :file-asts]
           (fn [file-asts]
             (vec (remove :package file-asts))))))

(defn delete-project-ast! [project]
  (swap! project-asts
         (fn [prjs]
           (filterv #(not= project (:project %)) prjs))))

(defn reset-asts! []
  (reset! project-asts []))



;; AST Queries
(defn get-project [project]
  (->> @project-asts
       (filterv #(= project (:project %)))
       first))

(defn get-module-ast [project module-file]
  (when-let [prj (get-project project)]
    (->> prj
         :file-asts
         (filterv #(= module-file (:file %)))
         first)))


(defn get-module-by-name [project module-name]
  (when-let [prj (get-project project)]
    (->> prj
         :file-asts
         (filterv #(= module-name (-> % :ast :moduleDeclaration :value)))
         first)))






;; Helpers
(defn get-module-name [module]
  (-> module :ast :moduleDeclaration :value))

(defn exposeAll? [exposing]
  (= "exposeAll" (-> exposing :exports :exports first :type)))



(defn package-import? [modules import]
  (->> (filter :package modules)
       (some #(= (:value import) (-> % :ast :moduleDeclaration :value)))))


(defn extract-exports [exposing]
  (->> exposing
       :exports
       :exports
       (map :value)))

(defn exposed-by-module? [module candidate]
  (let [exposing (-> module :ast :moduleDeclaration :exposing)]
    (if (exposeAll? exposing)
      true
      (-> (extract-exports exposing) set (contains? candidate)))))



(defn enrich-module-declarations [module]
  (let [exposing (-> module :ast :moduleDeclaration :exposing)
        expAll? (exposeAll? exposing)
        exports (-> (extract-exports exposing) set)
        exp-by-mod? #(or expAll? (contains? exports %))
        file (:file module)
        module-name (get-module-name module)
        package (:package module)]

    (->> (-> module :ast :declarations)
         (map #(assoc % :exposed?  (exp-by-mod? (:value %))
                 :file file
                 :module-name module-name
                 :package package)))))




(defn get-exposed-declarations [module]
  (->> (enrich-module-declarations module)
       (filter :exposed?)))

(defn get-import-candidate-tokens
  [imp-module-name
   imp-alias
   imp-exported-names
   imp-exports-all?
   exposed-declaration]

  (let [decl-name (:value exposed-declaration)]
        (-> #{(str imp-module-name "." decl-name )
              (when imp-alias
                (str imp-alias "." decl-name))
              (when (or imp-exports-all?
                        (contains? imp-exported-names decl-name))
                decl-name)}
            (disj nil))))


(defn get-external-candidates [module modules]
  (let [imports (-> module :ast :imports :imports)
        imported-mod-names (->> imports (map :value) set)
        curr-module-name (get-module-name module)
        external-exposed (->> modules
                              (filter #(and (not= curr-module-name (get-module-name %))
                                            (contains? imported-mod-names (get-module-name %))))
                              (mapcat #(get-exposed-declarations %))
                              (group-by :module-name))]
    (->> imports
         (mapcat
           (fn [imp]
             (let [imp-exported-names (-> (:exposing imp) -> extract-exports set)
                   imp-exports-all? (exposeAll? (:exposing imp))]
               (->> (get external-exposed (:value imp))
                    (map #(assoc % :candidate-tokens
                            (get-import-candidate-tokens (:value imp)
                                                         (:alias imp)
                                                         imp-exported-names
                                                         imp-exports-all?
                                                         %))))))))))







(defn get-default-candidates
  "Get candidates for Elm default imports as per
  https://github.com/elm-lang/core"
  [all-modules]

  (let [modules (->> (filter #(= "elm-lang/core" (-> % :package :name)) all-modules)
                     (group-by #(-> % :ast :moduleDeclaration :value)))]
    (concat
      (->> (get modules "Basics")
           (mapcat get-exposed-declarations)
           (map #(assoc % :candidate-tokens #{(:value %)}) ))
      (->> (get modules "Debug")
           (mapcat get-exposed-declarations)
           (map #(assoc % :candidate-tokens #{(str "Debug." (:value %))}) ))
      (->> (get modules "Maybe")
           (mapcat get-exposed-declarations)
           (map #(assoc % :candidate-tokens #{(str "Maybe." (:value %))}) ))
      (->> (get modules "Result")
           (mapcat get-exposed-declarations)
           (map #(assoc % :candidate-tokens #{(str "Result." (:value %))}) ))
      (->> (get modules "Platform")
           (mapcat get-exposed-declarations)
           (map #(assoc % :candidate-tokens
                   (if (= (:value %) "Program")
                     #{"Program"}
                     #{(str "Platform." (:value %))}))))
      (->> (get modules "Platfrom.Cmd")
           (mapcat get-exposed-declarations)
           (map #(assoc % :candidate-tokens
                   (case (:value %)
                     "Cmd" #{"Cmd"}
                     "!" #{"!"}
                     #{(str "Platform.Cmd." (:value %))
                       (str "Cmd." (:value %))}))))
      (->> (get modules "Platform.Sub")
           (mapcat get-exposed-declarations)
           (map #(assoc % :candidate-tokens
                   (if (= (:value %) "Sub")
                     #{"Sub"}
                     #{(str "Platform.Sub." (:value %))
                       (str "Sub." (:value %))}))))
      (->> (get modules "List")
           (mapcat get-exposed-declarations)
           (map #(assoc % :candidate-tokens
                   (if (= "::" (:value %))
                     #{"::"}
                     #{(str "List." (:value %))})))))))

(defn get-current-module-candidates [module]
  (let [mod-name (get-module-name module)]
    (->> (enrich-module-declarations module)
         (map #(assoc % :candidate-tokens #{(:value %)
                                            (str mod-name "." (:value %) ) })))))

(defn get-jump-to-candidates [module modules]
  (concat
     (get-current-module-candidates module)
     (get-external-candidates module modules)
     (get-default-candidates modules)))







(defn get-candidate-by-token [token module modules]
  (->> (get-jump-to-candidates module modules)
       (filter #(contains? (:candidate-tokens % ) token))
       first))




(defn ->pos [ast-pos]
  {:line (-> ast-pos :line dec)
   :ch (-> ast-pos :column dec)})


(defn ->range [location]
  {:start (->pos (:start location))
   :end (->pos (:end location))})


(defn- in-range? [{:keys [ch line]} decl]
  (let [{:keys [start end]} (->range (:location decl))]
    (cond
      (or (< line (:line start) )
          (> line (:line end) ))
      false

      (or (and (= (:line start) line)
               (< ch (:ch start) ))
          (and (= (:line end) line)
               (> ch (:ch end) )))
      false

      :else
      true)))



(defn find-top-level-declaration-by-pos
  ([pos module]
   (->> (:ast module)
        :declarations
        (filter #(in-range? pos %))
        first))
  ([pos project-dir module-file]
   (find-top-level-declaration-by-pos
     pos
     (get-module-ast project-dir module-file))))

(defn find-import-by-pos
  ([pos module]
   (->> (:ast module)
        :imports
        :imports
        (filter #(in-range? pos %))
        first))
  ([pos project-dir module-file]
   (find-import-by-pos
     pos
     (get-module-ast project-dir module-file))))



;; FEATURES


;; JUMP TO DEFINITION

(defn get-jump-to-definition [token module-file project-file]
  (when-let [module (get-module-ast project-file module-file)]
    (get-candidate-by-token token module (:file-asts (get-project project-file)))))



;; Autocompleter hints
(defn- to-hint [curr-module-name candidate]
  (map
    (fn [c-tok]
      {:type (:type candidate)  ; TODO: one of #{:type :definition :module} etc
       :candidate c-tok
       :module-name (when (not= curr-module-name (:module-name candidate))
                      (:module-name candidate))
       :signature (-> candidate :annotation :signatureRawSansName)})
    (:candidate-tokens candidate)))


(def dot-ex
  (js/RegExp "\\." "g"))

(defn- compare-dots [a b]
  (let [num-dots #(count (.match % dot-ex))]
    (compare (num-dots a) (num-dots b))))





(defn- declarations-hints
  [{:keys [token]} module modules]
  (->> (get-jump-to-candidates module modules)
       (filter (fn [decl]
                 (some #(= 0 (.indexOf % token))
                       (:candidate-tokens decl))))
       (mapcat (partial to-hint (get-module-name module) ))
       (filter #(= 0 (.indexOf (:candidate %) token)))
       (sort (fn [a b]
               (let [mod-sort (compare-dots (:candidate a) (:candidate b))]
                 (if (= 0 mod-sort)
                   (.localeCompare (:candidate a) (:candidate b))
                   mod-sort))))))


(defn- import-hints
  [{:keys [token pos]} imp mod-header modules]
  (let [curr-exports (->> imp :exposing :exports :exports (map :value) set)
        existing-import-names (->> mod-header :ast :imports :imports (map :value) set)]
    (cond
      ;; Return Modules Completions
      (and (not (:alias imp))
           (not (seq (-> imp :exposing :exports))))
      (->>  modules
            (map #(-> % :ast :moduleDeclaration))
            (filter #(and (= 0 (.indexOf (:value %) token))
                          (not (contains? existing-import-names (:value %)))))
            (map #(hash-map :candidate (:value %) ))
            (sort-by :candidate))

      ;; Return completions for exposing for given import
      (and (seq (-> imp :exposing :exports))
           (or (in-range? pos (-> imp :exposing :exports))
;;                (in-range? (update-in pos [:ch] #(- % (count token)))
;;                           (-> imp :exposing :exports))
               ))
      (->> (filter #(= (:value imp) (-> % :ast :moduleDeclaration :value)) modules)
           first
           get-exposed-declarations
           (map :value)
           (filter #(and (= 0 (.indexOf % token))
                         (or (= token %)  ;; TODO: Need to be a little more clever here (try on for Html.Events!)
                             (not (contains? curr-exports %)))))
           sort
           (map #(hash-map :candidate %)))

      :else [])))

(defn- module-header-hints
  "Provides hints when cursor inside exposing parens.
  Pls note that mod-header is the latest and greatest (if current editor content parses ok)"
  [{:keys [token pos]} mod-header module]

  (let [exposing (-> mod-header :ast :moduleDeclaration :exposing)
        exports-container (:exports exposing)
        curr-exports (-> (extract-exports exposing) set)
        decls (-> module :ast :declarations)]
    (cond
      (in-range? pos exports-container)
      (->> (map :value decls)
           (filter #(and (= 0 (.indexOf % token))
                         (not (contains? curr-exports %))))
           sort
           (map #(hash-map :candidate %)))
      :else
      [])))


(defn reserved-words-hints [token]
  (->> ["import" "type" "type alias" "type" "port"]
       (filter #(= 0 (.indexOf % token)))
       sort
       (map #(hash-map :candidate %))))


(defn get-hints
  "Return hints for a given module file and project.
  The context parameter provides additional info to filter results
  and to help make the results context aware to provide better more relevant results

  TODO: Only token is used and only toplevel declarations currently TBI"

  ([ctx module-file project-dir]
   (get-hints ctx module-file project-dir nil))
  ([{:keys [pos token ed] :as ctx} module-file project-dir module-header]

   (let [ed-tok (editor/->token ed pos)
         module (get-module-ast project-dir module-file)
         ;top-level-decl? (find-top-level-declaration-by-pos pos module)
         mod-header (or module-header module) ;; to cater for invalid ast
         modules (-> (get-project project-dir) :file-asts)
         imp (find-import-by-pos {:line (:line pos)
                                  :ch 0} mod-header)]

     (when module
       (cond
         (= 0 (:start ed-tok))
         (reserved-words-hints token)

         imp
         (import-hints ctx imp mod-header modules)

         (in-range? pos (-> mod-header :ast :moduleDeclaration))
         (module-header-hints ctx mod-header module)


         :else
         (declarations-hints ctx module modules))))))



;; (let [p-file "/Users/mrundberget/projects/elm-css"
;;       m-file "/Users/mrundberget/projects/elm-css/src/Css.elm"
;;       ed (first (pool/by-path m-file))]
;;   (time
;;     (doseq [x (repeat 1 "x")]
;;       (time
;;         (->> (get-hints {:token "S"
;;                          :pos {:ch 74 :line 9}
;;                          :ed ed}
;;                         m-file
;;                         p-file)
;;              count)))))


;; (let [ed (first (pool/by-path "/Users/mrundberget/projects/elm-css/src/Css.elm"))]
;;   (doseq [x (repeat 50 "x")]
;;     (time (let [p-file "/Users/mrundberget/projects/elm-css"
;;                 m-file "/Users/mrundberget/projects/elm-css/src/Css.elm"]
;;             (->> (get-hints {:token "hang"
;;                              :pos {:ch 39 :line 75}
;;                              :ed ed}
;;                             m-file
;;                             p-file)
;;                  count)))))




;; Sidebar doc search
(defn search-docs [sym project-dir]
  "Search all top level declarations for a given project.
  Currently just implements starts-with for name or qualified name"
  (let [qualified-name #(str (:module-name %) "." (:value %))]
    (when-let [prj (get-project project-dir)]
      (->> (:file-asts prj)
           (mapcat get-exposed-declarations)
           (filter
             (fn [decl]
               (or
                 (start-with? (:value decl) sym)
                 (start-with? (qualified-name decl) sym))))
           (map
             (fn [decl]
               {:name (:value decl)
                :ns (:module-name decl)
                :args (or
                        (-> decl :annotation :signatureRaw)
                        (-> decl :signatureRaw))
                :doc (:doc decl)
                :value (:value decl)
                :module-name (:module-name decl)}))
           (sort-by (juxt :value :module-name))))))


;; Get gutter marker info for a given module
(defn get-gutter-exposeds [module-file project-file]
  (when-let [module (get-module-ast project-file module-file)]
    (get-exposed-declarations module)))





;; *********** REFACTORINGS !!! ***************************************
(declare print-exports)


(defn- print-export [export]
  (str (if (= "binOpRef" (:type export))
         (str "(" (:value export) ")")
         (:value export))
       (cond

         (not (seq (:exports export)))
         ""

         :else
         (str " (" (print-exports (-> export :exports)) ")" ))))

(defn- print-exports [exports]
  (cond
    (not (seq exports))
    ""
    (= "exposeAll" (-> exports :exports first :type))
    ".."

    :else
    (->> (map print-export (:exports exports))
         (s/join ", "))))


(defn print-exposing [{:keys [exports] :as exposing}]
  (if-not (seq exports)
    ""
    (str "exposing ("
         (print-exports exports)
         ")")))



(defn print-import [imp]
  (str "import " (:value imp)
       (when-let [aliaz (:alias imp)]
         (str " as " aliaz))
       (when (seq (:exposing imp))
         (str " " (print-exposing (:exposing imp))))))

(defn print-imports
  ([imports]
   (print-imports identity imports))
  ([sort-fn imports]
   (->> (sort-fn imports)
        (map print-import)
        (s/join "\n"))))




(defn sort-imports-default [project-dir imports]
  (let [modules (-> (get-project project-dir) :file-asts)]
    (sort-by (juxt (partial package-import? modules) :value) imports)))



(defn sym-exposed?
  [sym
   project-dir
   module-file]
  (-> (get-module-ast project-dir module-file)
      (exposed-by-module? sym)))



(defn expose-decl
  "Adds an export to exposing node
  NOTE: Does not check if exposeAll or already exposed."
  [decl exposing]
  (let [value (:value decl)]
    (update-in exposing [:exports :exports]
              (fn [exports]
                (conj
                  exports
                  (case (:type decl)
                    "typeAliasDecl"
                    {:type "adt"
                     :value value}

                    "typeDecl"
                    {:type "adt"
                     :value value}

                    "binOpDef"
                    {:type "binOpRef"
                     :value value}

                    {:type "ref"
                     :value value}))))))

(defn unexpose-decl
  "Removes an export from and exposing node. Ignored if exposed through exposeAll (..)"
  [decl exposing]
  (let [value (:value decl)]
    (update-in exposing [:exports :exports]
              (fn [exports]
                (remove #(= value (:value %)) exports)))))





;; Autoimport feature
(defn get-autoimport-candidates
  "Find applicable import candidates based on token for a given module"
  [aliaz token module-file project-dir]

  (when-let [module (get-module-ast project-dir module-file)]

    (let [modules (-> (get-project project-dir) :file-asts)]

      (->> (filter #(not= module-file (:file %)) modules)
           (mapcat get-exposed-declarations)
           (concat (get-default-candidates modules))
           (filter (fn [candidate]
                     (and (= token (:value candidate))
                          (not
                            (some
                              #(and (:alias %) (= (:module-name candidate) (:value %)))
                              (-> module :ast :imports :imports))))))))))


(defn upsert-imports
  "Add import with alias or update an existing import with given alias"
  [module-name aliaz imports]
  (if-let [idx (idx-of #(= module-name (:value %)) imports)]
    (assoc-in (vec imports) [idx :alias] aliaz)
    (conj imports {:value module-name
                   :type "import"
                   :alias aliaz
                   :exposing []})))





;; (let [p-file "/Users/mrundberget/projects/elm-css"
;;       m-file "/Users/mrundberget/projects/elm-css/"]
;;   (->> (get-autoimport-candidates "P" "hyphenate" m-file p-file)
;;        (println)))



;; (let [p-file "/Users/mrundberget/projects/package.elm-lang.org"
;;       m-file "/Users/mrundberget/projects/package.elm-lang.org/src/frontend/Docs/Package.elm"]
;;   (->> (get-autoimport-candidates "P" "hyphenate" m-file p-file)
;;        (println)))






;;**********************************************************
;; AST status shown in statusbar
;;**********************************************************

(defn- status-class [{:keys [status]}]
  (case status
    :error "error"
    :ok "ok"
    ""))



(defn- status-text [{:keys [status error]}]
  (str "AST: "
       (case status
         :ok "Ok"
         :error "Error"
         "Not connected")))

(defn ->status-ui [{:keys [ast-summary]}]
  [:span {:class (str "ast-status " (status-class ast-summary))}
   (status-text ast-summary)])


(behavior ::update-ast-status
          :triggers #{:update!}
          :reaction (fn [this f]
                      (object/update! this [:ast-summary] f)))

(object/object* ::statusbar.ast-status
                :triggers #{}
                :behaviors #{::update-ast-status}
                :ast-summary {:status ""}
                :init (fn [this]
                        (statusbar/statusbar-item (bound this ->status-ui) "")))

(def statusbar-ast-summary (object/create ::statusbar.ast-status))
(statusbar/add-statusbar-item statusbar-ast-summary)



(defn update-status-for-editor [ed]
  (let [{:keys [status error]} (:ast-status @ed)]
    (object/raise statusbar-ast-summary
                  :update!
                  #(assoc % :status status :error error))))

(behavior ::update-ast-status-on-editor-change
          :triggers #{:focus}
          :reaction (fn [ed]
                      (update-status-for-editor ed)))



