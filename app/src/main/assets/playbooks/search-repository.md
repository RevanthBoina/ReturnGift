---
id: search_repository
name: Search Repository
triggers:
  - "search github"
  - "find repo"
  - "github search"
  - "look up repository"
---

When the user wants to search GitHub for repositories or code:

1. **open_app** → call open_app(package_name="com.github.android")
2. **get_screen_info** → confirm GitHub home is visible
3. **find_and_tap** → tap the Search icon (content-desc "Search")
4. **input_text** → call input_text(text="[query]")
5. **system_key** → call system_key(key="enter")
6. **get_screen_info** → read the top results
7. **finish** → call finish(summary="Showing GitHub results for '[query]'.")

Extract from the user's request:
- query = repository name, topic, or code keyword

- Good: `search github for react native`, `find repos about machine learning`
- Not this playbook: `open github` → open-and-navigate, `search the web for [query]` → open-and-search
