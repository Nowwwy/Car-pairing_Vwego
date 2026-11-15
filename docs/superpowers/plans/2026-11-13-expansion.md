# Car Pairing expansion implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans. Independent routing, storage, Android and language/places modules may be implemented concurrently under their file ownership contracts.

**Goal:** Extend the existing native Pleos app with real road estimates including stop dwell time, persisted choice-based recommendations, durable groups, leader handoff, QR invitations, parking records and reusable meetings.
**Architecture:** Keep Kotlin/Pleos NaviHelper, FusedLocation, Vehicle and ON_DEVICE speech. Python remains the authenticated local group server. SQLite stores group snapshots and private client profiles. Optional OSRM/FOSSGIS estimates explicitly exclude live traffic; SDK continues to choose native navigation routes.
**Tech Stack:** Kotlin Android API34+, Pleos existing versions, stdlib Python3.10+, SQLite, OSRM HTTP, OSM place services, local Ollama; ZXing core3.5.3 for QR.
**Spec:** User's supplied proposal, section4 plus previously requested feasible extensions. User authorized autonomous implementation and external providers. Retain baseline in carpairing_group_dev; this directory is the isolated extension.

## Global constraints
- No invented POIs, road duration or vacancy data. A provider error is an unavailable estimate, not a straight-line ETA.
- No native route changes without driver confirmation. Native SDK chooses the final geometry.
- Full input/selection only in P. Short accept/reject and voice remain available while driving.
- Distinguish SDK ETA, external road estimate, dwell minutes, and live-traffic support (false for OSRM).
- Stable random client_id in private app preferences. Group credentials never appear in public discovery or profiles. Learning opt-in; clear history supported.
- No paid API keys assumed, no public deployment without a configured HTTPS server. Gleo/HYBRID speech credentials and physical microphone testing are external blockers, not claimed complete.

## Review focus
- Reject nonfinite coordinates, >2 intermediate stops, excessive dwell, and malformed route responses.
- Verify server restart preserves tokens/events but resets stale GPS/ETA presence.
- Verify another room/member cannot transfer leadership, read parking or edit a group's destination.
- Keep numbers, uncertainty and negation when normalizing speech text; unsupported utterances stay original.
- Network estimates/places must not block one-second group heartbeats; public routing rate <=1req/s and cached.

## API contract for Android
All POSTs except /rooms,/join,/nearby require Authorization Bearer plus code in body. Existing endpoints retained.
- /rooms,/join accept optional client_id,plate. Return existing connection fields plus is_leader, member_id. /sync adds leader_id and companions[].leader,plate; top-level shared_place {name,latitude,longitude,address}.
- /trip/estimate: origin point, destination optionalPlace(default shared), stops [{name,latitude,longitude,address,kind,dwell_minutes}], profile optional car/foot. Returns base/via {duration_seconds,distance_meters,geometry:[[lon,lat],...]}, dwell_seconds,total_seconds,added_seconds,provider,traffic_included:false,notice. 502 on routing unavailability. No SDK mutation.
- /places supports kind parking/rest/fuel; returns places with optional recommendation,used_count and provider/availability_notice. Learning ranks only if enabled.
- /profile returns {learning_enabled,plate,choices_count}; /preferences updates learning_enabled(bool),plate(str).
- /choices: {place,kind,accepted,dwell_minutes,request_id}; private opt-in learning only. /history/clear removes own learned choices.
- /parking/save: optional place plus floor<=24,zone<=40,note<=120. /parking/list -> {records:[{id,place,floor,zone,note,created_at}]}; /parking/delete {id}. Positions are actual GPS or explicitly absent.
- /leader {member_id}: current leader only, target online. /leave removes own connection and auto transfers host if necessary.
- /discovery {enabled}: leader only, default false. /nearby {latitude,longitude,location_age_ms} -> {rooms:[{code,destination,distance_meters,members_count}]} within3km of recent opt-in leader location.
- /meeting/save {name}; /meetings -> {meetings:[{id,name,place}]}; /meeting/delete {id}. Reuse creates a new group via /rooms and requires normal participant consent.
- /destination {place}: leader only after own SDK confirmation. Updates shared destination and emits kind destination,place; each recipient separately confirms. /messages accepts destination_applied acknowledgement as well as prior kinds.
- QR contents carpairing://join?code=NNNNNN. Decode image picker or incoming deep link, validate exactly six digits, then normal join/consent.

## Work units
- [ ] Restore the verified v0.6 baseline fixes (foreground sync, prompt persistence, zero-coordinate waypoint name readback) before Android editing.
- [ ] Routing module: test elapsed driving + dwell and no-route errors, implement bounded/cache/rate-limited public HTTP adapter, probe live provider.
- [ ] SQLite module: test profile/parking/choice isolation, clear history, durable snapshots and restart; implement storage with lock and transactions.
- [ ] Language/place module: test conservative normalization and fuel/filter validation; retain original on ambiguous text and unavailable provider.
- [ ] Server integration: add HTTP contract tests first; implement leadership, durable groups, private utilities and routing dispatch outside room lock.
- [ ] Android: add a dedicated expanded-features panel and separate network executor, real estimate preview/confirm, QR/deep links, profile/parking/meetings/leader UI, text normalization feedback. Keep old flows.
- [ ] Build on online PC026 using its existing SDK/JBR; install isolated app build and restart it, exercise two cars and feature cases.
- [ ] Run Python suite, Android build/tests appropriate to changes, actual provider smoke tests. Package source/APK/API matrix and save deliverables.
