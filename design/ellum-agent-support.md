# Agent support (proposal)

From `TODO.md`: "Agent support. Of course, but what does that mean? Something
lightweight, resisting a whole complicated infrastructure."

## What "agent" means here

**An agent is a value** (provider, model, system prompt, tools, limits, hooks)
**plus one `run` function** that loops model ↔ tools until the model stops,
and returns the whole trace, not just the last reply. Composition comes from
turning an agent into a tool, so one agent can call another. That's all.

This is orthogonal to `make-source`. A custom source customizes *calls* to a
provider (system prompt, MCP servers, default model). An agent's `:provider`
can simply be a source.

## Where we are

`core/run-agent` already is the loop, and `core/chat` uses it when `:tools` is
set. Checking its behavior with a stubbed `-complete` (no live calls) shows
the gaps:

1. **Chat history drops tool turns.** `chat`'s `send!` appends only the final
   assistant message. After "1+2?" → tool call `add` → "3", the history is
   `[{:user "1+2?"} {:assistant "3"}]`, so later turns can't see what tools
   returned. This happens because `run-agent` returns only the final
   response, not the messages it built up.
2. **A tool exception aborts the whole run.** `tools/dispatch` doesn't catch
   anything, so a throwing `:fn` propagates out of `run-agent`. The model
   never gets the chance to recover, which it usually can if told "that
   failed: ...".
3. **Hitting max-iterations throws and loses the transcript.**
   `(ex-data e)` is just `{:iterations 2}`.
4. Usage isn't summed across iterations. (The `todo/ellum-reply-metadata`
   branch adds `:agent-usage`.)
5. `on-tool-call` only notifies. There's no veto or approval (FEATURE_SURVEY #7).
6. Tools are dispatched sequentially with no isolation (FEATURE_SURVEY #8).
   There are no general request/response hooks either (FEATURE_SURVEY #4).
7. MCP server tools can't be handed straight to `:tools`. That's the open
   bridge TODO in `mcp.md`, planned as the separate `ellum-mcp-tool-bridge` task.
8. The Anthropic message shape has no way to send `is_error: true` on a
   `tool_result`. `message->anthropic` and `results->anthropic-user-message`
   only emit `:tool_use_id`/`:content`.

## Options

**A. Status quo + docs.** Call `run-agent` "the agent" and fix bugs 1–3 in
place. Cheapest option. But the agent stays a bag of keyword args, not a
value: you can't name, store, compose or hand around an agent.

**B. Thin `hyperphor.ellum.agent` namespace (recommended).** An agent is a
plain map, `run` returns a trace, hooks are the extension seam, and
`agent->tool` handles composition. It depends only on ellum's public API
(`core/complete`, `tools`). Nothing in core depends on it.

**C. Full framework.** Memory stores, planners, workflow graphs,
suspend/resume persistence, multi-agent runtimes (Mastra, LangGraph, the
OpenAI Agents SDK). This is real work and possibly worth doing, but it's the
"complicated infrastructure" the TODO wants to resist. It belongs in its own
library, built on B.

**D. Provider-hosted agents.** Anthropic Managed Agents, OpenAI Responses
with hosted tools. Useful, but vendor-specific and opaque. If wanted, they
fit in as provider features (the same way the MCP connector did), not as
ellum's agent model.

## Recommendation: B

`design/design.md` lists Agents under *Excluded: should be in a different
layer*. B respects that. `agent.clj` *is* the different layer: it consumes
ellum the way any client would, so it could be moved into its own artifact
later without touching core. Change that line of `design.md` to say "agent
loop only, in `agent.clj`; frameworks out of scope".

### API sketch

```clojure
(require '[hyperphor.ellum.agent :as agent])

(def sql-fixer
  (agent/make-agent
   {:name     "sql-fixer"                ; also the tool name in agent->tool
    :provider :anthropic                 ; or a make-source keyword
    :model    "claude-sonnet-4-5"
    :system   "You write and repair SQL..."
    :tools    [run-sql-tool]
    :max-iterations 8
    :output   {:schema result-schema}    ; optional; see "Structured result"
    :hooks    {:on-tool-request (fn [call] (when (dangerous? call) {:reject "not allowed"}))
               :on-step         (fn [step] (log/info step))}}))

(agent/run sql-fixer "How many trials started in 2024?")
;; => {:content  "..."            ; final text (or :output, if structured)
;;     :output   {...}            ; parsed structured result, when :output given
;;     :stop     :done            ; :done | :max-iterations | :aborted
;;     :messages [...]            ; full history incl. tool turns, re-submittable
;;     :steps    [{:response .. :tool-calls .. :results ..} ...]
;;     :usage    {:input-tokens N :output-tokens N}}

(agent/run sql-fixer "and in 2023?" {:messages (:messages prev)})  ; continue

(agent/agent->tool sql-fixer)   ; => {:name :description :parameters :fn}
```

Some notes on the design:

- `make-agent` just validates and returns the map. Agents are data (no atom,
  no registry) and can be `assoc`ed like any other config.
- `run` never throws for loop-level outcomes. Max-iterations and hook aborts
  come back as `:stop` plus a full trace. Provider/HTTP errors
  still throw: that's an infrastructure failure, not an agent outcome.
- **Tool errors** are caught per call and sent back to the model as an error
  result (`is_error` on Anthropic, an `"Error: ..."` string on OpenAI).
  The run continues.
- **Hooks** are the seam where heavier infrastructure plugs in without
  living here. Approval UIs, tracing/OTel, persistence and budgets are all
  just hook functions. Any hook can return `:abort`, which ends the run with
  `:stop :aborted`.
  - `:on-tool-request` returns nil to proceed or `{:reject msg}` to veto.
    A veto goes back to the model as an error result and the loop continues,
    as in FEATURE_SURVEY #7.
  - `:on-step` is called after each model turn and `:on-tool-error` after
    each failed tool call. Otherwise they only notify.
- **Structured result.** When `:output` is given, add a synthetic `finish`
  tool whose parameters are the schema. The run ends when the model calls
  it, and its arguments are `:output`. This works the same on both providers
  and needs no `response_format`. It's the same idea as FEATURE_SURVEY #2.
- **Composition.** `agent->tool` wraps `run` as a tool `:fn` taking
  `{:task "..."}` and returning `:output` or `:content`. Multi-agent then
  just means "an agent with agent-tools". No orchestrator.
- `core/chat` becomes a thin stateful wrapper. It holds `:messages` in its
  atom and calls `agent/run` with them, which fixes gap 1.

### Non-goals

These stay out of ellum:

- memory or vector stores
- planners
- workflow DAGs
- durable suspend/resume
- async/streaming agent runs
- parallel sub-agent scheduling beyond what `pmap` in dispatch gives
- a tool registry

Hooks and the returned trace should be enough for someone to build those on
top.

## Implementation plan

Roughly in dependency order. Each step can land and be tested on its own.

1. **`tools.clj`**
   - `dispatch` catches exceptions and returns `{:id :name :error msg}`.
   - `dispatch-all` uses an order-preserving `pmap`/futures.
   - The result→message fns carry `:error?` through.
   - Small. FEATURE_SURVEY #8.
2. **`providers/anthropic.clj`**
   - `message->anthropic` and `results->anthropic-user-message` emit
     `:is_error true`.
   - OpenAI has no flag, so prefix the content.
3. **New `agent.clj`** with `make-agent`, `run`, `agent->tool`, and the
   `finish`-tool output mode. The loop is roughly today's `run-agent` body,
   plus step accumulation, hooks and usage summing. Around 120 lines.
4. **`core.clj`**
   - `agent.clj` requires `core`, so `core` can't require `agent` back.
     `core/run-agent` and `core/chat` reach `agent/run` through
     `requiring-resolve`, which keeps the static dependency one-way.
   - `run-agent` keeps its signature and still returns the final response,
     now with `:messages`/`:steps`/`:stop` assoc'd. That's back-compatible
     (no sibling repo calls it: nlq uses `complete`, eli uses `query`).
     Mark it deprecated in favor of `agent/run`.
   - `chat` appends the whole run's new messages, not just the last one.
5. **MCP.** Once the `ellum-mcp-tool-bridge` task lands, whatever tool-def
   function it produces feeds straight into `:tools`. Nothing agent-specific
   is needed.
6. **New `test/clj/hyperphor/ellum/agent_test.clj`.** No live LLM calls:
   stub with `(with-redefs [core/-complete scripted-responses] ...)`, the
   same way the gaps above were reproduced. Cover:
   - tool turn kept in `:messages`
   - a throwing tool becomes an error result, and the run continues
   - max-iterations returns `:stop :max-iterations` with a trace
   - veto via `:on-tool-request` fed back to the model; `:abort` stops the run
   - `finish`-tool structured output
   - `agent->tool` nesting
   - usage summed
7. **Docs.** Edit the Excluded line in `design.md`, add a README section, and
   update the `CLAUDE.md` namespace layout.

Interactions with in-flight branches:

- `todo/ellum-reply-metadata` adds `:agent-usage` and `:cost` to `run-agent`.
  `run`'s `:usage` should reuse its usage-summing helper.
- `todo/ellum-llm-config-objects` lets `:provider` be a config map. The
  agent's `:provider` should accept the same.

## Motivating example (cross-repo, not part of this change)

`nlq`'s `generate :sql` is a single `llm/complete`. When the SQL fails,
`endpoint`'s `with-ex` returns it as `:error` and nothing tries to repair it. As an agent with one `run-sql` tool (and
`:on-tool-request` limiting it to read-only `SELECT`), the model would see
the Postgres error and repair its own query, a few iterations at most. That's
the lightweight-agent use case in this ecosystem, and a good first consumer
once `agent.clj` exists. It would be a change to `hyperphor/nlq`, after an
ellum release.
