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

(defn mcp-post
  "POSTs a JSON-RPC `command` to an MCP server. `config` is either a bare URL
   string, or {:url ... :auth {...}} - see get-access-token for :auth shapes.
   Throws ex-info on a non-2xx MCP response (eg a 401 pointing at the OAuth
   server via WWW-Authenticate); otherwise returns the hato response with
   :body coerced to a parsed map (see coerce-mcp-body)."
  [config command]
  (let [{:keys [url auth]} (->config config)
        token   (get-access-token auth)
        payload {:jsonrpc "2.0"
                 :id (str (gensym))
                 :method command
                 :params {:protocolVersion "2025-06-18",
                          :capabilities {},
                          :clientInfo {:name "my-client", :version "0.1"}
                          }}
        resp (client/post url
                           {:body             (json/write-str payload)
                            :content-type     :application/json
                            :accept           "application/json, text/event-stream"
                            :headers          (when token {"Authorization" (str "Bearer " token)})
                            :as               :string ;; :json is content-type-blind, so we coerce below
                            :throw-exceptions false})
        content-type (get-in resp [:headers "content-type"])
        body         (coerce-mcp-body (:body resp) content-type)]
    (if (< (:status resp) 400)
      (assoc resp :body body)
      (throw (ex-info (str "MCP request failed: " (:status resp))
                       {:url url :status (:status resp) :body body
                        :www-authenticate (get-in resp [:headers "www-authenticate"])})))))

(defn mcp-tools
  [config]
  (-> config
      (mcp-post "tools/list")
      :body
      :result
      :tools))

(comment
  (mcp-tools "https://mcp.deepwiki.com/mcp")
  (mcp-tools "https://docs.mcp.cloudflare.com/mcp")

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
