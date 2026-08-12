# News — RSS on the Rokid glasses HUD

A [Rokid Nexus](https://github.com/Anezium/Rokid-Nexus) plugin that reads RSS and
Atom feeds on Rokid AR glasses. Subscribe to feeds on the phone, then browse
headlines and read articles on the HUD, entirely with the R08 ring.

News is a **headless phone APK**: it has no launcher icon and no UI of its own on
the glasses. The Nexus hub discovers it, the wearer approves it once, and from
then on it is launched from the glasses launcher and rendered by the hub from the
typed surfaces this plugin declares.

| | |
|---|---|
| Plugin id | `news` |
| Package | `com.beyondlevi.nexus.news` |
| API version | 3 |
| Capabilities | `surfaces` (plus Android `INTERNET`, phone-side) |
| SDK | `com.github.Anezium.Rokid-Nexus:bus-client:sdk-v0.13.0` |
| minSdk / targetSdk | 30 / 36 |

## On the glasses

Three views, all driven by the four ring verbs — forward/back to move, tap to
select, double tap for Back:

```
Sources ──tap──▶ Headlines ──tap──▶ Reader
   ▲                 │                 │
   └────── back ─────┴──── back ───────┘
   back at Sources = leave the plugin
```

- **Sources** — “All feeds”, one row per subscription with its article and unread
  counts (or the reason its last fetch failed), and a Refresh row.
- **Headlines** — newest first. The headline is split across both text bands the
  HUD draws for a list row (the renderer caps the title at one line and a plugin
  cannot make it wrap), with age and source as trailing tokens. Read items are
  dimmed, not hidden.
- **Reader** — the article text paged for the HUD. Forward/back walks the pages, a
  tap also advances, Back returns to the headlines.

Everything is one ordered list per view: selection wraps in both directions, the
focused row carries the HUD's selection rail, and Back never dead-ends. The
navigability is asserted on the JVM in
[`NewsStateTest`](app/src/test/java/com/beyondlevi/nexus/news/NewsStateTest.kt).

## On the phone

Nexus → Plugins → News opens the settings screen, built only from the Nexus
design kit:

- Add a feed by URL (typed loosely: `example.com/rss` is accepted), or pick one
  of the verified presets (BBC World, NYT World, Hacker News, Ars Technica, The
  Verge, G1, Tecnoblog).
- Feeds are named automatically from the channel title on the first fetch.
- Options: how many articles each feed contributes, and how stale the cache may
  be before opening the plugin re-fetches.
- Remove a feed, or uninstall the plugin, from the same screen.

## How it works

```
NewsPluginService   adapter: lifecycle + the four ring keycodes, nothing else
  └─ NewsRuntime    session: load, fetch, merge, render the single surface
       ├─ NewsState pure navigation/selection model (no Android, no SDK types)
       ├─ NewsSurfaces  state ──▶ NexusCard (rich rows, windowed and capped)
       ├─ RssParser  SAX parser for RSS 2.0 / Atom / RSS 1.0-RDF
       ├─ HtmlText + ArticlePager  markup ──▶ paragraphs ──▶ HUD pages
       ├─ NewsHttpClient  bounded HttpURLConnection fetch
       └─ FeedStore  subscriptions, options, read set, article cache
```

Platform constraints the code holds itself to, because they fail hard rather than
degrade:

- **Dormant unless open.** Nothing runs outside `PLUGIN_OPEN`…`PLUGIN_CLOSE`: no
  boot receiver, no background polling, no notifications of its own. The fetch
  scope is cancelled on close.
- **~3 KiB per surface.** A larger surface is silently not delivered when the SPP
  data plane is down, so rows are windowed to 12 per surface, strings are capped,
  and article text is paged. A test asserts the budget against 200 long articles.
- **Card limits.** Rows ≤ 64, line ≤ 240 chars, and a hashed `contentKey` ≤ 128 —
  a `contentKey` built by concatenating content throws inside the plugin process.
- **Prose rows clip at three wrapped lines** and the tail is ellipsised away, not
  reflowed, so reader rows are wrapped at 80 characters on word boundaries — the
  measured capacity of three lines on the RG-glasses HUD.
- **`SURFACE_BUSY` and friends are given up on quietly** — one plugin owns the
  HUD at a time and a retry loop would fight it.
- **Feeds are remote input.** Doctypes and external entities are refused (XXE and
  entity expansion), response bodies, redirects and timeouts are all bounded, and
  entity decoding is single-pass.

## Build

Needs JDK 17 and the Android SDK (platform + build-tools 36). The Gradle wrapper
is checked in.

```bash
./gradlew :app:testDebugUnitTest    # unit tests, including the ring navigability proof
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
```

Release builds are signed from the environment — set all three of
`NEXUS_RELEASE_KEYSTORE` (a PKCS12 file), `NEXUS_RELEASE_KEYSTORE_PASSWORD` and
`NEXUS_RELEASE_KEY_ALIAS`, then `./gradlew :app:assembleRelease`. Use the same
certificate forever: a plugin's identity, and the wearer's grant, is
`package + pluginId + signerSha256`.

## Install on a device

```bash
adb install -r app-debug.apk
# phone:   Nexus → Plugins → News → approve the surfaces capability (first time only)
# glasses: launcher → News
```

Installing grants nothing on its own, and changing the requested capability set
resets the grant to Pending. Switching signing keys (debug ↔ release) needs an
uninstall, reinstall and re-approval.

## Releases

`news-v1.0.2` is the first Store release: [news-phone-release.apk](https://github.com/beyondlevi/news-nexus/releases/tag/news-v1.0.2),
signed with the plugin's permanent certificate. A plugin's
identity is `package + pluginId + signerSha256`, so that certificate never
changes; installing the Store build over a locally sideloaded debug build
requires uninstalling the debug one first.

Verify any release APK before installing it — the full signer fingerprint is:

```
07d94dca37d6327e2e55783f5b6bfaad213fd49841ae306e8b5ed26168aedf17
```

```bash
apksigner verify --print-certs news-phone-release.apk   # Signer #1 certificate SHA-256 digest
```

It must match the `signerSha256` pinned in the registry descriptor; the phone
hub checks the same value before it installs.

## Roadmap

- Read aloud on the glasses via the hub `tts` capability (a new capability means
  re-approval, so it is a deliberate later step).
- Article images on the HUD image surface, for feeds that carry `media:thumbnail`.
- OPML import/export in the settings screen.
In the in-app Nexus Store. Releases live under the namespaced tag `news-vX.Y.Z`;
each one is submitted as a registry descriptor update:
[#60](https://github.com/Anezium/RokidBrew-Registry/pull/60) added 1.0.2 (merged),
[#73](https://github.com/Anezium/RokidBrew-Registry/pull/73) submits the current
release.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
