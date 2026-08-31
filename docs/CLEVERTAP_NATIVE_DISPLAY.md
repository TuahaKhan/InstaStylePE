# InstaStyle Stories on CleverTap Native Display

Demo reference for the InstaStyle stories use case: an Instagram-style story tray whose entire
content — circles, frames, durations, deep links — is authored by a marketer in a **CleverTap
Native Display** campaign, with an analytics model designed to answer the client's questions using
as few distinct event names as possible.

- [1. What the app does](#1-what-the-app-does)
- [2. Setup](#2-setup)
- [3. Marketer guide: creating the campaign](#3-marketer-guide-creating-the-campaign)
- [4. Event and property dictionary](#4-event-and-property-dictionary)
- [5. Custom board recipe](#5-custom-board-recipe)
- [6. The like/unlike decrement question](#6-the-likeunlike-decrement-question)
- [7. Honest limits of Native Display here](#7-honest-limits-of-native-display-here)
- [8. What Product Experiences would do better](#8-what-product-experiences-would-do-better)

---

## 1. What the app does

```
  Marketer                    CleverTap                        InstaStyle app
  --------                    ---------                        --------------
  Native Display                                    InstaStyleApp.attachBaseContext
  campaign, type                                    -> ActivityLifecycleCallback.register
  "Custom Key Value"                                -> CleverTapAPI.getDefaultInstance
        |                                                        |
        |  custom KV: st_tray = {...}                            v
        +----------------------------> [ SDK ] <---- pushEvent("Home Screen Viewed")
                                          |          (campaign trigger)
                                          |
                                          v
                              DisplayUnitListener.onDisplayUnitsLoaded
                                          |
                                          v
                            NativeDisplayRepository  (merge units, parse KV)
                                          |
                                          v
                                    StoryTray  ->  tray of circles on the home screen
                                                   -> StoryViewerActivity (the player)
                                                        |
                                                        v
                                                  StoryAnalytics -> 4 custom events
                                                                 -> profile counters
```

Source map:

| Concern | File |
|---|---|
| Every CleverTap SDK call | `stories/analytics/CleverTapManager.java` |
| Event taxonomy, profile sync | `stories/analytics/StoryAnalytics.java` |
| Display units → tray, merge, fallback | `stories/data/NativeDisplayRepository.java` |
| Custom KV → circles (both schemas) | `stories/data/TrayPayloadParser.java` |
| Watched/liked state on device | `stories/data/StoryStateStore.java` |
| Home screen + tray | `MainActivity.java`, `ui/StoryTrayAdapter.java`, `ui/StoryRingView.java` |
| Story player | `ui/StoryViewerActivity.java`, `ui/StoryProgressView.java` |
| Image cache + prefetch | `util/ImageLoader.java` |

Behaviour implemented in the player: per-frame timers from the payload, segmented progress bars,
**auto-advance into the next circle when a circle finishes**, tap right/left to skip forward/back,
hold to pause, swipe down to close, swipe left/right to change circle, like (with a count that
goes up and back down), share, and an optional CTA per frame. Watched circles drop to a grey ring.

The **provenance line** under the tray ("Native Display · 1 unit · campaign 1234567 · 4 circles,
10 stories · 640 ms") is deliberate demo furniture: during a walkthrough it is the proof that the
tray came from the dashboard and not from the APK.

If no campaign is live, the app renders `app/src/main/assets/sample_tray.json` after 2.5s and says
so in that line. Handy for demoing before the campaign exists; also the exact JSON to paste into
the dashboard.

---

## 2. Setup

1. Put credentials in `gradle.properties` (better: `~/.gradle/gradle.properties`, so they never
   reach git):

   ```properties
   CLEVERTAP_ACCOUNT_ID=XXX-XXX-XXXZ
   CLEVERTAP_TOKEN=xxx-xxx
   CLEVERTAP_REGION=eu1
   CLEVERTAP_STORY_TRIGGER_EVENT=Home Screen Viewed
   ```

   Account ID and Passcode: **Dashboard → Settings → Project → Project ID**. Region must match the
   account's region (`eu1`, `in1`, `sg1`, `us1`, `aps3`, `mec1`; blank for the default US region).
   A wrong or missing region is the single most common reason a first integration sees no data.

2. `./gradlew :app:installDebug`

3. `adb logcat -s InstaStyleCT CleverTap` — every SDK call and every event this app raises is
   logged there, which makes it easy to show a client the payload arriving and the events leaving.

The app runs with no credentials (it falls back to the sample tray), so the build never breaks
while waiting for a project to be provisioned.

---

## 3. Marketer guide: creating the campaign

**Campaigns → + Campaign → Native Display** (under *Messaging Channels*), then pick the
**Custom key-value** template.

**When:** the event named in `CLEVERTAP_STORY_TRIGGER_EVENT`, by default `Home Screen Viewed`
(the app raises it on every home-screen entry).
**Who:** all users, or whatever segment the demo needs.

> **One campaign carries the whole tray.** On mobile, CleverTap delivers only **one** Native
> Display campaign per triggering event — if several qualify, the one created first for that event
> wins, and Native Display on mobile does not expose the priority control that web and in-app
> campaigns have. So put every circle in a single campaign. See the note at the end of this section
> for what the app's unit merging is actually good for.

The custom key-value editor gives you flat text rows: keys and string values, no nesting. So the
tray is authored one of two ways — the app accepts both, and picks whichever it finds.

### Schema A — one row, JSON value (recommended)

Key `st_tray` (aliases: `story_tray`, `stories`, `tray`), value = the whole tray as JSON:

```json
{"v":1,"circles":[
  {"id":"new_arrivals","name":"New Arrivals","order":1,"ring":"#E1306C",
   "avatar":"https://cdn.example.com/new.jpg",
   "stories":[
     {"id":"new_1","image":"https://cdn.example.com/new1.jpg","duration":5,
      "caption":"Monsoon drop is live","deeplink":"instastyle://collection/new","likes":1240},
     {"id":"new_2","image":"https://cdn.example.com/new2.jpg","duration":4}
   ]},
  {"id":"summer_sale","name":"Summer Sale","order":2,"ring":"#F77737",
   "stories":[{"id":"sale_1","image":"https://cdn.example.com/sale1.jpg","duration":6}]}
]}
```

Any number of circles, any number of stories per circle. `app/src/main/assets/sample_tray.json` is
a working four-circle example to copy from.

### Schema B — one row per field (easier to hand-edit)

```
c1_id        = new_arrivals
c1_name      = New Arrivals
c1_order     = 1
c1_ring      = #E1306C
c1_avatar    = https://cdn.example.com/new.jpg
c1_s1_img    = https://cdn.example.com/new1.jpg
c1_s1_dur    = 5
c1_s1_cap    = Monsoon drop is live
c1_s1_link   = instastyle://collection/new
c1_s1_likes  = 1240
c1_s2_img    = https://cdn.example.com/new2.jpg
c1_s2_dur    = 4
c2_id        = summer_sale
c2_name      = Summer Sale
c2_order     = 2
...
```

Prefixes `c1_`, `circle1_`, `circle_1_` all work, as do `s1_`, `story1_`, `story_1_`. Keys are
case-insensitive. Schema B's advantage: a typo costs one field, not the whole tray.

### Field reference

| Circle field | Aliases | Default |
|---|---|---|
| `id` | — | `circle_<n>` |
| `name` | `title`, `label` | the id |
| `order` | `pos`, `position` | payload order |
| `ring` | `ring_color`, `color` | `#E1306C` |
| `avatar` | `cover`, `icon`, `img`, `image` | first story's image |

| Story field | Aliases | Default |
|---|---|---|
| `image` | `img`, `image_url`, `media`, `photo` | **required** — no image, frame dropped |
| `duration` | `dur`, `duration_secs`, `seconds`, `time` | 5s, clamped to 1–30s |
| `deeplink` | `link`, `deep_link`, `action`, `action_url`, `cta` | none (no CTA chip) |
| `caption` | `cap`, `text`, `message` | none |
| `like` | `like_enabled`, `show_like` | `true` |
| `share` | `share_enabled`, `show_share` | `true` |
| `likes` | `like_count`, `base_likes` | 0 (cosmetic starting number) |

### Image specification

| | Story frame | Circle avatar |
|---|---|---|
| **Aspect** | 9:16 portrait | 1:1 square |
| **Recommended** | **1080 × 1920** | **240 × 240** |
| **Minimum** | 720 × 1280 | 192 × 192 |
| **Above which it's wasted** | screen's long edge (~2560) | 512 × 512 |
| **How it's fitted** | `centerCrop`, full-bleed | `centerCrop`, then clipped to a circle |
| **Target file size** | ≤ 300 KB | ≤ 40 KB |
| **Formats** | JPEG, PNG, WebP (static) | same |

Five things that follow from how the player draws these:

- **Frames are cropped, never letterboxed.** `centerCrop` scales until both axes cover the screen and
  crops the overflow. On a modern 20:9 phone a 9:16 frame loses roughly **12% off each side**. If a
  frame carries edge-to-edge text or a logo near the left/right edge, either pull it inwards or
  author at **1080 × 2340** (9:19.5), which fills a tall screen with no side crop while still
  cropping gracefully on a 16:9 one.
- **The top and bottom of every frame sit under chrome.** Progress bars, circle name and close
  button occupy the top ~140dp; caption, CTA chip, heart and share button the bottom ~200dp. That is
  roughly the top 18% and bottom 25% of a tall screen. Keep faces, prices and logos in the middle
  band, and let the edges carry the photograph.
- **Send opaque images.** Frames decode as `RGB_565` — no alpha channel — which halves what each
  cached frame costs in memory. A transparent PNG will composite onto black rather than onto the
  frame behind it.
- **Oversized assets cost download time, not quality.** The loader decodes towards the device's own
  long edge and never below it, so a 4000px master is downsampled on the way in — you pay for the
  bytes over the network and gain nothing on screen. 1080 × 1920 at JPEG quality ~80 is the sweet
  spot.
- **The first frame of a circle is the one that can stall.** While a frame plays, the next one is
  already being prefetched, so a 5-second frame buys 5 seconds of headroom for its successor. The
  very first frame after a cold install has no such cover — which is exactly why file size matters
  more than resolution here.

Static images only, in this build. Animated GIFs render as their first frame and video is not
handled at all; Native Display itself can carry a video URL, but the player would need a
`MediaPlayer`/Media3 surface added to use it.

### Two things worth knowing

- **One campaign per trigger event, so author the whole tray in one campaign.** The app does merge
  every display unit it receives, keyed on circle `id` and sorted by `order` — but that does not
  buy you two campaigns on the same trigger, because CleverTap will only deliver one of them on
  mobile. What the merging is genuinely good for: units arriving on **different** trigger events
  (say an always-on tray on `Home Screen Viewed` plus a seasonal circle triggered elsewhere) and
  units still held in the SDK's on-device cache from an earlier session, which coexist with a fresh
  one instead of fighting it. It also means the app needs no change if CleverTap's delivery rules
  ever loosen.
- **Campaign impressions and clicks are separate from the custom events.** The app calls
  `pushDisplayUnitViewedEventForID` when the tray paints and `pushDisplayUnitClickedEventForID`
  when a circle is opened from the tray. Those are what fill in Impressions / Clicks / CTR on the
  **campaign report**. The four custom events below are what fill in the **custom board**. You need
  both.

---

## 4. Event and property dictionary

**Four** custom events. The fan-out lives in properties, not in event names — distinct event names
are capped per account, clutter every dropdown forever, and can't be merged later; properties are
cheap and can be pivoted freely on a board. Adding a fifth interaction type (save, follow, mute)
later costs a property *value*, not a new event.

Image URLs are deliberately **not** sent as properties: long values, unbounded distinct-value sets,
nothing you can usefully segment on. `story_id` identifies the frame.

### `Story Tray Rendered` — funnel step 1

The tray painted on the home screen.

| Property | Type | Notes |
|---|---|---|
| `payload_source` | string | `native_display` or `fallback` |
| `tray_size` | int | circles shown |
| `total_stories` | int | frames across all circles |
| `display_unit_count` | int | units that contributed |
| `display_unit_ids` | string | comma-joined `wzrk_id`s |
| `campaign_ids` | string | comma-joined campaign ids |
| `render_latency_ms` | int | screen entry → tray paintable |

`render_latency_ms` is the one that tells you whether Native Display is fast enough to sit above
the fold. Watch its average before promising this pattern on a cold start.

### `Story Circle Opened` — funnel step 2

| Property | Type | Notes |
|---|---|---|
| `open_source` | string | `tray_tap`, `auto_advance`, `swipe` |
| `circle_seq_in_session` | int | 1 = first circle opened this session, 2 = next… |
| `was_seen_before` | bool | every frame already watched |
| `circle_id`, `circle_name`, `circle_position` | string/int | which circle |
| `circle_story_count`, `tray_size` | int | |
| `campaign_id`, `display_unit_id`, `payload_source` | string | attribution |

`circle_seq_in_session` answers "how many people got past the first circle" without a second
event. `open_source` separates a deliberate tap from the auto-advance roll-on.

### `Story Viewed` — funnel step 3

One frame finished being on screen. Raised on **exit**, not entry, so it can carry dwell and how
the user left — which is what makes one event serve both reach and drop-off analysis.

| Property | Type | Notes |
|---|---|---|
| `story_id`, `story_position` | string/int | frame identity, 1-based |
| `story_duration_secs` | int | as authored |
| `dwell_ms` | int | actual time on screen |
| `completed` | bool | ran its full duration |
| `exit_reason` | string | `auto_complete`, `tap_forward`, `tap_back`, `swipe_next_circle`, `swipe_prev_circle`, `closed`, `backgrounded` |
| `liked` | bool | like state at exit |
| `is_last_in_circle` | bool | |
| `story_seq_in_session` | int | depth across the whole viewing session |
| + all the circle/attribution properties above | | |

`completed = true` vs `exit_reason = tap_forward` is the difference between "saw it" and "skipped
it" — the single most useful cut in the whole model, and the reason dwell is worth capturing.

### `Story Interacted` — funnel step 4

Every in-story interaction, discriminated by `action`.

| Property | Type | Notes |
|---|---|---|
| `action` | string | `like`, `like_removed`, `share`, `link_click` |
| `like_state_after` | bool | on like actions |
| `net_likes_after` | int | device's net like count |
| `share_channel` | string | on share |
| `deeplink` | string | on `link_click` |
| `time_into_story_ms` | int | how far into the frame they acted |
| `story_id`, `story_position` + circle/attribution properties | | |

### Profile properties

| Property | Mechanism | Purpose |
|---|---|---|
| `story_likes_net` | `incrementValue` / `decrementValue` | net likes per user — **goes down** on unlike |
| `liked_story_ids` | `addMultiValueForKey` / `removeMultiValueForKey` | the set of stories a user likes *right now* |
| `stories_viewed_total` | `incrementValue` | engagement depth, for segmentation |

### Native Display's own counters

`pushDisplayUnitViewedEventForID` / `pushDisplayUnitClickedEventForID` → Impressions, Clicks, CTR
on the campaign report. Not raised by the four events above; the app raises them separately.

---

## 5. Custom board recipe

**Boards → + Board** (or *Create Board* on Boards 2.0) → name it → **Create**. Then either build the
analysis in Trends / Funnels / Pivots and use **Pin / Add to Board**, or open the board and use
**Add Tile**, configure the analysis, **View Analysis** → **Save**.

Pinnable tile types: Trends, Funnels, Pivots, Cohorts, Flows, Segments, Notes. Grouping an event by
a custom event property works in Trends and Pivots, which is what the whole taxonomy below relies
on. There is **no computed-metric tile** — no arithmetic between two event counts in one widget —
which is why net likes is handled the way §6 describes.

Tiles, in the order that tells the story to a client:

1. **Funnel** — `Story Tray Rendered` → `Story Circle Opened` → `Story Viewed` →
   `Story Interacted`. Top-line conversion of the whole feature.
2. **Circle popularity** — `Story Circle Opened`, unique users, grouped by `circle_name`.
   Which circle earns the first slot.
3. **Tap-through by position** — `Story Circle Opened`, grouped by `circle_seq_in_session`.
   How deep across circles people go; the drop from 1 → 2 is the number to watch.
4. **Frame drop-off** — `Story Viewed`, unique users, grouped by `story_position`, filtered to one
   `circle_id`. The classic story completion curve.
5. **Completion vs skip** — `Story Viewed` grouped by `exit_reason`, or filtered to
   `completed = true` against total. Segment by `story_duration_secs` and you have a direct read on
   whether 5s or 7s frames hold attention.
6. **Auto-advance value** — `Story Circle Opened` grouped by `open_source`. How much of the reach on
   later circles the roll-on is creating rather than deliberate taps.
7. **Interaction mix** — `Story Interacted` grouped by `action`. Likes vs removals vs shares vs CTA.
8. **Likes by story** — `Story Interacted` filtered `action = like`, grouped by `story_id`.
9. **Net likers** — a segment on `liked_story_ids` containing a story id; see §6.
10. **Delivery health** — `Story Tray Rendered` grouped by `payload_source` (should be ~100%
    `native_display`) and average `render_latency_ms`.

---

## 6. The like/unlike decrement question

The client's ask: tapping the heart records a like; tapping again removes it and the **count should
go down**.

**What genuinely cannot be done.** Events are append-only. Once `Story Interacted / action = like`
is recorded, nothing takes it back — no API, no dashboard action. So any board tile that counts
that event is monotonic, by design. There is also no formula/computed tile on a custom board, so
"likes minus removals" is not expressible as a single number there.

**What is done instead, and it does satisfy the underlying requirement.** The user's *current* like
state lives on the profile, where arithmetic is allowed:

- `story_likes_net` — `incrementValue(+1)` on like, `decrementValue(-1)` on removal. A per-user net
  count that falls when a like is taken back.
- `liked_story_ids` — `addMultiValueForKey` on like, `removeMultiValueForKey` on removal. The exact
  set of stories the user likes right now.

That last one is the real answer to "the count should go down": a segment of *profiles where
`liked_story_ids` contains `sale_1`* is a live count of current likers for that story, and it
**shrinks** when someone unlikes. Same for a segment on `story_likes_net >= 3`.

So the honest framing for the client is:

| Question | Answer |
|---|---|
| "How many likes were ever given?" | Event count. Monotonic — and that is correct, it's an audit trail. |
| "How many users like this story *right now*?" | Segment on `liked_story_ids`. Goes down on unlike. |
| "How engaged is this user?" | `story_likes_net` on the profile. Goes down on unlike. |
| "Show net likes as one board tile" | Not directly. Put `action = like` and `action = like_removed` side by side, or use the profile-based segment count. |

Two caveats to state up front rather than discover later: a CleverTap multi-value property holds a
bounded number of values (on the order of 100), so `liked_story_ids` suits a demo and a
sensibly-sized catalogue, not an unbounded like history — a real deployment would keep the like
ledger in the client's own backend and mirror only aggregates to CleverTap. And an exact
*global* sum of `story_likes_net` across all profiles is not a standard board tile; segment counts
and distributions are, and the Profile Counts API can produce exact numbers if needed.

---

## 7. Honest limits of Native Display here

Worth saying out loud to the client, because each one is a place this pattern will bite:

1. **Custom KV is flat `String → String`.** Nesting has to be encoded — hence the JSON-in-a-string
   schema, and hence a parser in the app whose only job is to survive marketer typos.
2. **Delivery is event-triggered and asynchronous.** Units arrive whenever the SDK's next response
   carries them, not on the line after the trigger event. First-ever launch can therefore paint an
   empty tray; the app leans on the SDK's on-device cache for every subsequent launch, plus a
   bundled fallback for the demo.
3. **Campaign qualification rules apply to content.** A story tray is app *content*, but it is
   being delivered as a *message*, so it inherits campaign start/end dates, audience qualification
   and the one-campaign-per-trigger constraint. Content that fails to qualify is a blank home
   screen, not a missed push.
4. **No app-side defaults.** Nothing ships with the app, so "no campaign" means "no tray" unless you
   build a fallback yourself (this demo does; note it is a demo device, not a platform feature).
5. **A/B testing the tray is manual.** Two campaigns, split audiences, compare boards by hand. No
   randomised assignment, no significance testing, no automatic winner rollout.
6. **Reporting is mixed.** Tray impressions land in messaging metrics alongside push and in-app,
   so campaign-level reporting starts to conflate "content shown" with "message delivered".
7. **No kill switch.** Turning stories off means stopping a campaign (and the app then falls to
   whatever it does with an empty tray), not flipping a flag.

None of these blocks the demo. All of them are the argument in the next section.

---

## 8. What Product Experiences would do better

Native Display is a messaging feature being used as a content-configuration system. That works —
this repo is the proof — but every limitation in §7 traces back to that mismatch. **Product
Experiences (Remote Config / variables + experiments)** is the feature actually shaped like this
problem. The pitch, mapped to what the client will feel:

| Their pain with Native Display | With Product Experiences |
|---|---|
| Tray encoded as JSON-in-a-string; `c1_s1_img` key soup | **Typed, structured variables.** Define a tray variable with nested defaults in code; the marketer edits a structured value. `TrayPayloadParser` and its whole failure class disappear. |
| No app-side defaults; "no campaign" = blank tray | **Defaults ship in the app.** Variables have code-side defaults, so the tray always renders — offline, first launch, campaign expired. The fallback asset hack in this repo is not needed. |
| Blank tray on first launch, event-triggered delivery | **Config fetch at startup** with a completion callback, cached across launches. Semantically "fetch my config", not "qualify for a message". |
| A/B testing the tray is two campaigns and a spreadsheet | **Experiments, properly.** Randomised variant assignment, primary and guardrail metrics, statistical significance, automatic winner rollout. This is the big one — see below. |
| One campaign per trigger event | No coupling to events at all. One versioned config payload. |
| No kill switch | **Feature flag.** Stories off in seconds, no release, no campaign surgery. |
| Content metrics mixed into messaging reports | Config/experiment reporting kept separate from campaign reporting. |
| Variant analysis needs hand-added event properties | Variant membership is attached automatically, so the existing four events split by variant with no code change. |

**Why the experimentation point is the one that sells.** Every real question about a story tray is
an experiment: 5-second frames or 7? Which circle earns slot one? Does auto-advance into the next
circle lift total frames watched, or does it annoy people into closing? Does showing a like button
at all change completion? With Native Display, each of those is "build two campaigns, split the
audience, eyeball two boards, argue about whether the difference is real." With Product
Experiences, it is one experiment with a primary metric — and the analytics model in this repo is
already built for it: `Story Viewed` with `completed` and `dwell_ms` is a ready-made primary metric,
`exit_reason = closed` is a ready-made guardrail.

That is the honest edge to pitch: **Native Display can deliver the tray; Product Experiences is
what lets you find out which tray is the right one, and turn it off when it isn't.** If the client
only wants a marketer to swap creatives, Native Display is genuinely sufficient and cheaper. If
they intend to *tune* the feature — and a stories tray is exactly the kind of surface teams tune
forever — the manual-A/B tax will exceed the licence cost quickly.

Two things to be straight about in the pitch: Product Experiences is a paid add-on rather than part
of the messaging suite, and variable fetching still needs the same care about async timing (cached
values render first, fresh values apply on the next entry). Also, Native Display keeps one real
advantage worth conceding: it is a campaign, so it can be scheduled, journey-orchestrated and
per-user personalised with the messaging tooling the marketer already knows, and its impressions
and clicks are counted for free. A mature answer is often both — Product Experiences for the
feature's structure and behaviour, Native Display or in-app for the time-boxed promotional circle.

> **Provenance of the claims in this doc.** The SDK behaviour (API signatures, custom KV arriving as
> a flat string map, display unit id format) was read from the CleverTap Android SDK source at
> v8.4.1. The dashboard paths, the Native Display template list, the one-campaign-per-trigger-event
> rule on mobile, the pinnable board tile types and the absence of a computed-metric tile were
> confirmed through CleverTap's own documentation Q&A. The Product Experiences capability details in
> §8 are from working knowledge only — the sandbox this was built in could not reach
> `developer.clevertap.com` or `docs.clevertap.com` directly, so confirm exact variable types and
> experiment metric options against current docs before putting numbers in front of the client.
