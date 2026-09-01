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
 
## Is this useful?

Maybe for small utility functions, the kind of thing that goes in multtool. Not very useful for writing code in real systems, where the pieces depend on each other. Claude Code etc can do this.

So this is more of an idea generator than something useful.

Nonetheless I want to equip every repl I use with this, just because.
