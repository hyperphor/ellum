(ns hyperphor.ellum.providers.typesafe
  (:require [hato.client :as client]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [environ.core :as env]
            [hyperphor.ellum.util :as util]))

;;; Very fast special-purpose LLM service
;;; See https://docs.typesafe.ai/api

(def base-url "https://api.typesafe.ai/v1/systemone")
(def api-key (env/env :typesafe-api-key))

(defn api-post
  [body]
  (:body (client/post base-url
                      {:body (json/write-str body)
                       :content-type :application/json
                       :headers {"Authorization" (str "Bearer " api-key)}
                       :as :json})))

;; Convenience interfaces to the 3 basic question types. Note, the API is really
;; easy to use and you call api-post directly if you want to do anything fancy.

(defn noul
  [state question]
  (-> (api-post 
       {
        :state state
        :model "jev-latest"
        :questions
        {
         :question {
                    :type :noul
                    :instructions question
                    }
         }
        })
      (get-in [:answers :question :noul])))

(comment
  (noul "I'm going to vote for donald trump because I beleive in MAGA" "is the author a moron?")
  (noul "Hi, I've been trying to connect my Stripe account for 3 days and the integration keeps failing. I'm losing sales. Please help ASAP." "does this message express urgency"))


(defn classify
  [thing question choices]
  (-> (api-post
          {
           :state thing
           :model "jev-latest"
           :questions
           {:choice {
                     :type :choice
                     :instructions question
                     :criteria (zipmap choices choices)
                     }
            }
           })
      (get-in [:answers :choice])
      #_ :choice
      ))

(comment
  (classify "malignant melanoma"
          "what kind of cancer is this?"
          ["breast cancer"
           "brain cancer"
           "skin cancer"
           "pancreatic cancer"
           ])
  )

(defn score
  [thing question levels]
  (-> (api-post
       {
        :state thing
        :model "jev-latest"
        :questions
        {:score {
                 :type :score
                 :instructions question
                 :criteria levels
                 }
         }
        })
      (get-in [:answers :score])))

(comment
  (score "The support agent was rude and never resolved my issue."
         "how satisfied is the customer?"
         ["very dissatisfied" "dissatisfied" "neutral" "satisfied" "very satisfied"]))


