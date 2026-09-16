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
