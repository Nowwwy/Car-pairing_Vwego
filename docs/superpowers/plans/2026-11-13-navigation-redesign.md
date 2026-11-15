# Navigation UI redesign

Goal: remove coordinate input; map-first native navigation presentation with floating destination/ETA and group cards, fixed bottom actions, no long list on the map.

User delegates design and asks to install/reopen. Retain Pleos integration and all consent/driver checks. Native Kotlin Views, no new dependencies. Keep real map attribution and no fabricated road route or turn cues.

- [x] MainActivity: map-first FrameLayout for home/map/native; secondary features in dialogs; message offers remain rendered in an overlay; dynamic ETA and companion text update.
- [x] Remove all visible latitude/longitude inputs, including expanded destination dialog. Select existing Pleos destination or default Gangnam Exit2; do not associate arbitrary names with default coordinates.
- [x] Map: deliberate whitespace, overlay cards with clear hierarchy, compact four-button dock, pan/zoom/recenter.
- [x] Verify build/unit checks; stop both old app processes, install same signed APK and reopen; inspect screenshot and coordinate-free creation form.
- [x] Save updated source patch to persistent file; keep old release unchanged.
