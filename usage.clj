;;; Examples – should be in test suite

(in-ns hyperphor.ellum.core
       (:require [hyperphor.multitool.core :as u]))

(query :openai "How can I survive the coming end times?")
(query :anthropic "How can I survive the coming end times?"
       :model "claude-sonnet-5")

(query-structured
 :anthropic "tell me about Larry Hunter the academic"
 "person"
 (object-schema
  {:name (string-schema "full name")
   :age (number-schema "age in years")
   :profession (string-schema "profession") }
  ))

(query-structured
 :anthropic "tell me about Larry Hunter the academic"
 "person"
 (object-schema
  {:name "full name"
   :age {:description "age in years" :type :number}
   :profession "profession" }
  ))

(def c (chat {:model "gpt-4o"}))
((:send! c) "remember your name is Fred")
((:send! c) "what's your name")

(run-agent
 {:provider :openai
  :model "gpt-4o"
  :tools [(make-tool
           "get_weather"
           "Get current weather for a location"
           {:type "object"
            :properties {:location {:type "string" :description "City name"}}
            :required ["location"]}
           (fn [{:keys [location]}]
             (hyperphor.ellum.tools.weather/get-weather location)))]
  :messages [{:role :user :content "What's the weather in Chicago and Hartford?"}]})






(key-status {:provider :anthropic})

;; organization id is available from a standard key's rate-limit headers;
;; name/owner/workspace of the key itself requires an Admin API key
;; (ANTHROPIC_ADMIN_API_KEY) — see hyperphor.ellum.providers.anthropic
(require '[hyperphor.ellum.providers.anthropic :as anthropic])
(anthropic/organization-info)
(anthropic/list-api-keys)
(anthropic/find-api-key (anthropic/api-key))

