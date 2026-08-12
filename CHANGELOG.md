# Changelog

All notable changes to the News plugin are documented here. Versions follow
semver and match `versionName` in `app/build.gradle.kts`.

## 1.1.1

Fixes from the Store's automated source review of 1.1.0. Two of them could take
the plugin down or strand the reader, so they are worth stating plainly:

- **The control-only document is budgeted in UTF-8 bytes, not characters.** The
  ~3 KiB the control link carries is a byte limit; counting characters lied by a
  factor of three on CJK or emoji text, so a Chinese article would have been
  dropped by the hub and the wearer would have kept staring at the previous
  screen. The envelope and per-segment overhead are counted too.
- **The closing note is reserved before the prose is laid in.** It used to be
  appended on top of an exhausted budget, which pushed the document past the
  SDK's 40 000-character limit — and that limit throws inside the plugin's own
  process, so opening a very long article could kill the plugin instead of
  showing a truncated one.
- **Surface sends dedupe on what was rendered, not on the article's identity.**
  A feed that rewrites an open article keeps its id, so the old check suppressed
  the update and left the wearer reading the stale text. The `contentKey` stays
  stable, because the hub keys scroll position on it.
- A partially-fitting paragraph now contributes its opening lines instead of
  being dropped whole.

## 1.1.0

- The article body is now a native reader surface (`NexusReader`, SDK
  sdk-v0.15.0): the glasses renderer wraps the text and owns the scroll, so an
  article ships whole instead of being cut into 4-row pages of 80 characters.
  About 17 lines of continuous prose per screen instead of four clamped blocks.
- The ring's forward/back scroll the document by viewport - the hub consumes
  those keys for a reader surface and only forwards SELECT and BACK - and BACK
  still returns to the headline list.
- On a control-only link (SPP data plane down) the document is trimmed to a
  CXR-safe size with a note saying so, because a surface over ~3 KiB is dropped
  rather than delivered.
- The reader opens on the article's first paragraph and never tail-follows: the
  document-shaped `NexusReaderAnchor.TOP` (sdk-v0.15.0, glasses hub 1.4.3). A
  refresh that grows the text leaves the wearer exactly where they were reading.
- Requires glasses hub 1.4.3 or newer (versionCode 10403), which is where the
  reader anchor landed; reader surfaces themselves arrived in 1.4.1.

## 1.0.2

- Headlines now read across both text bands of a HUD list row instead of being
  ellipsised after ~28 characters. The glasses renderer caps a list-row title at
  one line and a plugin cannot make it wrap, so the headline is split on a word
  boundary between the title and the secondary line, and the age and source move
  to the row's trailing tokens. Roughly twice as much headline is legible.
- Row age is rendered against an injected clock instead of the system one, so a
  rendered surface is reproducible in tests.

## 1.0.1

- Reader rows wrap at 80 characters instead of 110. Device testing on the
  RG-glasses HUD showed a prose row holds ~29 characters per wrapped line and the
  renderer clips at three lines with an ellipsis — the tail is dropped, not
  reflowed — so the previous budget silently lost the end of every long paragraph
  mid-sentence.

## 1.0.0

First release.

- RSS 2.0, Atom and RSS 1.0/RDF feeds, parsed on the phone with a hardened SAX
  parser (no doctypes, no external entities, bounded text and entry counts).
- Three HUD views — sources, headlines, reader — fully operable with the R08 ring
  one-axis method: forward/back to move, tap to select, double tap for Back.
- Headline rows show age, source, unread state and per-feed failure reasons; read
  items are dimmed rather than hidden.
- Article text is reduced to paragraphs and paged for the HUD, wrapped at 110
  characters so prose rows are never clipped.
- Surfaces are windowed to 12 rows and capped so every send stays under the ~3 KiB
  transport budget that a CXR-only link imposes.
- Article cache and read state survive between sessions; a feed that fails to
  fetch keeps its cached entries instead of emptying the HUD.
- Phone settings on the Nexus design kit: add feeds by URL or from seven verified
  presets, automatic naming from the channel title, articles-per-feed and refresh
  staleness options, feed removal and the canonical uninstall row.
- Built against the Rokid Nexus SDK `sdk-v0.13.0`, API version 3, requesting only
  the `surfaces` capability.
