;;; Examples – should be in test suite

(in-ns hyperphor.ellum.core)

(query :openai "How can I survive the coming end times?")
(query :anthropic "How can I survive the coming end times?"
       :model "claude-sonnet-5")

(def c (chat {:model "gpt-4o"}))
((:send! c) "well hello, what is the meaning of life?")

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
             (get-weather location)))]
  :messages [{:role :user :content "What's the weather in Chicago and Hartford?"}]})
  )
