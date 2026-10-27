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

;; make-source: register a customized provider -- here, :anthropic with
;; GitHub's MCP server baked in, so every call gets GitHub search tools and a
;; steering system prompt without having to pass :mcp-servers each time.
;; Uses the caller's own `gh auth token` rather than a separately-managed PAT.
(require '[clojure.java.shell :as shell])

(def github-helper
  (make-source
   {:base-provider :anthropic
    :keyword :anthropic-github
    :system-prompt "You have access to the GitHub MCP server. Use its search tools to find real, current information instead of relying on memory."
    :mcp-servers [{:name "github"
                   :url "https://api.githubcopilot.com/mcp/"
                   :authorization-token (clojure.string/trim (:out (shell/sh "gh" "auth" "token")))}]}))

;; :anthropic-github (== github-helper) now works as a provider anywhere
(query :anthropic-github "Find one real GitHub repo that is a proof-of-concept for the Log4Shell vulnerability, and name it.")

;; inspect what the MCP server actually did
(:mcp-tool-uses (complete {:provider :anthropic-github
                           :messages [{:role :user :content "Search GitHub for a repo about the Heartbleed vulnerability and name one."}]}))

;; make-source with OpenAI RAG: file_search over a hosted vector store
;; (routes through the Responses API). make-rag-store uploads + indexes files.
(require '[hyperphor.ellum.providers.openai :as openai])

(def docs-store (openai/make-rag-store "my docs" ["/path/to/a.pdf" "/path/to/b.pdf"]))

(make-source
 {:base-provider :openai
  :keyword :openai-docs
  :vector-store-ids [docs-store]
  :max-num-results 12
  :system-prompt "Answer from the provided documents; say so if they don't cover it."})

(query :openai-docs "Summarize what these documents say about melanoma.")

;; which files were cited
(:citations (complete {:provider :openai-docs
                       :messages [{:role :user :content "What's new in melanoma?"}]}))

;; background mode for slow RAG queries
(let [{:keys [id]} (openai/submit-response {:vector-store-ids [docs-store]
                                            :messages [{:role :user :content "Long question..."}]})]
  (:content (openai/normalize-responses-response (openai/await-response id))))

;; organization id is available from a standard key's rate-limit headers;
;; name/owner/workspace of the key itself requires an Admin API key
;; (ANTHROPIC_ADMIN_API_KEY) — see hyperphor.ellum.providers.anthropic
(require '[hyperphor.ellum.providers.anthropic :as anthropic])
(anthropic/organization-info)
(anthropic/list-api-keys)
(anthropic/find-api-key (anthropic/api-key))

