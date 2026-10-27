(ns hyperphor.ellum.terms
  (:require [hyperphor.ellum.core :as core]
            [hyperphor.ellum.extract :as e]
            [hyperphor.multitool.core :as u]
            [hyperphor.multitool.cljcore :as ju]
            [hyperphor.multitool.graph :as graph]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

;;; Originally from ELI, but it seems generally useful.
;;; Some stuff here verges on Alzabo domain. I guess the dividing line is weheter there is an ontology or not.

;;; Note: without "of strings" sometimes returns {:concept .. :description...} which is sometime useful
(defn generate-terms
  [field n]
  (-> (core/query
         :openai
         (u/tx "Generate {{n}} important concepts from {{field}}.  Return results as a json vector of strings.")
         :model "gpt-4o"
         )
        e/extract-json
        first))

(defn diversify-terms
  [elts n]
  (let [elts (str/join ", " elts)]
    (-> (core/query
         :openai
         (u/tx "extend this set with {{n}} new elements that fit the set but maximize diversity within it: {{elts}}  Return results as json vector, omit the original elements ")
         :model "gpt-4o")
        e/extract-json
        first)))

;;; TODO find the version that took a fiction/real argument...
(defn generate-instances
  [category n]
  (-> (core/query
         :openai
         (u/tx "Generate {{n}} instances of {{category}}.  Return results as a json vector of strings.")
         :model "gpt-4o"
         )
        e/extract-json
        first))

(defn entailments
  [terms]
  (let [terms (str/join ", " terms)]
    (-> (core/query
         :openai
         (u/tx "for each term, generate the list of terms that are logically prior, or need to be understood first. Return a json map {term: [priods]...}: {{terms}}")
         :model "gpt-4o")
        e/extract-json
        first)))


;;; generalizing the above

(defn instance-relations
  "Given `instances` (a seq of strings, already generated), ask the LLM for the
  list of other instances that are `relation` to each one. Returns a json map
  {instance: [related...]}."
  [instances relation]
  (let [instances (str/join ", " instances)]
    (-> (core/query
         :openai
         (u/tx "for each element of the list, generate the list of other entries that are {{relation}} to it. Return a json map {term: [related,]...}: {{instances}}")
         :model "gpt-4o"
         :max-tokens 16000)
        (e/extract-json :key-fn identity)
        first)))

(defn category-relations
  [category relation n]
  (let [instances (generate-instances category n)]
    (prn :instances instances)
    (instance-relations instances relation)))


;;; TODO use to color nodes in the graph
(defn categorized
  [terms n]
  (-> (core/query :openai
                   (u/tx "look at all the terms and come up with {{n}} categories that they fit into, return as json map of category to list of terms: {{terms}}")
                   :max-tokens 8000)
      (e/extract-json :key-fn identity)
      first
      ))

(defn descriptions
  "Ask the LLM for a short (one- or two-sentence) glossary-style description
  of each of `terms`. Returns a json map {term: description}."
  [terms]
  (let [terms (str/join ", " terms)]
    (-> (core/query
         :openai
         (u/tx "for each term, write a concise one- or two-sentence description suitable as a glossary entry. Return a json map {term: description, ...}: {{terms}}")
         :model "gpt-4o"
         :max-tokens 16000)
        (e/extract-json :key-fn identity)
        first)))

(defn mcp-descriptions
  "Like `descriptions`, but via :anthropic with `mcp-servers` (as taken by
  core/complete's :mcp-servers — Anthropic's MCP connector, see
  design/mcp.md) attached, so the model can call out to a real external tool
  while writing descriptions. Returns {:descriptions {term description,...}
  :mcp-tool-uses [...] :mcp-tool-results [...]} — the latter two so a caller
  can confirm the server was actually used, since they're otherwise easy to
  wire up wrong and get silently-ungrounded output."
  [terms mcp-servers]
  (let [terms-str (str/join ", " terms)
        response (core/complete
                  {:provider :anthropic
                   :messages [{:role :user
                               :content
                               (u/tx "for each term, write a concise one- or two-sentence description suitable as a glossary entry. For at most 5 terms that name a specific, well-known, real-world named vulnerability or exploit, use the available search tools once each to find a genuine real-world reference (eg a PoC repo or an issue/commit discussing it) and mention what you actually found in one clause. Write the rest from your own knowledge without calling tools. Never fabricate a citation — only mention something a tool call actually returned. Return a json map {term: description, ...}: {{terms-str}}")}]
                   :mcp-servers mcp-servers
                   :max-tokens 16000})]
    {:descriptions (first (e/extract-json (:content response) :key-fn identity))
     :mcp-tool-uses (:mcp-tool-uses response)
     :mcp-tool-results (:mcp-tool-results response)}))

(defn github-mcp-server
  "GitHub's official remote MCP connector server, authenticated with the
  caller's own `gh` CLI login (via `gh auth token`) rather than requiring a
  separately-managed token. Exposes repo/issue/code search, not a dedicated
  security-advisory API — see design/mcp.md."
  []
  {:name "github"
   :url "https://api.githubcopilot.com/mcp/"
   :authorization-token (str/trim (:out (shell/sh "gh" "auth" "token")))})


(defn graph-relations
  [& args]
  (let [data (apply category-relations args)
        dir (ju/temp-dir-path)]
    ;; (u/map-invert-multiple e)
    (graph/generate! data dir)
    (ju/open-url (u/tx "file://{{dir}}/graph.html"))))



(comment
  (graph-relations "musical genres" "influenced" 100))

;;; Parsing a pre-written (eg LLM-generated) markdown term list, as opposed to
;;; generating one. Expects `## Category` headers over blocks of
;;; `N. **Term** — description` lines (see input/socialism.md).

(def ^:private term-line-re #"\d+\.\s+\*\*(.+?)\*\*\s+[—-]\s+(.+)")

(defn parse-term-list
  "Parse markdown `text` into [{:term :category :description}...].
  `:category` is the full text of the nearest preceding `## ` header."
  [text]
  (->> (str/split text #"(?m)^##\s+")
       rest
       (mapcat
        (fn [section]
          (let [[header & lines] (str/split-lines section)
                category (str/trim header)]
            (keep (fn [line]
                    (when-let [[_ term description] (re-matches term-line-re (str/trim line))]
                      {:term term :category category :description description}))
                  lines))))
       vec))

;;; Hand-assigned short keys + a fixed-order categorical palette (see
;;; dataviz skill) for the categories found in input/socialism.md. Node
;;; labels always carry the term name, so color here is a secondary cue,
;;; not the only way to tell categories apart.
(def socialism-categories
  {"Foundations: Method and Materialism"        {:key :foundations           :color "#2a78d6"}
   "Political Economy"                          {:key :political-economy     :color "#eb6834"}
   "Classes and the Working Class"              {:key :classes               :color "#1baf7a"}
   "The State and Revolution"                   {:key :state-and-revolution  :color "#eda100"}
   "The Party and Organization"                 {:key :party-and-organization :color "#e87ba4"}
   "Imperialism and the International Struggle" {:key :imperialism           :color "#008300"}
   "The Movement and Its History"               {:key :movement-history      :color "#4a3aa7"}})

(defn categorize-term-list
  "Replace each entry's full-header :category with its short keyword key,
  using `categories` (a map as in `socialism-categories`)."
  [entries categories]
  (mapv (fn [{:keys [category] :as entry}]
          (assoc entry :category (get-in categories [category :key])))
        entries))

(defn term-list->graph
  "Build a {:graph {term [related...]} :node-color (fn node -> color)
  :popup-fn (fn node -> {:category :color :description}) :legend [[label color]...]}
  from parsed `entries` (as from `parse-term-list`/`categorize-term-list`),
  `categories` (key -> {:color}), and a `relation` description for the LLM
  relation query."
  [entries categories relation]
  (let [terms (mapv :term entries)
        known? (set terms)
        term->category (into {} (map (juxt :term :category) entries))
        term->description (into {} (map (juxt :term :description) entries))
        key->color (into {} (map (fn [[_ {:keys [key color]}]] [key color]) categories))
        key->label (into {} (map (fn [[header {:keys [key]}]] [key header]) categories))
        ;; LLM occasionally answers with an abbreviated/hallucinated term not
        ;; in the source list (eg "Revolution" for "Socialist revolution"),
        ;; or includes the term itself; drop both rather than let them show
        ;; up as uncategorized orphan nodes or self-loops.
        relations-map (into {} (for [[term related] (instance-relations terms relation)]
                                  [term (vec (remove #{term} (filter known? related)))]))]
    {:graph relations-map
     :node-color (fn [node]
                   (get key->color (term->category node) "#cccccc"))
     :popup-fn (fn [node]
                 (let [category (term->category node)]
                   {:category (get key->label category "")
                    :color (get key->color category "#cccccc")
                    :description (get term->description node)}))
     :legend (for [[header {:keys [color]}] categories]
               [header color])}))

(defn graph-term-list!
  "Parse `md` as a term list, fetch relations among its terms, and write a
  colored, legended graph site (with hover-popup descriptions) to a temp
  dir, opening it in a browser. `categories` maps full header text ->
  {:key :color} (see `socialism-categories`)."
  [md categories relation]
  (let [entries (categorize-term-list (parse-term-list md) categories)
        {:keys [graph node-color popup-fn legend]} (term-list->graph entries categories relation)
        dir (ju/temp-dir-path)]
    (graph/generate!
     graph dir
     {:node-color node-color
      :popup-fn popup-fn
      :legend legend
      :index? true
      :search? true})
    (ju/open-url (u/tx "file://{{dir}}/index.html"))
    {:entries entries :graph graph :dir dir}))

(comment
  (graph-term-list! (slurp "input/socialism.md") socialism-categories "closely related to"))

;;; Fully generated pipeline: no pre-written markdown, categories and
;;; descriptions are themselves LLM-generated. See dataviz skill for the
;;; fixed-order categorical palette this slices from.
(def categorical-palette
  ["#2a78d6" "#eb6834" "#1baf7a" "#eda100" "#e87ba4" "#008300" "#4a3aa7" "#e34948"])

(defn- slugify
  [s]
  (-> s str/lower-case (str/replace #"[^a-z0-9]+" "-") (str/replace #"(^-|-$)" "") keyword))

(defn categories->meta
  "Build a {category-name {:key :color}} map (the shape `term-list->graph`
  expects) from `category->terms` (as returned by `categorized`), assigning
  a short keyword key and a fixed-order categorical color per category."
  [category->terms]
  (into {} (map (fn [[category _] color] [category {:key (slugify category) :color color}])
                category->terms
                categorical-palette)))

(def uncategorized-color "#999999")

(defn generate-term-graph
  "Generate `n` terms for `field`, sort them into `num-categories` categories,
  describe each, and fetch `relation` relations among them. Returns the same
  shape as `term-list->graph`, plus :entries, :categories, and (when
  `mcp-servers` is given) :mcp-tool-uses/:mcp-tool-results from the
  description step — see `mcp-descriptions`.
  `mcp-servers`, if given, routes the description step through
  `mcp-descriptions` (Anthropic's MCP connector) instead of plain `descriptions`."
  [field n num-categories relation & [mcp-servers]]
  (let [terms (generate-terms field n)
        known? (set terms)
        category->terms (categorized terms num-categories)
        categories (categories->meta category->terms)
        ;; as with instance-relations, the LLM can echo back a term slightly
        ;; reworded inside a category list; only trust assignments that match
        ;; a term we actually generated.
        term->category-name (into {} (for [[category cat-terms] category->terms
                                            term cat-terms
                                            :when (known? term)]
                                        [term category]))
        ;; the categorization call sometimes drops a handful of terms rather
        ;; than miscategorizing them; give those a visible fallback bucket
        ;; instead of silently losing them from the graph.
        uncategorized (remove term->category-name terms)
        categories (if (seq uncategorized)
                     (assoc categories "Uncategorized" {:key :uncategorized :color uncategorized-color})
                     categories)
        term->category-name (into term->category-name (map vector uncategorized (repeat "Uncategorized")))
        {:keys [descriptions mcp-tool-uses mcp-tool-results]}
        (if mcp-servers
          (mcp-descriptions terms mcp-servers)
          {:descriptions (descriptions terms)})
        entries (mapv (fn [term]
                         {:term term
                          :category (get-in categories [(term->category-name term) :key])
                          :description (get descriptions term)})
                       terms)]
    (merge (term-list->graph entries categories relation)
           {:entries entries :categories categories
            :mcp-tool-uses mcp-tool-uses :mcp-tool-results mcp-tool-results})))

(defn graph-generated-terms!
  "Generate `n` terms for `field`, sort into `num-categories` categories,
  describe each, fetch `relation` relations, and write a colored, legended,
  hover-popup graph site to a temp dir, opening it in a browser. See
  `generate-term-graph` for `mcp-servers`."
  [field n num-categories relation & [mcp-servers]]
  (let [{:keys [graph node-color popup-fn legend] :as result}
        (generate-term-graph field n num-categories relation mcp-servers)
        dir (ju/temp-dir-path)]
    (graph/generate!
     graph dir
     {:node-color node-color
      :popup-fn popup-fn
      :legend legend
      :index? true
      :search? true
      :width 200
      :height 200})
    (ju/open-url (u/tx "file://{{dir}}/index.html"))
    (assoc result :dir dir)))

(comment
  (graph-generated-terms! "cybernetics" 100 5 "logically prior to, or a prerequisite for understanding"))



;;; OK this is more ELI specific, but what the hell
(defn generate-personas
  [field n]
  (-> (core/query
         :openai
         (u/tx "Generate {{n}} important personas who have different levels of knowledge of {{field}}, from extremely naive to expert.  Return results as a json vector of short strings, eg \"amateur botanist\".")
         :model "gpt-4o"
         )
        e/extract-json
        first))

;;; Huh same problem as before, without the "short" instruction it is doing more than I want, but interesting

(comment
  (generate-personas "cat behavior" 4)
  ["Naive Nellie: Nellie is a young college student who recently adopted her first cat. She has limited experience with pets and is unsure about basic cat behavior. She's unaware of common cat traits and often misinterprets normal cat actions as odd or problematic."
   "Curious Chris: Chris is a middle-aged professional who has lived with a cat for the past year. He has a basic understanding of general cat behavior, such as the significance of purring and kneading, but often reads books and articles to learn more. He's eager to deepen his understanding and become a better pet owner."
   "Knowledgeable Katie: Katie is a seasoned cat owner who has cared for several cats over the past decade. She understands complex behaviors like hunting instincts, territory marking, and social dynamics among cats. Katie often advises friends on how to improve their relationships with their pets."
   "Expert Edwin: Edwin is a certified feline behaviorist who has been working with cats for over twenty years. He is familiar with advanced topics such as breed-specific behaviors, feline body language, and the psychological impacts of environmental changes. Edwin is frequently consulted by veterinarians and shelters for his expertise."])

;;; Don't really need extend-personas because extend-terms works 


(comment
  ;; Cybernetics
  (def cyber-concepts ["Feedback Loop" "Homeostasis" "Autopoiesis" "Cybernetics of Cybernetics" "Norbert Wiener" "Second-order Cybernetics" "Control Theory" "Complex Adaptive Systems" "Black Box" "Artificial Intelligence" "Self-organization" "System Dynamics" "Modeling" "Stability" "Perception-Action Cycle" "Information Theory" "Entropy" "Decision Theory" "Nonlinear Systems" "Observer Effect" "Adaptive Systems" "Robotics" "Communication Theory" "Bayesian Networks" "Dynamic Systems" "Emergence" "Interdisciplinary Approach" "Synchronization" "Machine Learning" "Artificial Neural Networks" "Viable Systems Model" "Signals and Systems" "Systemic Thinking" "Control Systems" "Feedback Control" "Redundancy" "Mathematical Modeling" "Bionics" "Ecology" "Resilience" "Artificial Life" "Cyber-physical Systems" "Human-Machine Interaction" "Complexity Theory" "Biocybernetics" "Sociocybernetics" "Cybernetics and Society" "System Identification" "Laws of Form" "Circular Causality"])

  (core/query :openai (u/tx "look at all the terms and come up with 5 categories that they fit into, return as json map of category to list of terms: {{cyber-concepts}}")))
