# Context Parking Implementation Plan

> Native execution in the current authorized session; preserve the existing app and local model.

**Goal:** Resolve indirect parking needs against the active destination and understand contextual voice choices.
**Architecture:** Ground model interpretation in client navigation plus own confirmed server events. Keep search results and route commits deterministic; use bounded local inference for interpretation and clarification.
**Tech Stack:** Python stdlib, Ollama qwen3:4b, Kotlin/Pleos 2.1.
**Spec:** ../specs/2026-11-15-context-parking-design.md

## Constraints and review focus
- Do not invent vacancies/coordinates or silently change a destination.
- Current client target wins over shared room and historical targets; ignore peers' changes.
- Negation, ambiguous selection, repeated requests, stale async results and model failures must fail conservatively.
- All session state is bounded and belongs to the authenticated member.

## Tasks
- [x] Add server regression cases for changed destination, parking paraphrases, explicit relay/current-location, missing position and unavailable model; observe failures.
- [x] Extend voice_requests.py local interpretation context and add grounded voice-choice interpretation; add model schema/negation/invalid-index tests.
- [x] Update voice_api.py/server.py for contextual parking search and voice-choice endpoint; preserve existing report composer.
- [x] Update VoiceRoutePanel.kt with bounded previous-utterance memory, search basis explanation, vacancy notice and contextual choice requests; add pure Kotlin history tests.
- [x] Run Python suite, Android build/tests, then real-model cases and device UI checks. Deploy preserving room data and save source ZIP with updated verification.
