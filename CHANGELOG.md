# Changelog

All notable changes to the News plugin are documented here. Versions follow
semver and match `versionName` in `app/build.gradle.kts`.

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
