# (Arcana Drama) DramaCix

Original Cloudstream MainAPI provider, authored by ArcanaDrama. Actual source routes `/diziler` and `/ara` retain source cards, counts and paging. Matching TVSeries metadata and DH_CONFIG item records preserve real chapter order without filling gaps. Upload dates are not production years.

Only stable chapter URLs enter Episode.data. Each resolution fetches the exact chapter, validates slug/urlEp/embed/unique item and uses its fresh source-issued ticket with the normal `/api/video` flow. The real WebView renderer capability determines the publisher's g flag; no GPU/user-agent/fingerprint impersonation. Source access/challenge/entitlement denials stop. Account login is optional through the source's ordinary own-site settings page; no shared credentials are included.

First-party page/API fetches use a normal source WebView cookie store. Publisher media comes only from source-delivered HTTPS resources (including the source's transparent wrappers), with public DNS validation and no source Cookie/Authorization transfer. Media type is checked from actual bytes. Only source subtitle files with actual timing cues are returned; no automatic translation endpoint or fabricated Turkish text. All callbacks complete before loadLinks returns.

WebViews are task-owned and close after a short idle interval; unload/cancellation cleans owned work. The original Play1.2.7 host does not expose plugin account settings; current Cloudstream and Arcana76+ do. API renewal occurs freshly on episode resolution, not by claiming a retry of the same URL renews it mid-video.

Build from the repository root with pinned JDK17/Android37 and `tools/build.py --module ArcanaDramacix --hydrate <builds-checkout> --build --catalogue`. GPL-3.0; Jsoup license is included in libs. Runtime acceptance is tracked separately from source compilation.
