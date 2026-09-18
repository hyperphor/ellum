(ns hyperphor.ellum.extract
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [hyperphor.multitool.core :as u]
            [hyperphor.ellum.util :as util]
            [hyperphor.ellum.core :as llm]
            ))

;;; Some stuff from older projects migrating here
;;; Not sure if at the right level of abstraction
;;; Waitaminute most of this is in utils already. Foo

(defn read-json-safe
  [s]
  (try
    (json/read-str s :key-fn keyword)
    (catch Throwable e
      s)))

;;; Extract code from a llm response (md).
;;; Assumes a type, assumes only one code block
;;; Lenient: accepts any label (or none) on the code fence, because models
;;; sometimes omit the language tag even when asked for Clojure.
;;; Returns [type code non-code-text]
(defn extract-code
  [s]
  (let [[m? type code] (re-find #"```(.*)\n([\s\S]*?)```" s)]
    (when m?
      [(keyword type) code (str/replace s m? "")])))

;;; TODO sometimes it returens separate ```json elements! Argh
;;; TODO most things that use this would be better off with schema-based approach
(defn extract-json
  [s]
  (let [[type code text] (extract-code s)]
    (if (= type :json)
      [(read-json-safe code) text]
      ;; Or, maybe encoded differently
      (let [xtract (or (re-find #"JSON[\s\S]*?(\[[\s\S]*\])" s)
                       (re-find  #"(?s)[\s\S]*?(\[\s*\{[\s\S]*\}\s*\])" s))] ;finds [{ ... }]
        (if xtract
          [(read-json-safe (second xtract)) s]
          nil)))))

(defn extract-clojure
  [s]
  (try                                  ;TODO pull out into macro → way
    (let [[type code text] (extract-code s)]
      (if (= type :clojure)               ;or :edn
        [(read-string code) text]         ;TODO safety
        ))
    (catch Exception e
      (throw (ex-info "Clojure extract failure" {:s s})))))  

(defn extract-edn
  [s]
  (or (extract-clojure s)
      (try                                  ;TODO pull out into macro → way
        (read-string s)
        (catch Exception e
          (throw (ex-info "EDN extract failure" {:s s})))))  
  )

;;; TODO also include a numerical score for how central the topic is to the piece as a whole
;;; TODO can add "10 most important" or something
               ;; Note: "topics" doesn't do as well as named entities (for wikipedia pages at least)
;;; TODO provider, model, prompt options
(defn extract-topics
  [text]
  (->
   (llm/complete
   {:provider :openai
    :model "gpt-4o"
    :messages [{:role "system" :content "You are a research librarian whose job is to classify documents by their key topics.  "}
               {:role "user" :content "What are the key topics, categories, and named entities in the text? Please produce a result in the form of a json list of short words or phrases. "}
               {:role "user" :content text}
               ]})
   :content
   extract-json))
