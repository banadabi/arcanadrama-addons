# (Arcana Drama) DramaTR

Original Cloudstream MainAPI provider for https://dramatr.com, authored by ArcanaDrama.
Catalogue and search use the publisher's `/api/catalog` and `/api/search`. Details retain
the matching TVSeries metadata and actual ascending `sd-ecard` chapter links. A source's
upload date is not presented as its production year. Editions retain their distinct URLs.

Only stable chapter URLs enter `Episode.data`; every `loadLinks` calls the normal
`/api/play` with the real chapter referer and verifies series, ordinal and episode ID.
The chosen source's `url`/`src`, type and source subtitles are used. `raw_url`, ads,
watch-party, analytics and unrelated account actions are not called. Source cookies
stay in the app's normal WebView store; no account Cookie/Authorization is attached
to media. Login is optional through the plugin settings in Cloudstream and Arcana76+;
stock Play1.2.7 supports guest playback but does not expose that settings action.

The source declares its own audio/edition labels. UI locale alone does not select
an audio track or invent subtitles; no automatic translation is performed. Native
streams are required; a source returning only an HTML embed is not called playable.
Access limits and account requirements remain enforced by the publisher.

Build from the repository root using the pinned wrapper/SDK and `tools/build.py
--module ArcanaDramaTR --hydrate <builds-checkout> --build --catalogue`. GPL-3.0
applies; the included Jsoup dependency has its own license in `libs/`.
