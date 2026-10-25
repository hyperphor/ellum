# Model catalog (models.dev) — proposal

Source item: `design/TODO.md` "# models.dev". It asks whether models.dev would work
as the data source for a model selector, and notes that it "doesn't really provide API
access though? That is via https://ai-sdk.dev/".

## Problem

ellum can list model ids (`core/list-models` → provider `/models` endpoints), but those
endpoints return almost nothing useful:

- Anthropic gives `{:id :display_name :created_at}`.
- OpenAI gives `{:id :created :owned_by}`, plus embeddings, tts, whisper, dall-e and
  fine-tunes mixed into the same list.

Neither returns pricing, context or output limits, capabilities (tools, structured
output, reasoning, vision), or deprecation status. That leaves three gaps:

1. **No model selector.** A UI or REPL helper can't offer "models from providers I
   have keys for, that support tool calls, that aren't deprecated, sorted by price".
   This needs metadata ellum doesn't have.
2. **Hand-maintained model facts in code.**
   - `openai/reasoning-model?` is a regex (`^(o\d|gpt-5)`) that decides
     `max_tokens` vs `max_completion_tokens`.
   - The sibling branch `todo/ellum-reply-metadata` adds `usage.clj` with a
     hand-written `default-pricing` table. Its OpenAI half is marked "from memory,
     unverified".
   - Both go stale every time a provider ships a new model.
3. **Consumers hard-code model strings.** nlq's `generate.clj` defaults to `"gpt-4o"`
   and evals take literal `{:provider :model}` configs. Nothing checks that those ids
   exist or tells you what they cost.

## What models.dev actually is (checked 2026-10-25)

- An MIT-licensed, community-maintained database of model specs (GitHub
  `anomalyco/models.dev`, formerly `sst/models.dev`, ~7.4k stars, pushed to daily). It
  powers opencode. The data is authored as TOML per provider and model, and published
  as static JSON.
- **It does provide API access.** The TODO's worry is half right: the API is metadata
  only, with no inference, but it is a perfectly usable HTTP API.
  - `GET https://models.dev/api.json`: providers → models. 232 providers, 8929
    provider-model entries, **5.9 MB**.
  - `GET https://models.dev/models.json`: provider-agnostic model facts (606 models).
  - `GET https://models.dev/catalog.json`: both together (6.4 MB).
  - `?type=all|decision` selects specialized model types. They are omitted by default.
  - `GET https://models.dev/logos/{provider}.svg`
  - Served by Cloudflare with `access-control-allow-origin: *` (so the browser can
    fetch it directly) and an ETag. A conditional `If-None-Match` GET returns **304**.
  - There is no per-provider slicing: `/api/anthropic.json` 302-redirects to `/`. You
    always fetch the whole file.
- **ai-sdk.dev** is Vercel's AI SDK, a TypeScript inference library. models.dev ids
  are AI SDK ids, and each provider carries an `npm` field (`@ai-sdk/anthropic`). It
  has no bearing on a Clojure client beyond the shared id convention.

### Data shape (per model, under `[provider-id :models model-id]`)

```
{:id "claude-haiku-5-5" :name "Claude Haiku 5.5" :family "claude-haiku"
 :description "..." :status "deprecated"|"beta"|"alpha"|absent
 :reasoning true :reasoning_options [{:type "effort" :values [...]} ...]
 :tool_call true :structured_output true :temperature false :attachment true
 :modalities {:input ["text" "image" "pdf"] :output ["text"]}
 :limit {:context 1000000 :output 128000 (:input ...)}
 :cost {:input 0.1 :output 0.5 :cache_read 0.01 :cache_write 0.125
        :tiers [{:input 0.5 ... :tier {:type "context" :size 100000}}]}
 :knowledge "2026-06" :release_date "..." :last_updated "..."
 :open_weights false :canonical_model_id "anthropic/claude-haiku-5-5"}
```

Provider entries carry `{:id :name :env ["ANTHROPIC_API_KEY"] :npm :doc :api}`. 207 of
the 232 providers have an `:api` base URL, such as `https://openrouter.ai/api/v1` or
`https://api.deepseek.com`. First-party `anthropic` and `openai` have no `:api` field.

- Cost is **USD per 1M tokens** (per `packages/core/src/schema-v2.ts`). `:tiers` are
  conditional prices, e.g. a price above a context-size threshold.
  `:experimental :modes` holds service-tier variants (flex/priority).
- Field coverage across all 8929 entries:
  - Always present: `id name tool_call reasoning limit modalities`.
  - `cost` on 8534. `structured_output` on 6801. `knowledge` on 5223.
  - `status` on 978, of which 904 are deprecated.

## Spike results

The spike was a scratch script run with `lein run -m clojure.main` in this worktree. It
used hato and cheshire, which are already deps. It is not committed.

| Check | Result |
|---|---|
| Fetch and parse all of `api.json` with hato `:as :json` | ~570 ms |
| Conditional re-fetch with the ETag | 304, ~170 ms |
| Live `list-models :anthropic` (14 ids) joined on id against models.dev `anthropic` (17) | **14/14 matched**. The 3 extras are undated aliases (`claude-haiku-4-5` etc.). |
| OpenAI coverage | Not measured: there is no `OPENAI_API_KEY` on this machine. Expect a large unmatched remainder, because `/models` includes non-chat models and models.dev lists only 51. That is fine, because "unmatched" mostly means "not a chat model". |
| `openai/reasoning-model?` regex vs catalog `:reasoning` flag, over models.dev's 51 OpenAI models | **7 disagreements**, all models the catalog says reason but the regex misses: `gpt-6-astra`, `gpt-6-luna`, `gpt-6-sol`, `gpt-6.1-sol`, `gpt-daybreak-{blue,red}-latest`, `gpt-realtime-2.1`. |

**Possible existing bug.** For the gpt-6 family, `openai/complete` currently sends
`max_tokens`. Unverified: whether the API rejects that or ignores it. Either way, this
is exactly the staleness the catalog would remove.

**Price check.** models.dev agrees with the sibling `usage.clj` table on the OpenAI
input/output/cache-read rates I checked (gpt-5, gpt-5-mini, gpt-4.1, gpt-4o, o3), and on
Anthropic input/output rates. It disagrees on Anthropic **cache-read** for the 5.x
models:

| Model | models.dev cache-read | `usage.clj` cache-read (assumes 0.1 × input) |
|---|---|---|
| opus-5-5 | 0.2 | 0.4 |
| sonnet-5-5 | 0.1 | 0.2 |
| fable-5-1 | 0.25 | 1.0 |

One of them is wrong. That is itself an argument for one shared source of truth.

**Gotcha found.** hato `:as :json` keywordizes keys, and OpenRouter-style ids contain
`/` (`meta/muse-spark-1.3`). As keywords these become namespaced, and `name` silently
drops the prefix. Parse the model maps with string keys, or look models up by their
`:id` value, never by the keyword key.

## Options

### Data source

| Source | Pricing | Capabilities | Coverage | Notes |
|---|---|---|---|---|
| Provider `/models` APIs (status quo) | no | no | exactly what your key can call | Authoritative for availability. Needs a key per provider. |
| **models.dev** `api.json` | yes, tiered | rich (tools, structured, reasoning options, modalities, limits, status) | 232 providers | MIT. Static JSON with CORS and ETag. AI SDK ids match first-party ids. |
| OpenRouter `GET /api/v1/models` | yes (string USD/token) | modalities, context, `supported_parameters` | 467 models, OpenRouter ids only | Only describes OpenRouter's own routing. Ids are `vendor/model`. |
| LiteLLM `model_prices_and_context_window.json` | yes, very detailed (batch, 1h cache) | `supports_*` flags | 4308 entries | Flat, LiteLLM-specific keys. Good pricing, weaker as a selector (no descriptions or families). |

models.dev is the best fit for a selector: it has descriptions, families, status and
capability flags, uses first-party ids, and has a clean provider→model structure.
LiteLLM is the better fallback if pricing precision (batch, 1-hour cache) ever matters
more than selector UX.

### Delivery

- **(a) Live fetch with caching.** Fetch on first use and memoize in an atom.
  Revalidate with the ETag after a TTL (e.g. 24h). Always current, but adds a network
  dependency and a ~0.5 s first hit.
- **(b) Checked-in snapshot.** Store a filtered resource (only providers ellum
  supports, which is a few hundred KB, not 5.9 MB), regenerated by a REPL-only fn.
  This is the same pattern nlq-aact uses for its schema. Works offline and is
  deterministic in tests, but goes stale between releases.
- **(c) Hybrid (recommended).** Load the snapshot by default. `refresh-catalog!`
  replaces it with live data, optionally automatically when `:ellum-catalog-refresh`
  is set. A failed fetch falls back to the snapshot with a warning. It never throws
  into a `complete` call.

## Recommendation

Adopt models.dev as ellum's model-metadata source, delivered via (c). Expose it as a
**data API in ellum**. ellum has no UI. The actual selector widget belongs in a
consumer (nlq's frontend, or way), and it can read the same data from ellum through an
endpoint, or straight from models.dev in the browser thanks to CORS.

Scope v1 to the providers ellum actually implements (`anthropic`, `openai`). Leave
generic OpenAI-compatible providers driven by the catalog's `:api` and `:env` fields as
an explicit later option. It's tempting, since it would give ellum DeepSeek, Groq,
OpenRouter and others nearly for free, but it is a separate feature.

## Sketch

New namespace `hyperphor.ellum.models`:

```clojure
(model-info :anthropic "claude-sonnet-4-6")
;; => {:id "claude-sonnet-4-6" :name "Claude Sonnet 4.6" :provider :anthropic
;;     :family "claude-sonnet" :reasoning? true :tools? true :structured? true
;;     :temperature? true :input #{:text :image :pdf}
;;     :context 1000000 :max-output 128000
;;     :cost {:input 3 :output 15 :cache-read 0.3 :cache-write 3.75}
;;     :status nil :knowledge "2025-08-31" :release-date "2026-02-17" :raw {...}}

(models :provider :anthropic)                         ; normalized seq, newest first
(models :tools? true :structured? true :deprecated? false
        :providers #{:anthropic :openai})            ; selector-style filter
(refresh-catalog!)                                    ; live fetch with ETag; falls back to snapshot
```

- **Normalization.** Translate snake_case to ellum-style kebab keys with `?` booleans,
  and turn modalities into keyword sets. Lookup is exact id first, then the id with a
  date suffix or `-latest` stripped, the same rule as sibling `usage/model-pricing`.
  `:raw` keeps the original.
- **Custom providers.** A custom provider from `make-source` resolves through
  `core/resolve-provider`, so `(model-info my-source "...")` works.
- **`list-models` enrichment.** Add an opt-in `(list-models :anthropic :enrich true)`,
  or a separate `core/available-models`. Either one joins live ids with catalog info,
  giving "what my key can call, plus what it is". Ids the key can call but the catalog
  lacks come back with only `:id`.
- **Pricing.** `usage/model-pricing` (sibling branch) consults the catalog first and
  falls back to its table, so the hand-written table becomes overrides only.
  Use base `:cost` only. Ignore `:tiers` and `:experimental` in v1, and document that
  cost is approximate above tier thresholds.
- **Reasoning flag.** `openai/reasoning-model?` checks `(:reasoning? (model-info
  :openai model))` and keeps the regex as a fallback for unknown ids.

## Implementation plan

| File | Change |
|---|---|
| `src/clj/hyperphor/ellum/models.clj` (new) | Load the snapshot, `refresh-catalog!` (hato GET with `If-None-Match`, string keys), normalize, `model-info`, `models` filter. |
| `resources/ellum/models-dev.json` (new) | Snapshot filtered to `anthropic` and `openai`. Lein's default `:resource-paths` already covers `resources/`. |
| `src/clj/hyperphor/ellum/models.clj` `(comment ...)` | `regenerate-snapshot!`, REPL-only. Fetches, filters, writes the resource. |
| `src/clj/hyperphor/ellum/core.clj` | `available-models` (or `:enrich` on `list-models`). Re-export `model-info`. |
| `src/clj/hyperphor/ellum/providers/openai.clj` | `reasoning-model?` consults the catalog. Makes `models` a dependency of the provider ns. Check for a require cycle, since `models` must not require providers. |
| `src/clj/hyperphor/ellum/usage.clj` (once `todo/ellum-reply-metadata` lands) | `model-pricing` uses catalog `:cost` first. |
| `test/clj/hyperphor/ellum/models_test.clj` (new) | Against a tiny fixture slice (2–3 models, including a `/`-containing id and a date-suffixed lookup). No network. |
| `design/TODO.md` | Point the models.dev item here. |

Rough size: ~150 lines of Clojure plus the fixture. No new dependencies.

## Open questions

- Should the snapshot live in the jar at all? It carries a stale-data risk for
  downstream users. The alternative is option (a) only, with the fixture used just for
  tests.
- Enrich `list-models` in place, which changes its return shape for existing callers,
  or add a new fn? The plan assumes a new fn.
- Who owns the selector widget: nlq's frontend (which already has the `:llm` config),
  or a generic component in way?
- Do the gpt-6 models actually reject `max_tokens`? This needs one cheap live call
  with an OpenAI key before claiming it as a bug fix.
