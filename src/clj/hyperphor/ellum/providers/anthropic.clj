(ns hyperphor.ellum.providers.anthropic
  (:require [hato.client :as client]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [environ.core :as env]
            [hyperphor.ellum.util :as util]))

(def default-model "claude-opus-4-8")
(def base-url "https://api.anthropic.com/v1")
(def api-version "2023-06-01")

(defn api-key []
  (or (env/env :anthropic-api-key)
      (throw (ex-info "ANTHROPIC_API_KEY not set" {}))))

(defn- base-headers []
  {"x-api-key" (api-key)
   "anthropic-version" api-version
   "content-type" "application/json"})

(defn api-get
  [path params]
  (:body (client/get (str base-url path)
                     {:query-params params
                      :headers (base-headers)
                      :as :json})))

(defn api-post
  [path body]
  (:body (client/post (str base-url path)
                      {:body (json/write-str body)
                       :content-type :application/json
                       :headers (base-headers)
                       :as :json})))

(defn api-post-stream
  [path body]
  (:body (client/post (str base-url path)
                      {:body (json/write-str body)
                       :content-type :application/json
                       :headers (base-headers)
                       :as :stream})))

(defn list-models []
  (api-get "/models" {}))

;;; Key status (rate limits) and billing. Mostly unused.

(defn admin-api-key []
  (or (env/env :anthropic-admin-api-key)
      (throw (ex-info "ANTHROPIC_ADMIN_API_KEY not set" {}))))

(defn- admin-headers []
  {"x-api-key" (admin-api-key)
   "anthropic-version" api-version
   "content-type" "application/json"})

(defn admin-api-get
  [path params]
  (:body (client/get (str base-url path)
                     {:query-params params
                      :headers (admin-headers)
                      :as :json})))

(defn- parse-long-safe [s]
  (when s (try (Long/parseLong s) (catch Exception _ nil))))

(defn- header-status [headers prefix]
  {:limit (parse-long-safe (get headers (str prefix "-limit")))
   :remaining (parse-long-safe (get headers (str prefix "-remaining")))
   :reset (get headers (str prefix "-reset"))})

(defn rate-limit-status
  "Get the current rate-limit standing for this API key by making a minimal
  Messages API call (1 output token) and reading back the anthropic-ratelimit-*
  response headers. Reflects the organization/workspace's live request and
  token budget — this is the 'status' available to a standard API key; full
  billing/cost data requires an Admin API key, see usage-report and cost-report."
  [& {:keys [model] :or {model default-model}}]
  (let [{:keys [headers]} (client/post (str base-url "/messages")
                                       {:body (json/write-str {:model model
                                                               :max_tokens 1
                                                               :messages [{:role "user" :content "hi"}]})
                                        :content-type :application/json
                                        :headers (base-headers)
                                        :as :json})]
    {:organization-id (get headers "anthropic-organization-id")
     :requests (header-status headers "anthropic-ratelimit-requests")
     :tokens (header-status headers "anthropic-ratelimit-tokens")
     :input-tokens (header-status headers "anthropic-ratelimit-input-tokens")
     :output-tokens (header-status headers "anthropic-ratelimit-output-tokens")
     :raw-headers headers}))

(defn organization-rate-limits
  "List the organization's configured rate limits (the Rate Limits API).
  Requires an Admin API key (ANTHROPIC_ADMIN_API_KEY), distinct from a
  standard API key. Optional opts: :model (look up the group for one model)
  and :group-type (\"model_group\" \"batch\" \"token_count\" \"files\" \"skills\" \"web_search\")."
  [& {:keys [model group-type]}]
  (admin-api-get "/organizations/rate_limits"
                 (cond-> {}
                   model (assoc :model model)
                   group-type (assoc :group_type group-type))))

(defn usage-report
  "Query the organization's Messages API token usage (the Usage Admin API).
  Requires an Admin API key (ANTHROPIC_ADMIN_API_KEY). params is a map of the
  Anthropic Usage API's query params, e.g.
  {:starting_at \"2026-07-01T00:00:00Z\" :ending_at \"2026-07-24T00:00:00Z\" :bucket_width \"1d\"}."
  [params]
  (admin-api-get "/organizations/usage_report/messages" params))

(defn cost-report
  "Query the organization's cost/billing data in USD (the Cost Admin API).
  Requires an Admin API key (ANTHROPIC_ADMIN_API_KEY). params is a map of the
  Anthropic Cost API's query params, e.g.
  {:starting_at \"2026-07-01T00:00:00Z\" :ending_at \"2026-07-24T00:00:00Z\"}."
  [params]
  (admin-api-get "/organizations/cost_report" params))

(defn organization-info
  "Get the organization (id/name) that an Admin API key belongs to
  (/v1/organizations/me). Requires an Admin API key (ANTHROPIC_ADMIN_API_KEY)."
  []
  (admin-api-get "/organizations/me" {}))

(defn list-api-keys
  "List API keys for the organization, including each key's name, status,
  owner (:created_by / :principal), and workspace. Requires an Admin API key
  (ANTHROPIC_ADMIN_API_KEY). opts: :status :workspace-id :created-by-user-id
  :limit :after-id :before-id."
  [& {:keys [status workspace-id created-by-user-id limit after-id before-id]}]
  (admin-api-get "/organizations/api_keys"
                 (cond-> {}
                   status (assoc :status status)
                   workspace-id (assoc :workspace_id workspace-id)
                   created-by-user-id (assoc :created_by_user_id created-by-user-id)
                   limit (assoc :limit limit)
                   after-id (assoc :after_id after-id)
                   before-id (assoc :before_id before-id))))

(defn- key-hint-matches?
  "Does an Admin API partial_key_hint (e.g. \"sk-ant-api03-R2D...igAA\") match a raw secret key?"
  [hint secret]
  (when (str/includes? hint "...")
    (let [[prefix suffix] (str/split hint #"\.\.\." 2)]
      (and (str/starts-with? secret prefix)
           (str/ends-with? secret suffix)))))

(defn find-api-key
  "Identify a specific API key — its name, status, owner, and workspace — by
  matching its secret against the organization's partial_key_hint values.
  Requires an Admin API key (ANTHROPIC_ADMIN_API_KEY) belonging to the same
  organization as secret. Returns the matching APIKey map, or nil if not found."
  [secret]
  (->> (list-api-keys :limit 1000)
       :data
       (filter #(key-hint-matches? (:partial_key_hint %) secret))
       first))

(defn normalize-models
  "Convert a raw Anthropic /models list response to a vector of normalized model maps."
  [response]
  (mapv (fn [m] {:id (:id m) :name (:display_name m) :created (:created_at m) :raw m})
        (:data response)))

;;; Normalization

(defn- content-block->text
  "Extract text from a content block, or nil if not a text block."
  [{:keys [type text]}]
  (when (= type "text") text))

(defn- content-block->tool-call
  "Convert an Anthropic tool_use block to ellum normalized tool call."
  [{:keys [type id name input]}]
  (when (= type "tool_use")
    {:id id :name name :arguments input}))

(defn- normalize-stop-reason [stop-reason]
  (case stop-reason
    "end_turn" :stop
    "tool_use" :tool-calls
    "max_tokens" :length
    "stop_sequence" :stop
    "refusal" :refusal
    (keyword stop-reason)))

(defn normalize-response
  "Convert a raw Anthropic Messages response to the ellum normalized format."
  [response]
  (let [content-blocks (:content response)
        text (->> content-blocks
                  (keep content-block->text)
                  (clojure.string/join "\n")
                  not-empty)
        tool-calls (->> content-blocks
                        (keep content-block->tool-call)
                        not-empty)
        stop-reason (normalize-stop-reason (:stop_reason response))]
    {:content text
     :refusal (when (= stop-reason :refusal)
                (or text
                    (get-in response [:stop_details :explanation])
                    "No explanation given"
                    ))
     :tool-calls tool-calls
     :stop-reason stop-reason
     :usage {:input-tokens (get-in response [:usage :input_tokens])
             :output-tokens (get-in response [:usage :output_tokens])}
     :raw response}))

;;; Serialization for requests

(defn- serialize-tool
  [{:keys [name description parameters]}]
  {:name name
   :description description
   :input_schema parameters})

(defn- message->anthropic
  "Convert an ellum message to Anthropic Messages API format.
  Handles user/assistant/tool-result messages.
  Tool results are user messages with content of type 'tool_result'."
  [{:keys [role content tool-calls tool-result-id]}]
  (cond
    ;; Tool result (from tool execution, goes back as user message)
    tool-result-id
    {:role "user"
     :content [{:type "tool_result"
                :tool_use_id tool-result-id
                :content (if (string? content) content (json/write-str content))}]}
    ;; Assistant message with tool calls — reconstruct content blocks
    (and (= role :assistant) tool-calls)
    {:role "assistant"
     :content (vec (concat
                    (when content [{:type "text" :text content}])
                    (map (fn [{:keys [id name arguments]}]
                           {:type "tool_use"
                            :id id
                            :name name
                            :input arguments})
                         tool-calls)))}
    ;; Regular message
    :else
    {:role (name role) :content content}))

;;; Main complete function

(defn complete
  "Call Anthropic Messages API.
  req keys: :model :messages :system :tools :max-tokens :stream"
  [{:keys [model messages system tools max-tokens]
    :or {model default-model max-tokens 4096}}]
  (let [body (cond-> {:model model
                      :max_tokens max-tokens
                      :messages (mapv message->anthropic messages)}
               system (assoc :system system)
               tools (assoc :tools (mapv serialize-tool tools)))]
    (-> (api-post "/messages" body)
        normalize-response)))

(defn complete-stream
  "Call Anthropic Messages API with streaming.
  Returns lazy seq of parsed SSE events."
  [{:keys [model messages system tools max-tokens]
    :or {model default-model max-tokens 4096}}]
  (let [body (cond-> {:model model
                      :max_tokens max-tokens
                      :messages (mapv message->anthropic messages)
                      :stream true}
               system (assoc :system system)
               tools (assoc :tools (mapv serialize-tool tools)))]
    (util/parse-sse-stream (api-post-stream "/messages" body))))
