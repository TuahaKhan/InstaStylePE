# InstaStyle — CleverTap Native Display stories demo

A deliberately plain Android app whose only interesting part is the CleverTap integration: an
Instagram-style story tray where **every circle, frame, duration and deep link comes from a
CleverTap Native Display campaign**, plus an analytics model that answers the client's questions
with four custom events.

Built to demo the pattern to a client, not to be pretty.

## Quick start

```properties
# gradle.properties (or ~/.gradle/gradle.properties, to keep it out of git)
CLEVERTAP_ACCOUNT_ID=XXX-XXX-XXXZ
CLEVERTAP_TOKEN=xxx-xxx
CLEVERTAP_REGION=eu1
```

```bash
./gradlew :app:installDebug
adb logcat -s InstaStyleCT CleverTap     # every SDK call and event is logged
./gradlew :app:testDebugUnitTest         # payload parser tests
```

With no credentials the app still runs, on a bundled sample tray — so you can see the UI before the
campaign exists.

## What it demonstrates

- Native Display custom key-value payload → live story tray, merged across any number of display
  units, ordered by the marketer.
- Full story player: per-frame timers, segmented progress, **auto-advance into the next circle**,
  tap/hold/swipe gestures, like with a count that goes up and back down, share, per-frame CTA.
- Four custom events (`Story Tray Rendered`, `Story Circle Opened`, `Story Viewed`,
  `Story Interacted`) that fan out through properties rather than event names.
- Profile counters (`incrementValue`/`decrementValue`, `addMultiValueForKey`/
  `removeMultiValueForKey`) that give a like count which genuinely **decrements** — the client's
  one "you probably can't do this" requirement.

## Read next

**[docs/CLEVERTAP_NATIVE_DISPLAY.md](docs/CLEVERTAP_NATIVE_DISPLAY.md)** — architecture, the
marketer's campaign setup and both key-value schemas, the full event/property dictionary, a custom
board recipe, an honest account of what Native Display can't do here, and the Product Experiences
comparison to pitch from.
