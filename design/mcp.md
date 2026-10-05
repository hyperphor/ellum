# MCP

Should provide some sort of MCP support:

## MCP client

Not even clear what this is for, usually it is LLMs that are the clients. Still seemed like a good thing to be able to do discovery of operations

`mcp_client.clj` now supports OAuth-requiring MCP servers: `mcp-post`/`mcp-tools`
take either a bare URL (unauthenticated) or `{:url ... :auth ...}`, where
`:auth` is one of:
- a 0-arg function returning a token string, for delegating to a token-getter
  the caller already has (eg `(partial okc.cirro/get-access-token db)`,
  which handles Cognito's client-credentials/user-password/device-code
  priority chain and its own caching/refresh) — nothing here needs to know
  how the token was obtained;
- `{:type :bearer :token ...}` for a static token;
- `{:type :client-credentials :token-url ... :client-id ... :client-secret ...}`
  for a generic OAuth2 client-credentials grant (token minted and cached
  until near expiry) — covers a plain Cognito client-credentials call
  directly, without needing a delegate function.

Credentials must be passed explicitly (map or closed-over by the function) —
there's no generic env var convention, since each MCP server is its own auth
domain.

- TODO: full MCP spec OAuth (metadata discovery, dynamic client registration,
  authorization-code + PKCE with a local redirect listener) and an
  interactive device-code flow — not implemented; the functional `:auth`
  escape hatch covers these by delegation instead.

`mcp-post` now does a real `initialize` → `notifications/initialized`
handshake once per server url (rather than resending protocolVersion/
capabilities on every call), caching any `Mcp-Session-Id` the server hands
back and echoing it on subsequent requests. JSON-RPC-level `:error`
responses (distinct from HTTP errors - JSON-RPC returns these with a normal
2xx status) now throw a clear ex-info too.

`mcp-call` (`tools/call`) is implemented: calls a tool by name with
arguments and unpacks the MCP result - preferring `:structuredContent`,
otherwise joining text content blocks. A tool result with `:isError true`
throws (MCP's own convention for a failed call, distinct from a JSON-RPC
error).

- TODO: bridge `mcp-tools`'s output directly into ellum's own tool-def
  format (`{:name :description :parameters :fn}` from tools.clj) so an MCP
  server's tools can be handed straight to `chat/make-chat`'s `:tools`
  without per-tool glue code — `mcp-call` is the piece that was missing to
  do that, but the actual bridge fn isn't written yet.
- TODO: stdio transport, for the many MCP servers that run as a local
  subprocess rather than over HTTP.
- TODO: resources/prompts primitives, and sampling (server-initiated LLM
  calls forwarded to core/complete).

## Anthropic MCP connector (API-native, beta)

Separate from `mcp_client.clj` above — this is Anthropic's own infrastructure
calling a remote MCP server directly during a Messages API call, no local MCP
client involved. Implemented in `providers/anthropic.clj`: `core/complete`
(and `complete-stream`) take `:mcp-servers`, a seq of
`{:name :url :authorization-token}`. For each server, ellum adds a
`{:type "url" ...}` entry to the request's `:mcp_servers` *and* a matching
`{:type "mcp_toolset" :mcp_server_name ...}` entry to `:tools` (the API
requires both, kept in sync so callers don't hand-write the toolset side),
and sets the `anthropic-beta: mcp-client-2025-11-20` header.

Since the server executes the call itself, there's nothing for ellum to
dispatch — the normalized response surfaces what happened via `:mcp-tool-uses`
(`{:id :name :arguments :server}`) and `:mcp-tool-results`
(`{:tool-use-id :content :error?}`) for inspection, distinct from the
client-dispatched `:tool-calls`.

Not implemented: `:authorization_token` is passed through as a plain string
only — no OAuth flow, unlike `mcp_client.clj`'s `:auth` option. Anthropic-side
only (`:anthropic` provider); no OpenAI equivalent exists.

### Redesign

I want to be able to create an LLM provider, say :anthropic-c3po, at runtime. This would work just like :anthropic provider but add the MCP server. I don't want to have to specify MCP in each call, although its ok to retain that capability. Basically want to extend the concept of providers to allow for customizations, with MCP being just one of them.

(def c3po
    (make-source
        {:base-provider :anthropic
	  :keyword :anthropic-c3po
	:system-prompt "you are a protocol guru"   
	:mcp-servers [{...}]
	:default-model
}))

I'm sure there will be other parameters but hopefull you get the idea. Everything except :base-provider is optional

When this is run, both c3po and :anthropic-c3po can be used as providers in all ellum calls

#### Implemented

`core/make-source`, in `core.clj`. Registers the config in an atom keyed by
the provider keyword (`:keyword`, or an auto-generated one if omitted — so
the returned value is always usable even unnamed) and returns that keyword.
`core/complete`, `complete-stream`, `list-models`, `key-status`, and
`query-structured` all resolve a custom provider to its `:base-provider`
before dispatching, folding in the registered `:system-prompt` (used unless
the call gives its own `:system`), `:mcp-servers` (the registered ones are
prepended to any the call also passes — so you never *have* to repeat them,
but still can add more per call), and `:default-model` (used unless the call
gives its own `:model`). `run-agent`'s tool-result formatting (which branches
on provider shape) also resolves first, so tool calls work the same as on
the plain base provider.

Not done: no way to unregister/list registered sources; no validation of
`:base-provider` against actually-known providers (a typo registers fine and
only fails on first use, same as an unknown plain provider keyword today).
