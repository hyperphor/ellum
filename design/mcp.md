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
