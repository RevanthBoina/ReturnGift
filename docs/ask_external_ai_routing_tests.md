# ASK_EXTERNAL_AI — Routing Test Utterances

**Date:** 2026-08-01
**Purpose:** Validate that `ask_external_ai` triggers correctly and does not collide
with `web_search`, `open_app`, `search_video`, `search_place`, or `create_design`.
**Format:** `utterance → expected_skill [expected_app] [expected_capability]`

---

## SHOULD route to `ask_external_ai`

### Direct app + query (14 apps)
```
ask chatgpt about black holes                    → ask_external_ai [chatgpt] [chat]
ask grok what happened in the news today         → ask_external_ai [grok] [chat]
search perplexity for quantum computing          → ask_external_ai [perplexity] [research]
get gemini to explain photosynthesis             → ask_external_ai [gemini] [explain]
ask claude to review my essay                    → ask_external_ai [claude] [analyze]
have copilot write a cover letter                → ask_external_ai [copilot] [generate]
use poe for a creative writing prompt            → ask_external_ai [poe] [generate]
translate hello world to Spanish with deepl      → ask_external_ai [deepl] [translate]
paraphrase this paragraph with quillbot          → ask_external_ai [quillbot] [rewrite]
create a presentation about climate change with gamma → ask_external_ai [gamma] [generate]
research cancer treatments on consensus          → ask_external_ai [consensus] [research]
ask notebooklm about my uploaded document        → ask_external_ai [notebooklm] [chat]
analyze this pdf with humata                     → ask_external_ai [humata] [analyze]
check my grammar with grammarly                  → ask_external_ai [grammarly] [rewrite]
```

### Generic AI queries (no app specified → default chatgpt)
```
ask ai about the history of Rome                 → ask_external_ai [chatgpt] [chat]
what does the ai say about inflation             → ask_external_ai [chatgpt] [chat]
have ai summarize this article                   → ask_external_ai [chatgpt] [summarize]
chat with gpt about machine learning             → ask_external_ai [chatgpt] [chat]
ask the assistant what is recursion              → ask_external_ai [chatgpt] [explain]
prompt ai with write a haiku about rain          → ask_external_ai [chatgpt] [generate]
get an ai answer for how does wifi work          → ask_external_ai [chatgpt] [explain]
ask ai to explain blockchain simply              → ask_external_ai [chatgpt] [explain]
have ai write a product description              → ask_external_ai [chatgpt] [generate]
ask ai to debug my python code                   → ask_external_ai [chatgpt] [code]
```

### Capability-specific triggers
```
summarize this text using ai                     → ask_external_ai [chatgpt] [summarize]
translate good morning to Hindi                  → ask_external_ai [deepl] [translate]
translate this to French                         → ask_external_ai [deepl] [translate]
rewrite this email to be more professional       → ask_external_ai [quillbot] [rewrite]
explain what machine learning is simply          → ask_external_ai [chatgpt] [explain]
write python code to sort a list                 → ask_external_ai [chatgpt] [code]
debug this javascript function                   → ask_external_ai [chatgpt] [code]
analyze this data and give insights              → ask_external_ai [chatgpt] [analyze]
research climate change with sources             → ask_external_ai [consensus] [research]
generate a blog post about travel                → ask_external_ai [chatgpt] [generate]
create a pitch deck about my startup             → ask_external_ai [gamma] [generate]
find scientific papers on alzheimers             → ask_external_ai [consensus] [research]
```

### Clipboard workflows
```
ask ai about what's in my clipboard             → ask_external_ai [chatgpt] [chat]
summarize my clipboard text                      → ask_external_ai [chatgpt] [summarize]
translate my clipboard to German                 → ask_external_ai [deepl] [translate]
rewrite what I copied                            → ask_external_ai [quillbot] [rewrite]
```

### Inference hint variants
```
ask gpt about dark matter                        → ask_external_ai [chatgpt] [chat]
ask open ai about the stock market               → ask_external_ai [chatgpt] [chat]
ask chat gpt to write a poem                     → ask_external_ai [chatgpt] [generate]
ask gpt-4 to explain quantum physics             → ask_external_ai [chatgpt] [explain]
ask x ai about elon musk                         → ask_external_ai [grok] [chat]
ask xai about twitter                            → ask_external_ai [grok] [chat]
ask twitter ai about trending topics             → ask_external_ai [grok] [chat]
ask google ai about the weather                  → ask_external_ai [gemini] [chat]
ask bard about cooking recipes                   → ask_external_ai [gemini] [chat]
ask google gemini to plan my trip                → ask_external_ai [gemini] [chat]
ask anthropic about AI safety                    → ask_external_ai [claude] [chat]
ask claude ai to write a story                   → ask_external_ai [claude] [generate]
ask bing chat about news                         → ask_external_ai [copilot] [chat]
ask bing ai to generate an image                 → ask_external_ai [copilot] [generate]
ask microsoft copilot about excel formulas       → ask_external_ai [copilot] [chat]
ask quora poe about philosophy                   → ask_external_ai [poe] [chat]
use deepl translate to convert this text         → ask_external_ai [deepl] [translate]
grammar check this sentence                      → ask_external_ai [grammarly] [rewrite]
check spelling with grammarly                    → ask_external_ai [grammarly] [rewrite]
proofread my email                               → ask_external_ai [grammarly] [rewrite]
use notebook lm to analyze my notes              → ask_external_ai [notebooklm] [analyze]
use google notebook to summarize my document     → ask_external_ai [notebooklm] [summarize]
analyze this pdf with humata ai                  → ask_external_ai [humata] [analyze]
search scientific papers on consensus            → ask_external_ai [consensus] [research]
```

---

## SHOULD NOT route to `ask_external_ai` (anti-trigger validation)

### → `web_search`
```
google black holes                               → web_search [NOT ask_external_ai]
search the web for quantum computing             → web_search [NOT ask_external_ai]
look up inflation online                         → web_search [NOT ask_external_ai]
find information about climate change            → web_search [NOT ask_external_ai]
search for python tutorials                      → web_search [NOT ask_external_ai]
browse to github.com                             → web_search [NOT ask_external_ai]
look up the weather on chrome                    → web_search [NOT ask_external_ai]
```

### → `open_app`
```
open chatgpt                                     → open_app [NOT ask_external_ai]
launch perplexity                                → open_app [NOT ask_external_ai]
open the gemini app                              → open_app [NOT ask_external_ai]
start claude                                     → open_app [NOT ask_external_ai]
go to copilot                                    → open_app [NOT ask_external_ai]
bring up grok                                    → open_app [NOT ask_external_ai]
open deepl                                       → open_app [NOT ask_external_ai]
```

### → `search_video`
```
search youtube for machine learning tutorials    → search_video [NOT ask_external_ai]
play a video about black holes                   → search_video [NOT ask_external_ai]
find videos about cooking                        → search_video [NOT ask_external_ai]
watch a documentary about space                  → search_video [NOT ask_external_ai]
```

### → `search_place`
```
find a coffee shop near me on maps               → search_place [NOT ask_external_ai]
navigate to the airport                          → search_place [NOT ask_external_ai]
get directions to downtown                       → search_place [NOT ask_external_ai]
```

### → `create_design`
```
create a design in canva                         → create_design [NOT ask_external_ai]
make a social media post on canva                → create_design [NOT ask_external_ai]
open canva and create a poster                   → create_design [NOT ask_external_ai]
```

---

## Disambiguation edge cases

| Utterance | Expected | Reason |
|-----------|----------|--------|
| `search for python` | `web_search` | No "ai" or "ask" keyword |
| `search ai tools` | `web_search` | "search" without "ask" routes to web |
| `ask about python` | `ask_external_ai` | "ask" keyword present |
| `open chatgpt and ask about python` | `ask_external_ai` | Query present overrides open_app |
| `translate hello to Spanish` | `ask_external_ai [deepl]` | capability=translate + target_language |
| `translate this document` | `ask_external_ai [deepl]` | capability=translate |
| `make a presentation` | `ask_external_ai [gamma]` | "presentation" → gamma disambiguation rule |
| `create slides about AI` | `ask_external_ai [gamma]` | "slides" → gamma disambiguation rule |
| `research papers on cancer` | `ask_external_ai [consensus]` | "papers" → consensus disambiguation rule |
| `find scientific studies on sleep` | `ask_external_ai [consensus]` | "studies" → consensus rule |
| `grammar check` | `ask_external_ai [grammarly]` | "grammar" → grammarly disambiguation rule |
| `fix my spelling` | `ask_external_ai [grammarly]` | "spelling" → grammarly rule |
| `write code for sorting` | `ask_external_ai [chatgpt] [code]` | "code" capability |
| `debug my app` | `ask_external_ai [chatgpt] [code]` | "debug" → code capability |

---

## Routing validation summary

| Category | Total utterances | Expected: ask_external_ai | Expected: other skill |
|----------|-----------------|--------------------------|----------------------|
| Direct app + query | 14 | 14 | 0 |
| Generic AI queries | 10 | 10 | 0 |
| Capability-specific | 12 | 12 | 0 |
| Clipboard workflows | 4 | 4 | 0 |
| Inference hint variants | 30 | 30 | 0 |
| Anti-triggers (web_search) | 7 | 0 | 7 |
| Anti-triggers (open_app) | 7 | 0 | 7 |
| Anti-triggers (search_video) | 4 | 0 | 4 |
| Anti-triggers (search_place) | 3 | 0 | 3 |
| Anti-triggers (create_design) | 3 | 0 | 3 |
| Edge cases | 14 | 10 | 4 |
| **Total** | **108** | **80** | **28** |

Target precision ≥ 0.85, recall ≥ 0.80. All 80 positive cases have matching triggers or
inference hints in `ask-external-ai.yaml`. All 28 negative cases have matching anti-triggers
or disambiguation rules.
