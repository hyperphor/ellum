# TODO

This is like a ghetto version of alzabo instance get

(generate-instances "fruit" 10) → ["apple"",...]
extend-set (in ELI)

# models.dev

This would be a good resources for a model selector I suppose.
https://models.dev/
doesn'r really provide API access though? That is via https://ai-sdk.dev/

# Multiple keys/provider

Eg llm objects like 
{:provider ..
:api-key ..
:model <etc and other defaults could live here, system prompt, why not.}

# Prompt composition

Not clear what is wanted here. It's also too easy to do by hand

We have u/tx for templating

# Structured query support

(defllm population 
  [city]
  (structured-query "What is the population of {{city}} city?" :number))
  
This is super-trivial but it would be handy to have it handy

# Pimento support

This means being able to augment queries with RAG info, which is obviously generally useful!
This might be too OpenAI specific but that's OK (and probably other providers copy them anyway)

# Hm this bleeds into NLQ

Sort of...I suppose the boundary is clear, but LLMs have a way of breaking my normal modularity instincts.

# Agent support

Of course, but what does that mean? 

Something lightweight, resisting a whole complicated infrastructure (not that I don't want to build that, but it seems like a separable layer)

