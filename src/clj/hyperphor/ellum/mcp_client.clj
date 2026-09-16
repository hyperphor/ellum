(ns hyperphor.ellum.mcp_client
  (:require [hato.client :as client]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [hyperphor.multitool.core :as u]
            [hyperphor.multitool.web :as uw]
            [hyperphor.multitool.cljcore :as ju]
            ))

;;; ── Response body coercion ──────────────────────────────────────────────

(defn- sse-data-lines
  "Pull the JSON payload(s) out of an SSE-framed body's `data:` lines."
  [body]
  (->> (str/split-lines body)
       (keep (fn [line]
               (when (str/starts-with? line "data:")
                 (str/trim (subs line (count "data:"))))))
       (remove str/blank?)))

(defn- coerce-json
  "Defensive JSON parse: falls back to {:message body} on a non-JSON body
   (HTML error page, empty body, etc) instead of throwing."
  [body]
  (if (string? body)
    (try (json/read-str body :key-fn keyword)
         (catch Exception _ {:message body}))
    body))

(defn- coerce-mcp-body
  "Parses `body` per `content-type`. MCP Streamable HTTP servers commonly
   SSE-frame even a single JSON-RPC response; unwrap those to their last
   data: payload, otherwise parse as plain JSON."
  [body content-type]
  (when (and (string? body) (not (str/blank? body)))
    (if (str/includes? (or content-type "") "text/event-stream")
      (some-> (sse-data-lines body) last coerce-json)
      (coerce-json body))))

;;; ── Auth ─────────────────────────────────────────────────────────────────
;;; :auth is one of:
;;;   a 0-arg function -> called on every request to get a bearer token
;;;     string. For delegating to a token-getter a caller already has -
;;;     eg (partial okc.cirro/get-access-token db), which handles Cognito's
;;;     client-credentials/user-password/device-code priority chain and its
;;;     own caching/refresh. Nothing here needs to know how it got the token.
;;;   {:type :bearer :token "..."}
;;;     - static token, sent as-is.
;;;   {:type :client-credentials
;;;    :token-url "https://auth.example.com/oauth2/token"
;;;    :client-id "..." :client-secret "..."
;;;    :client-auth :basic|:post   ; how to authenticate the token request,
;;;                                ; default :basic (HTTP Basic); some token
;;;                                ; endpoints only accept :post (client_id/
;;;                                ; client_secret in the form body) - never
;;;                                ; send both.
;;;    :scope "..." :resource "..."}   ; both optional; :resource is RFC 8707
;;;     - OAuth2 client-credentials grant, minted and cached until near expiry.
;;;       A generic Cognito client-credentials call (like okc/cirro.clj's
;;;       :app auth) fits this shape directly; anything with a richer flow
;;;       (user-password, device-code, refresh) is better handled by passing
;;;       a function instead of trying to grow this case to cover it.
;;; Credentials must be passed explicitly - there's no generic
;;; MCP_CLIENT_ID/MCP_CLIENT_SECRET/MCP_TOKEN env var convention (unlike
;;; :openai-api-key/:anthropic-api-key, each MCP server is its own auth
;;; domain), so callers should pull theirs from wherever makes sense
;;; per-server and build the auth map (or function) themselves.

(def ^:private token-cache
  "Cached {:access-token :expires-at}, keyed on the full :client-credentials
   auth map (minus :client-auth, which doesn't affect the minted token).
   Tokens are audience-scoped (RFC 8707 :resource), so two MCP servers behind
   the same token-url/client-id can still need distinct tokens - keying on
   just those would silently hand one server a token scoped to the other."
  (atom {}))

(defn- form-encode
  [params]
  (->> params
       (remove (comp nil? val))
       (map (fn [[k v]] (str (name k) "=" (uw/url-encode (str v)))))
       (str/join "&")))

(defn- client-credentials-token
  [{:keys [token-url client-auth client-id client-secret] :or {client-auth :basic} :as auth}]
  (let [basic-header  (str "Basic " (.encodeToString (java.util.Base64/getEncoder)
                                                       (.getBytes (str client-id ":" client-secret) "UTF-8")))
        body-params   (cond-> {:grant_type "client_credentials"
                                :scope      (:scope auth)
                                :resource   (:resource auth)}
                        (= client-auth :post) (assoc :client_id client-id :client_secret client-secret))
        resp   (client/post token-url
                             {:headers (cond-> {"Content-Type" "application/x-www-form-urlencoded"}
                                         (= client-auth :basic) (assoc "Authorization" basic-header))
                              :body             (form-encode body-params)
                              :as               :string
                              :throw-exceptions false})
        result (coerce-json (:body resp))]
    (if (:access_token result)
      {:access-token (:access_token result)
       :expires-at   (+ (System/currentTimeMillis)
                         (* (- (:expires_in result 3600) 60) 1000))}
      (throw (ex-info "MCP OAuth client-credentials request failed"
                       {:token-url token-url :status (:status resp) :body result})))))

(defn get-access-token
  "Returns a bearer token for `auth` (nil -> nil, unauthenticated request).
   A function `auth` is called with no args and trusted to return a token
   string, doing its own caching/refresh. Otherwise dispatches on :type:
   :bearer returns its static :token verbatim; :client-credentials mints one
   via the OAuth2 client-credentials grant and caches it (see token-cache),
   refreshing ~1min before expiry."
  [auth]
  (cond
    (nil? auth) nil

    (fn? auth) (auth)

    :else
    (case (:type auth)
      :bearer (:token auth)

      :client-credentials
      (let [cache-key (dissoc auth :client-auth)
            cache     (get @token-cache cache-key)]
        (when (or (nil? cache) (>= (System/currentTimeMillis) (:expires-at cache)))
          (swap! token-cache assoc cache-key (client-credentials-token auth)))
        (:access-token (get @token-cache cache-key)))

      (throw (ex-info "Unknown MCP auth type" {:auth auth})))))

;;; ── Requests ────────────────────────────────────────────────────────────

(defn- ->config
  "Normalizes mcp-post's first arg: a bare URL string means no auth."
  [x]
  (if (map? x) x {:url x}))

(defn- rpc-post
  "Raw JSON-RPC POST: `msg` is a full JSON-RPC 2.0 message map (a request
   has :id, a notification omits it - the caller decides which). Attaches
   auth and session headers, coerces the response body per its
   Content-Type. Returns {:status :body :session-id}; throws ex-info on a
   non-2xx HTTP response (eg a 401 pointing at the OAuth server via
   WWW-Authenticate) but does NOT check for a JSON-RPC-level :error in the
   body - callers that expect one should check it themselves."
  [{:keys [url auth]} msg session-id]
  (let [token (get-access-token auth)
        resp  (client/post url
                            {:body             (json/write-str msg)
                             :content-type     :application/json
                             :accept           "application/json, text/event-stream"
                             :headers          (cond-> {}
                                                 token      (assoc "Authorization" (str "Bearer " token))
                                                 session-id (assoc "Mcp-Session-Id" session-id))
                             :as               :string ;; :json is content-type-blind, so we coerce below
                             :throw-exceptions false})
        content-type (get-in resp [:headers "content-type"])
        body         (coerce-mcp-body (:body resp) content-type)]
    (if (< (:status resp) 400)
      {:status (:status resp) :body body
       :session-id (get-in resp [:headers "mcp-session-id"])}
      (throw (ex-info (str "MCP request failed: " (:status resp))
                       {:url url :status (:status resp) :body body
                        :www-authenticate (get-in resp [:headers "www-authenticate"])})))))

(defn- check-rpc-error!
  "Throws ex-info if `body` (a parsed JSON-RPC envelope) carries an :error -
   JSON-RPC returns these with a normal 2xx HTTP status, so rpc-post's own
   HTTP-level check doesn't catch them."
  [body context]
  (when (:error body)
    (throw (ex-info (str "MCP error: " (get-in body [:error :message] "unknown"))
                     (assoc context :error (:error body))))))

(def ^:private session-cache
  "url -> Mcp-Session-Id string handed back by that server's initialize
   response, or ::none once confirmed the server doesn't use sessions (so
   initialize! doesn't redo the handshake on every call). Keyed on :url
   alone, not :auth - fine for the expected one-identity-per-server-per-
   process usage; a process juggling multiple identities against the same
   url would need finer keying."
  (atom {}))

(defn- initialize!
  "Performs the MCP initialize handshake for config's url, once per url per
   process: POSTs `initialize` (carrying protocolVersion/capabilities/
   clientInfo - the only call these belong on, unlike the old code sending
   them with every request), caches any Mcp-Session-Id the server hands
   back, then fires the notifications/initialized notification per spec.
   Some servers don't implement that notification's endpoint at all, so its
   failure is swallowed rather than aborting the session - it's advisory,
   nothing downstream depends on its response.
   Returns the session id to send on subsequent requests, or nil."
  [{:keys [url] :as config}]
  (when-not (contains? @session-cache url)
    (let [{:keys [body session-id]}
          (rpc-post config
                    {:jsonrpc "2.0" :id (str (gensym))
                     :method "initialize"
                     :params {:protocolVersion "2025-06-18"
                              :capabilities {}
                              :clientInfo {:name "ellum" :version "0.1"}}}
                    nil)]
      (check-rpc-error! body {:url url :method "initialize"})
      (swap! session-cache assoc url (or session-id ::none))
      (try
        (rpc-post config
                  {:jsonrpc "2.0" :method "notifications/initialized" :params {}}
                  session-id)
        (catch Exception _ nil))))
  (let [sid (get @session-cache url)]
    (when-not (= sid ::none) sid)))

(defn mcp-post
  "Sends a JSON-RPC `method` call (with optional `params`, default {}) to
   an MCP server, after ensuring the initialize handshake has run (see
   initialize!). `config` is either a bare URL string, or {:url ... :auth
   ...} - see get-access-token for :auth shapes. Throws ex-info on a
   non-2xx HTTP response or a JSON-RPC :error result; otherwise returns
   {:status :body}, :body the parsed {:jsonrpc :id :result ...} envelope."
  ([config method] (mcp-post config method {}))
  ([config method params]
   (let [config     (->config config)
         session-id (initialize! config)
         resp       (rpc-post config
                               {:jsonrpc "2.0" :id (str (gensym))
                                :method method :params params}
                               session-id)]
     (check-rpc-error! (:body resp) {:url (:url config) :method method})
     resp)))

(defn mcp-tools
  [config]
  (-> config
      (mcp-post "tools/list")
      :body :result :tools))

(defn- unpack-tool-result
  "Collapses an MCP tools/call result ({:content [...] :isError bool
   :structuredContent {...}}) into a plain value, for use as an ellum
   tool's return value (see tools.clj's {:fn ...} contract). MCP represents
   a failed tool call as :isError true rather than a JSON-RPC :error,
   precisely so the LLM can see and react to it - ellum's tools/dispatch
   has no such convention, so surface it as a thrown exception like any
   other failed :fn. Prefers :structuredContent when the tool declares an
   outputSchema; otherwise joins all-text content into a single string;
   falls back to the raw content vector for image/resource/etc results
   that can't meaningfully collapse to a string."
  [{:keys [content structuredContent isError]}]
  (when isError
    (throw (ex-info "MCP tool call returned an error" {:content content})))
  (cond
    structuredContent structuredContent
    (every? #(= (:type %) "text") content) (str/join "\n" (map :text content))
    :else content))

(defn mcp-call
  "Calls MCP tool `tool-name` with `arguments` (a map, default {}) via
   tools/call. Returns the unpacked result value - see unpack-tool-result."
  ([config tool-name] (mcp-call config tool-name {}))
  ([config tool-name arguments]
   (-> (mcp-post config "tools/call" {:name tool-name :arguments (or arguments {})})
       :body :result
       unpack-tool-result)))

(comment
  (mcp-tools "https://mcp.deepwiki.com/mcp")
  (mcp-tools "https://docs.mcp.cloudflare.com/mcp")

  (mcp-call "https://mcp.deepwiki.com/mcp" "read_wiki_structure" {:repoName "facebook/react"})
  (mcp-call "https://mcp.deepwiki.com/mcp" "ask_question" {:repoName "axios/axios" :question "What the heck is this repo for?"})

  (mcp-tools "https://wd-mcp.wmcloud.org/mcp/")
  (mcp-call "https://wd-mcp.wmcloud.org/mcp/" "search_items" {:query "Uri Caine"})
  (mcp-call "https://wd-mcp.wmcloud.org/mcp/" "get_statements" {:entity_id "Q1343710"})

  ;; Cirro's MCP endpoint - delegate to okc.cirro's own get-access-token
  ;; (functional :auth) rather than reimplementing its Cognito
  ;; client-credentials/user-password/device-code priority chain here.
  (require '[org.parkerici.okc.sources.cirro :as cirro])
  (mcp-tools {:url "https://dev.cirro.bio/api/mcp"
              :auth (partial cirro/get-access-token cirro-db)}) ;cirro-db per cirro.clj's api-post




  ;; A plain generic OAuth2 client-credentials server, by contrast, needs
  ;; no delegate - the declarative :client-credentials shape covers it
  ;; directly. Don't check in real :client-id/:client-secret values here.
  (mcp-tools {:url "https://mcp.example.com/mcp"
              :auth {:type :client-credentials
                     :token-url "https://auth.example.com/oauth2/token"
                     :client-id "..."
                     :client-secret "..."
                     :client-auth :basic}})


  (mcp-tools {:url "https://api.githubcopilot.com/mcp/"
              :auth {:type :bearer
                     :token "<github pat token>"}})


  )
