# REPL language extension – programming with NL

This is a hack, I realized you can turn any REPL into a natural language programming environment:

```clojure
(defextend (bag= b1 b2) "true if the collection are bag equal (that is, contain the same elements but possibly in a different order)")
```

Produces:

```clojure
(defn bag=
  "Returns true if the collections contain the same elements regardless of order."
  [b1 & bs]
  (apply = (frequencies b1) (map frequencies bs)))
 ```
 
 Cute solution and even more general then the spec since it takes an arbitrary number of arguments.
 
## Open questions

This is the sort of functionality that should be everywhere (on u/) but too many dependencies. 

Where to store results (namespace and file) also open to question.



## Is this useful?

Maybe for small utility functions, the kind of thing that goes in multtool. Not very useful for writing code in real systems, where the pieces depend on each other. Claude Code etc can do this.

So this is more of an idea generator than something useful.

Nonetheless I want to equip every repl I use with this, just because.

## Similar tools

- **`method_missing` / `doesNotUnderstand:`** (Ruby / Smalltalk) — the real
  ancestor of this idea, decades before LLMs: intercept a call to an
  undefined symbol and decide what it should do, lazily, the first time
  it's hit. `defextend` is that pattern with an LLM as the thing deciding.

- **[Marvin's `@fn`](https://github.com/PrefectHQ/marvin)** and
  **[magentic's `@prompt`](https://github.com/jackmpcollins/magentic)**
  (Python) — decorate a function that has a signature and docstring but no
  body; calling it sends the args to an LLM and returns its answer
  directly. Same declare-intent-let-the-model-fill-it-in feel, but the
  model is consulted on *every* call, not just the first — no source ever
  gets written down, so there's nothing to read, edit, or run offline
  afterward. `defextend` trades that (a network round-trip on first use
  only) for a real, inspectable `defn` in `generated.clj` forever after.

- **[TanStack "Code Mode" skills](https://tanstack.com/blog/tanstack-ai-code-mode)**
  — closest actual analog. An agent solves a problem by writing and running
  code, then calls `register_skill` to persist the working implementation
  as a named, cached routine; later requests reuse it instead of
  regenerating. Same cache-after-first-success shape as `defextend`, just
  operating at agent-tool-registration scale rather than "define me a
  function in this REPL."

- **Claude Code's own Skills** (what's driving this very session) — a
  name resolves to a cached, reusable capability instead of being
  re-derived from scratch each time. Conceptually adjacent, but skills are
  authored ahead of time by a human or agent, not synthesized on the spot
  the first time something calls their name.
