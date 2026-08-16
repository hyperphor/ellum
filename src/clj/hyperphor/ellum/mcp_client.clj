(ns hyperphor.ellum.mcp_client
  (:require [hato.client :as client]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [hyperphor.multitool.core :as u]
            [hyperphor.multitool.web :as uw]
            [hyperphor.multitool.cljcore :as ju]
            ))


(defn- sse-data-lines
  "Pull the JSON payload(s) out of an SSE-framed body's `data:` lines."
  [body]
  (->> (str/split-lines body)
       (keep (fn [line]
               (when (str/starts-with? line "data:")
                 (str/trim (subs line (count "data:"))))))
       (remove str/blank?)))

(defn mcp-post
  [url command]
  (let [payload
        {
         :jsonrpc "2.0"
         :id (str (gensym))
         :method command
         :params {
                  :protocolVersion "2025-06-18",
                  :capabilities {},
                  :clientInfo {:name "my-client", :version "0.1"}
                  }}
        resp (client/post url
                           {:body             (json/write-str payload)
                            :content-type     :application/json
                            :accept           "application/json, text/event-stream"
                            ;; :headers          {"Authorization" (str "Bearer " (get-access-token db))}
                            :as               :string ;; :json is content-type-blind, so we coerce below
                            ;; :throw-exceptions false
                            })
        body (:body resp)
        content-type (get-in resp [:headers "content-type"] "")]
    (prn :content-type content-type)
    (cond-> resp
      (and (string? body) (not (str/blank? body)))
      (assoc :body
             (if (str/includes? content-type "text/event-stream")
               ;; MCP Streamable HTTP servers commonly SSE-frame even a
               ;; single JSON-RPC response; grab the last data: payload.
               (some-> (sse-data-lines body)
                       last
                       (json/read-str :key-fn keyword))
               (json/read-str body :key-fn keyword))))))

(defn mcp-tools
  [url]
  (-> url
      (mcp-post "tools/list")
      :body
      :result
      :tools))

(comment
  (mcp-tools "https://mcp.deepwiki.com/mcp")
  (mcp-tools "https://docs.mcp.cloudflare.com/mcp")

  ;; TODO support OAuth
  (mcp-tools "https://dev.cirro.bio/api/mcp")
  )

