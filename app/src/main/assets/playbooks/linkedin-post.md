# LinkedIn Post (bounded executor reference flow)

Post text to the LinkedIn feed. This flow is the reference for the bounded
state-machine executor: DETECT → ACT → VERIFY → MOVE ON, ~2 screen reads total.

## Route (deterministic)
1. START → CHECK_TARGET_APP: is com.linkedin.android foreground?
2. OPEN_TARGET_APP if not (verified launch; FAIL after 2 retries).
3. FIND_TARGET "Start a post" (text → content-desc → resource-id) → tap.
4. PERFORM_ACTION input: tap composer → wait for editable focus → clear →
   set text → verify field content. No clipboard.
5. FIND_TARGET "Post" → tap.
6. VERIFY_ACTION: ONE read — foreground is LinkedIn and "Post successful"
   confirmation is visible. Done.

## Budgets
60s wall clock · ≤8 screen reads · ≤15 actions · ≤2 retries per state ·
≤2 consecutive passive reads. Normal run: ~2 screen reads, no indefinite retries.

## Never
- Re-read an unchanged screen without an action in between.
- Reuse a node_id across a transition — re-resolve semantically.
- Restart the whole flow when one step fails — retry only the current state.
