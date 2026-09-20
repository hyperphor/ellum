(ns hyperphor.ellum.terms
  (:require [hyperphor.ellum.core :as core]
            [hyperphor.ellum.extract :as e]
            [hyperphor.multitool.core :as u]
            [clojure.string :as str]))

;;; Originally from ELI, but it seems generally useful

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
