# Feature Survey: What ellum Should Steal from Other LLM Frameworks

*A survey of DSPy, Mastra, LangChain, Vercel AI SDK, Instructor, Pydantic AI, ellmer (R), and LlamaIndex/Haystack, with concrete recommendations for ellum, a Clojure port of R's `ellmer` package.*

## 1. Current State of ellum

ellum is a two-provider (OpenAI chat completions, Anthropic Messages) Clojure client library with a `defmulti`-based dispatch in `core.clj` routing to `providers/openai.clj` / `providers/anthropic.clj`, each normalizing to a common `{:content :tool-calls :stop-reason :usage :raw}` response shape (including refusal handling). Tool calling works via provider-agnostic `{:name :description :parameters :fn}` maps (`tools.clj`), with a `run-agent` loop in `core.clj` that dispatches tool calls and re-submits until `:stop` (max-iterations guard, but tool exceptions propagate uncaught and `dispatch-all` runs tools sequentially even within one turn). Structured output is native `response_format` JSON-schema on OpenAI, but on Anthropic it's a fragile prompt-injected-schema + regex/JSON-extraction from free text (`extract.clj`/`util.clj`), with no validation or retry on either path. `chat.clj` is a bare in-memory atom (no persistence, no cost tracking — usage is discarded from history). Streaming (`complete-stream`) returns a raw lazy seq of unparsed provider SSE deltas with no accumulation into the normalized response shape. There is no retry/backoff anywhere in the HTTP layer, no cost/pricing tables (only raw per-call token counts), no observability hooks, no prompt caching, and no multi-modal (image/PDF) content types — messages assume `:content` is always a string. Tests are unit-level only (normalization, schema, tools), no live integration coverage.

## 2. Per-Framework Highlights

### ellmer (R) — the origin
This is a parity/drift audit, not feature-shopping — ellum is porting this library.

- Retries up to 3x by default, retrying on connection failure too, not just HTTP error status (`options(ellmer_max_tries)`) — ellum has none.
- `token_usage()` reports cumulative session cost via LiteLLM pricing data, distinguishing cached vs. uncached tokens — ellum only exposes raw per-call token counts.
- `Chat$on_tool_request()` + `tool_reject()` lets a caller **veto** a tool call before dispatch — a real approval gate, not just notification.
- First-class rich content types (`ContentImage`, `ContentImageRemote`, `ContentPDF`, `ContentThinking`) vs. ellum's bare string `:content`.
- `parallel_chat()` / `batch_chat()` fan out multiple conversations concurrently — no equivalent in ellum.

Sources: [ellmer reference index](https://ellmer.tidyverse.org/reference/index.html), [ellmer 0.3.0 release notes](https://tidyverse.org/blog/2025/07/ellmer-0-3-0/), [ellmer 0.4.0 release notes](https://tidyverse.org/blog/2025/11/ellmer-0-4-0/), [tool-calling vignette](https://ellmer.tidyverse.org/articles/tool-calling.html).

### DSPy — declarative signatures/modules + optimizer compiler
The compiler itself is out of scope for a client lib.

- Worth stealing: `dspy.Assert` / `Suggest` — on validation failure, reinject the error message into the prompt and retry, rather than blindly resending the same request. Directly applicable to ellum's structured-output gap.

Sources: [DSPy Assertions](https://dspy.ai/learn/programming/7-assertions/), [arXiv:2312.13382](https://arxiv.org/pdf/2312.13382).

### Mastra — TS agent/workflow orchestration + observability
Full workflow engine / memory is out of scope.

- Zero-instrumentation OTel tracing captures every LLM call, tool exec, token count, latency, cost at the span level once enabled — good model for a lightweight hook seam (not full OTel) in ellum.
- `requireApproval: true` tool flag + suspend/resume — an explicit, structured version of ellmer's approval callback.

Sources: [Mastra observability overview](https://mastra.ai/docs/observability/overview), [human-in-the-loop docs](https://mastra.ai/docs/workflows/human-in-the-loop).

### LangChain — chain/agent/retriever ecosystem breadth
The 700+-integration ecosystem is exactly what ellum's own CLAUDE.md warns against rebuilding.

- `.with_retry()` as a composable wrapper with explicit `retry_if_exception_type`, `wait_exponential_jitter`, `stop_after_attempt` — good shape for ellum's retry policy.
- Minimal `on_start` / `on_end` / `on_error` callback triple on every Runnable — lighter-weight than Mastra's OTel, more appropriate for a client library.

Source: [RunnableRetry reference](https://reference.langchain.com/python/langchain-core/runnables/retry).

### Vercel AI SDK — streaming primitives + typed generation

- `streamObject` progressively yields partial, schema-validated object fields as tokens arrive — ellum's `complete-stream` has no accumulation into the normalized shape at all.
- Two distinct retry knobs in practice: transport-level (`maxRetries`, exponential backoff) vs. validation-level (reask on schema failure) — worth keeping as separate policies, not one generic retry.
- Mid-stream errors surface as a stream event rather than killing the whole stream.

Sources: [streamText reference](https://ai-sdk.dev/docs/reference/ai-sdk-core/stream-text), [AI SDK 4.1 blog](https://vercel.com/blog/ai-sdk-4-1).

### Instructor (Python/TS) — validated structured extraction

- Core mechanism: wrap the provider client around a target schema; on validation failure, "reask" by injecting the validation-error text back into the conversation and retrying up to `max_retries`. This is the most directly portable idea for `schema.clj` / `query-structured`, especially for Anthropic which has no validation at all today.

Source: [reask validation concepts](https://python.useinstructor.com/concepts/reask_validation/).

### Pydantic AI — typed agents + dependency injection

- `RunContext`-based typed dependency injection into tools solves the "global state in tool closures" problem (ellum's `tools/weather.clj` closes over an ambient env-var API key) — interesting but likely too large a redesign to prioritize now.
- Structured-output validators triggering a bounded retry (default 1) independently converge on the same reask pattern as Instructor, reinforcing it as the right idea.

Source: [Pydantic AI agent concepts](https://pydantic.dev/docs/ai/core-concepts/agent/).

### LlamaIndex / Haystack — RAG frameworks
Index-and-query vs. node-pipeline abstractions. Both are a different category of library — see Out of Scope below.

## 3. Prioritized Recommendations

Ranking axis: **pain (how much this blocks real usage today) × lift (inverse of implementation effort)** — cheap fixes to acute gaps first, larger protocol-touching work last.

| # | Feature | Inspired by | Lift |
|---|---|---|---|
| 1 | HTTP retry/backoff with jitter | ellmer, LangChain, Vercel AI SDK | Small–medium |
| 2 | Anthropic structured output via tool-forcing | Instructor, Pydantic AI | Small |
| 3 | Validation-reask retry loop, cross-provider | Instructor, Pydantic AI, DSPy Assertions | Medium |
| 4 | `:on-request`/`:on-response`/`:on-error` hooks | Mastra tracing, LangChain callbacks | Small |
| 5 | Cost/token tracking with pricing table | ellmer's `token_usage()` | Small–medium |
| 6 | Stream accumulator into normalized response shape | Vercel AI SDK's `streamText`/`streamObject` | Medium |
| 7 | Tool-call approval hook | ellmer's `on_tool_request`/`tool_reject`, Mastra's `requireApproval` | Small |
| 8 | Parallel tool dispatch + exception isolation | ellmer/Vercel concurrent execution | Small |
| 9 | Anthropic prompt caching (`cache_control`) | ellmer's cached/uncached token tracking | Small |
| 10 | Multi-modal content types (images, PDFs) | ellmer's `ContentImage`/`ContentPDF` | Medium–large |

### 1. HTTP retry/backoff with jitter
*ellmer, LangChain, Vercel AI SDK*

Every `hato` call in both provider namespaces fails hard on transient 429/5xx/connection errors; zero retry logic exists. Add a `with-retry` wrapper in `util.clj` (max-tries, retryable status codes, exponential backoff + jitter) used by `api-post`/`api-post-stream` in both providers. Doesn't change the provider protocol. **Small–medium.**

### 2. Anthropic structured output via tool-forcing
*Instructor, Pydantic AI*

Replace the fragile prompt-injected-schema + regex extraction in `query-structured`'s Anthropic branch with the idiomatic mechanism both Instructor and Pydantic AI actually use for Claude: define one tool whose `input_schema` **is** the target JSON schema, force `tool_choice` to it, and read the structured object straight out of the `tool_use` block. `providers/anthropic.clj` already serializes tools with `input_schema`, so this is nearly free — it's a new call path in `core.clj`/`providers/anthropic.clj`, not new machinery. **Small.**

### 3. Validation-reask retry loop, cross-provider
*Instructor, Pydantic AI, DSPy Assertions*

Layer on top of #2: after parsing, run the result through `schema/coerce`/`validate-required` (which exist but are currently never called post-completion); on failure, append an error-describing message and re-call up to N times, for both providers. Keep this as a distinct retry policy from #1's transport retries (different failure class, different backoff). Touches `core.clj`'s `query-structured` and `schema.clj`. **Medium.**

### 4. `:on-request`/`:on-response`/`:on-error` hooks
*Mastra tracing, LangChain callbacks*

Optional callback keys on the request map, invoked around `-complete` in `core.clj`. This is substrate, not a standalone nicety: it's what #5 (cost tracking) and retry logging from #1 would plug into. Keep it in-process only — not full OTel. **Small.**

### 5. Cost/token tracking with pricing table
*ellmer's `token_usage()`*

Today usage is per-call raw token counts only; nothing accumulates across a session, and `chat.clj` discards `:usage` when storing turns. Add a `hyperphor.ellum.usage` ns with a small static per-model pricing table plus a `session-usage`/`total-cost` fn folding over retained turn usage (requires `chat.clj` to keep `:usage` on stored assistant messages). Naturally wired through the hooks in #4. **Small–medium.**

### 6. Stream accumulator into the normalized response shape
*Vercel AI SDK's `streamText`/`streamObject`*

`complete-stream` returns raw unparsed SSE deltas with no relation to the `{:content :tool-calls :stop-reason :usage}` shape `complete` produces — streaming and non-streaming callers see entirely different data. Add a per-provider (delta shapes differ) `accumulate-stream` reducer, exposed via `core.clj`. **Medium.**

### 7. Tool-call approval hook
*ellmer's `on_tool_request`/`tool_reject`, Mastra's `requireApproval`*

`run-agent`'s `on-tool-call` is fire-and-forget notification only, with no way to veto a call before `tools/dispatch` executes it. Change the contract (or add `on-tool-request`) so a callback can return a rejection that short-circuits dispatch and is surfaced to the model as a synthetic error result instead. **Small.**

### 8. Parallel tool dispatch + exception isolation
*implied by ellmer/Vercel supporting concurrent execution; both providers can return multiple `tool_calls` in one turn*

`tools/dispatch-all` is a plain sequential `mapv`, and a thrown exception from any `:fn` propagates uncaught, killing the whole batch. Swap to a `future`/`pmap`-based fan-out preserving order, with per-call exception capture turned into an error result rather than a crash. **Small.**

### 9. Anthropic prompt caching (`cache_control` breakpoints)
*ellmer explicitly tracks cached vs. uncached tokens as of 0.3.0*

System prompts and tool definitions are resent every turn in `chat.clj`'s multi-turn loop with no caching — a real cost/latency cost for nontrivial system prompts or tool-heavy agents. Add optional cache markers in `providers/anthropic.clj`'s `message->anthropic`/`serialize-tool`, gated by a request flag; `normalize-response` gets a slot for cached-token usage once #5 exists to consume it. **Small.**

### 10. Multi-modal content types (images, PDFs)
*ellmer's `ContentImage`/`ContentPDF`/`ContentImageRemote`*

`:content` is assumed to always be a string throughout `chat.clj` and both providers, even though both OpenAI and Anthropic natively support image/PDF input. This is the one candidate that changes the core normalized message shape (`:content` becomes string-or-vector-of-typed-parts) and touches serialization in both providers. Real gap, but the largest lift — do it last, deliberately. **Medium–large.**

## 4. Explicitly Out of Scope

- **DSPy's declarative-program + optimizer/compiler layer.** DSPy compiles programs against a metric — a fundamentally different product from a client library. Only its Assertions retry idea (folded into #3) is portable; the compiler itself isn't.
- **Mastra's/LangChain's full workflow orchestration + memory/retriever ecosystem** (durable suspend/resume workflows, working memory, 700+ integrations). This is framework-scope. Per ellum's own CLAUDE.md, that orchestration job belongs to sibling apps (alzabo, pimento) built *on top of* ellum, not inside it.
- **LlamaIndex/Haystack-style RAG indexing abstractions.** Building a vector-store/index layer is a different library entirely. If ellum ever needs RAG support, the right shape is "here's how to wire a tool that calls your existing retrieval store," not an index abstraction of its own.
