(ns hyperphor.ellum.providers.openai
  (:require [hato.client :as client]
            [clojure.data.json :as json]
            [environ.core :as env]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [hyperphor.ellum.util :as util]))

(def default-model "gpt-4.1")
(def base-url "https://api.openai.com/v1")

(defn api-key []
  (or (env/env :openai-api-key)
      (throw (ex-info "OPENAI_API_KEY not set" {}))))

(defn- auth-headers []
  {"Authorization" (str "Bearer " (api-key))
   "OpenAI-Beta" "assistants=v2"})

(defn api-get
  [path params]
  (:body (client/get (str base-url path)
                     {:query-params params
                      :headers (auth-headers)
                      :as :json})))

(defn api-post
  [path body]
  (:body (client/post (str base-url path)
                      {:body (json/write-str body)
                       :content-type :application/json
                       :headers (auth-headers)
                       :as :json})))

(defn api-post-stream
  [path body]
  (:body (client/post (str base-url path)
                      {:body (json/write-str body)
                       :content-type :application/json
                       :headers (auth-headers)
                       :as :stream})))

(defn list-models []
  (api-get "/models" {}))

(defn normalize-models
  "Convert a raw OpenAI /models list response to a vector of normalized model maps."
  [response]
  (mapv (fn [m] {:id (:id m) :created (:created m) :raw m})
        (:data response)))

;;; Normalization

(defn- normalize-tool-call
  [{:keys [id type function]}]
  {:id id
   :name (:name function)
   :arguments (util/read-json-safe (:arguments function))})

(defn- normalize-stop-reason
  [finish-reason]
  (case finish-reason
    "stop" :stop
    "tool_calls" :tool-calls
    "length" :length
    "content_filter" :content-filter
    (keyword finish-reason)))

(defn normalize-response
  "Convert a raw OpenAI chat completions response to the ellum normalized format.
  A model refusal (message.refusal set, e.g. under structured outputs) is surfaced
  as :refusal with :stop-reason :refusal, since finish_reason alone doesn't signal it."
  [response]
  (let [choice (get-in response [:choices 0])
        message (:message choice)
        refusal (:refusal message)
        finish-reason (normalize-stop-reason (:finish_reason choice))
        tool-calls (some->> (:tool_calls message)
                            (mapv normalize-tool-call)
                            not-empty)]
    {:content (:content message)
     :refusal refusal
     :tool-calls tool-calls
     :stop-reason (if refusal :refusal finish-reason)
     :usage {:input-tokens (get-in response [:usage :prompt_tokens])
             :output-tokens (get-in response [:usage :completion_tokens])}
     :raw response}))

;;; Serialization for requests

(defn- serialize-tool
  [{:keys [name description parameters]}]
  {:type "function"
   :function {:name name
              :description description
              :parameters parameters}})

(defn- messages->openai
  "Convert normalized ellum messages to OpenAI format.
  ellum messages can have :role :user/:assistant/:tool/:system and :content.
  Tool result messages have :tool-call-id."
  [messages]
  (mapv (fn [msg]
          (cond-> {:role (name (:role msg))}
            (:content msg) (assoc :content (:content msg))
            (:tool-calls msg) (assoc :tool_calls
                                     (mapv (fn [tc]
                                             {:id (:id tc)
                                              :type "function"
                                              :function {:name (:name tc)
                                                         :arguments (json/write-str (:arguments tc))}})
                                           (:tool-calls msg)))
            (:tool-call-id msg) (assoc :tool_call_id (:tool-call-id msg))))
        messages))

;;; Main complete function

;; o-series and gpt-5.x ("reasoning") models reject `max_tokens` on chat
;; completions -- they want `max_completion_tokens` instead.
(defn- reasoning-model? [model]
  (boolean (re-find #"^(o\d|gpt-5)" model)))

(defn- max-tokens-key [model]
  (if (reasoning-model? model) :max_completion_tokens :max_tokens))

(declare complete-responses complete-responses-stream)

(defn complete
  "Call OpenAI chat completions API, or the Responses API (see `complete-responses`)
  when :vector-store-ids is given.
  req keys: :model :messages :system :tools :max-tokens :response-format :vector-store-ids"
  [{:keys [model messages system tools max-tokens response-format vector-store-ids]
    :or {model default-model max-tokens 4096}
    :as req}]
  (if vector-store-ids
    (complete-responses req)
    (let [system-msg (when system [{:role "system" :content system}])
          all-messages (concat system-msg messages)
          body (cond-> {:model model
                        :messages (messages->openai all-messages)
                        (max-tokens-key model) max-tokens}
                 tools (assoc :tools (mapv serialize-tool tools))
                 response-format (assoc :response_format response-format))]
      (-> (api-post "/chat/completions" body)
          normalize-response))))

(defn complete-stream
  "Call OpenAI chat completions API with streaming. Returns lazy seq of parsed SSE events.
  With :vector-store-ids, streams the Responses API instead (different event shapes)."
  [{:keys [model messages system tools max-tokens vector-store-ids]
    :or {model default-model max-tokens 4096}
    :as req}]
  (if vector-store-ids
    (complete-responses-stream req)
    (let [system-msg (when system [{:role "system" :content system}])
          all-messages (concat system-msg messages)
          body (cond-> {:model model
                        :messages (messages->openai all-messages)
                        (max-tokens-key model) max-tokens
                        :stream true}
                 tools (assoc :tools (mapv serialize-tool tools)))]
      (util/parse-sse-stream (api-post-stream "/chat/completions" body)))))

;;; Responses API (newer OpenAI API)

(defn run-response
  "Call the OpenAI responses API (newer stateful API).
  Returns full response body."
  [input & [{:as params}]]
  (api-post "/responses"
            (merge {:model default-model :input input}
                   params)))

(defn get-response
  "Retrieve a response by id (eg to poll a :background request)."
  [response-id]
  (api-get (str "/responses/" response-id) {}))

(defn await-response
  "Poll a background response until it leaves queued/in_progress; returns the raw response."
  [response-id & {:keys [interval-ms timeout-ms] :or {interval-ms 2000 timeout-ms 600000}}]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [r (get-response response-id)]
        (cond (not (#{"queued" "in_progress"} (:status r))) r
              (> (System/currentTimeMillis) deadline)
              (throw (ex-info "Timed out waiting for response" {:response r}))
              :else (do (Thread/sleep interval-ms) (recur)))))))

;;; Responses API as an ellum provider: file_search (RAG over vector stores),
;;; function tools, structured output, multi-turn via full message history.

(defn- message->responses-items
  "Convert one ellum message to a seq of Responses API input items."
  [{:keys [role content tool-calls tool-call-id]}]
  (if (= :tool (keyword role))
    [{:type "function_call_output" :call_id tool-call-id :output content}]
    (concat
     (when content [{:role (name role) :content content}])
     (for [tc tool-calls]
       {:type "function_call" :call_id (:id tc) :name (:name tc)
        :arguments (json/write-str (:arguments tc))}))))

(defn- serialize-responses-tool
  [{:keys [name description parameters]}]
  {:type "function" :name name :description description :parameters parameters})

(defn- file-search-tool
  [vector-store-ids max-num-results]
  (cond-> {:type "file_search" :vector_store_ids (vec vector-store-ids)}
    max-num-results (assoc :max_num_results max-num-results)))

;; Chat-completions response_format {:type "json_schema" :json_schema {...}}
;; becomes Responses text.format {:type "json_schema" :name :schema :strict}.
(defn- response-format->text-format
  [{:keys [type json_schema] :as response-format}]
  (if (= type "json_schema")
    {:format (merge {:type "json_schema"} json_schema)}
    {:format response-format}))

(defn responses-request-body
  "Build a Responses API request body from an ellum request."
  [{:keys [model messages system tools max-tokens response-format
           vector-store-ids max-num-results include-search-results previous-response-id]
    :or {model default-model max-tokens 4096}}]
  (let [all-tools (concat (when vector-store-ids [(file-search-tool vector-store-ids max-num-results)])
                          (map serialize-responses-tool tools))]
    (cond-> {:model model
             :input (vec (mapcat message->responses-items messages))
             :max_output_tokens max-tokens}
      system (assoc :instructions system)
      (seq all-tools) (assoc :tools (vec all-tools))
      response-format (assoc :text (response-format->text-format response-format))
      include-search-results (assoc :include ["file_search_call.results"])
      previous-response-id (assoc :previous_response_id previous-response-id))))

(defn- responses-stop-reason
  [{:keys [status incomplete_details]} tool-calls]
  (case status
    "completed" (if tool-calls :tool-calls :stop)
    "incomplete" (case (:reason incomplete_details)
                   "max_output_tokens" :length
                   "content_filter" :content-filter
                   :incomplete)
    (keyword status)))

(defn normalize-responses-response
  "Convert a raw Responses API response to ellum's normalized format, plus
  :citations [{:file-id :filename}], :file-search-results, and :response-id."
  [response]
  (let [output (:output response)
        parts (->> output
                   (filter #(= "message" (:type %)))
                   (mapcat :content))
        text (->> parts (filter #(= "output_text" (:type %))) (map :text) not-empty)
        refusal (some #(when (= "refusal" (:type %)) (:refusal %)) parts)
        citations (->> parts
                       (mapcat :annotations)
                       (filter #(= "file_citation" (:type %)))
                       (map (fn [a] {:file-id (:file_id a) :filename (:filename a)}))
                       distinct
                       not-empty)
        tool-calls (->> output
                        (filter #(= "function_call" (:type %)))
                        (mapv (fn [{:keys [call_id name arguments]}]
                                {:id call_id :name name
                                 :arguments (util/read-json-safe arguments)}))
                        not-empty)
        search-results (->> output
                            (filter #(= "file_search_call" (:type %)))
                            (mapcat :results)
                            not-empty)]
    {:content (when text (str/join text))
     :refusal refusal
     :tool-calls tool-calls
     :citations citations
     :file-search-results search-results
     :response-id (:id response)
     :stop-reason (if refusal :refusal (responses-stop-reason response tool-calls))
     :usage {:input-tokens (get-in response [:usage :input_tokens])
             :output-tokens (get-in response [:usage :output_tokens])}
     :raw response}))

(defn complete-responses
  "Call the Responses API with an ellum request. Extra req keys:
  :vector-store-ids :max-num-results :include-search-results :previous-response-id"
  [req]
  (-> (api-post "/responses" (responses-request-body req))
      normalize-responses-response))

(defn complete-responses-stream
  "Streaming `complete-responses`. Returns lazy seq of Responses SSE events
  (text arrives in events of :type \"response.output_text.delta\")."
  [req]
  (util/parse-sse-stream
   (api-post-stream "/responses" (assoc (responses-request-body req) :stream true))))

(defn submit-response
  "Submit `req` as a :background Responses request; returns raw {:id :status ...}.
  Use `await-response` + `normalize-responses-response` to collect the result."
  [req]
  (api-post "/responses" (assoc (responses-request-body req) :background true)))

;;; Files

(defn- api-delete
  [path]
  (:body (client/delete (str base-url path)
                        {:headers (auth-headers)
                         :as :json})))

(defn upload-file
  "Upload a local file (path or java.io.File) for use with vector stores. Returns raw file object."
  [file & {:keys [purpose] :or {purpose "assistants"}}]
  (:body (client/post (str base-url "/files")
                      {:multipart [{:name "purpose" :content purpose}
                                   {:name "file" :content (io/file file)}]
                       :headers (auth-headers)
                       :as :json})))

(defn list-files []
  (:data (api-get "/files" {})))

(defn file-ids []
  (map :id (list-files)))

(defn get-file-content
  "Raw content of an uploaded file, as a string."
  [file-id]
  (:body (client/get (str base-url "/files/" file-id "/content")
                     {:headers (auth-headers)
                      :as :string})))

(defn delete-file [file-id]
  (api-delete (str "/files/" file-id)))

;;; Vector stores

(defn create-vector-store
  "Create a vector store. params eg {:name \"docs\" :file_ids [...]}. Returns raw store object."
  [params]
  (api-post "/vector_stores" params))

(defn list-vector-stores []
  (:data (api-get "/vector_stores" {})))

(defn vector-store
  "Raw vector store object, including :status and :file_counts."
  [store-id]
  (api-get (str "/vector_stores/" store-id) {}))

(defn delete-vector-store [store-id]
  (api-delete (str "/vector_stores/" store-id)))

(defn vector-store-files [store-id]
  (:data (api-get (str "/vector_stores/" store-id "/files") {})))

(defn vector-store-add-file [store-id file-id]
  (api-post (str "/vector_stores/" store-id "/files") {:file_id file-id}))

(defn search-vector-store
  "Search a vector store directly (normally done implicitly by file_search). Returns raw results."
  [store-id query & [{:as params}]]
  (api-post (str "/vector_stores/" store-id "/search")
            (merge {:query query} params)))

(defn await-vector-store
  "Poll until a store's file ingestion is finished; returns the store object."
  [store-id & {:keys [interval-ms timeout-ms] :or {interval-ms 2000 timeout-ms 600000}}]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [s (vector-store store-id)]
        (cond (not= "in_progress" (:status s)) s
              (> (System/currentTimeMillis) deadline)
              (throw (ex-info "Timed out waiting for vector store" {:store s}))
              :else (do (Thread/sleep interval-ms) (recur)))))))

(defn make-rag-store
  "Upload `files`, create a vector store `store-name` from them, and wait for ingestion.
  Returns the store id."
  [store-name files]
  (let [ids (mapv (comp :id upload-file) files)
        store (create-vector-store {:name store-name :file_ids ids})
        ready (await-vector-store (:id store))]
    (when (pos? (get-in ready [:file_counts :failed] 0))
      (throw (ex-info "Some files failed to ingest" {:store ready})))
    (:id store)))
