# AnimeWorld — CloudStream 3 Extension (remake)

Italian anime provider for [CloudStream 3](https://github.com/recloudstream/cloudstream),
re-scraping **animeworld.ac** and enriching every title with a **transparent PNG title logo**
sourced from TMDB (via ARM + AniList id mapping).

This is a best-practice rewrite of the original `doGior/hadEnough` plugin, porting the
`anilist_title_logo.js` flow (AniList → ARM → TMDB public HTML → `image.tmdb.org` PNG) into
native Kotlin and wiring the resulting logo URL into the CloudStream `LoadResponse.logoUrl`
field so the title logo renders on the detail page.

---

## Key features

| Feature | Implementation |
| --- | --- |
| Transparent PNG title logo | `TmdbLogoProvider` — AniList id → ARM API → TMDB `/images/logos` HTML scrape → CDN URL |
| Logo caching (24h TTL) | `ConcurrentHashMap` keyed by AniList id; negative results cached too |
| Sync IDs | `addAniListId`, `addMalId`, `addTMDbId` all populated on `LoadResponse` |
| Dub/Sub split | Open-class subclassing (`AnimeWorldDub`, `AnimeWorldSub`) — §8.12 pattern |
| Settings UI | Bottom-sheet with TV-friendly focus outline + restart prompt |
| Vidguard extractor | Rhino-eval of the obfuscated `svg` global + sigDecode of the m3u8 URL |
| No API keys | 100% public endpoints — ARM, TMDB website, TMDB CDN |

---

## File layout

```
AnimeWorld/                                    # repo root
├── .github/workflows/build.yml                # CI: build .cs3 + plugins.json, push to builds branch
├── settings.gradle.kts                        # auto-includes subdirs (skips status=0)
├── build.gradle.kts                           # root build script
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
├── repo.json                                  # repository metadata for the CS3 app
└── AnimeWorld/                                # the actual provider module
    ├── build.gradle.kts                       # version=21, requiresResources=true
    └── src/main/
        ├── AndroidManifest.xml                # package="it.dogior.hadEnough"
        ├── kotlin/it/dogior/hadEnough/
        │   ├── AnimeWorldPlugin.kt            # @CloudstreamPlugin entry point
        │   ├── AnimeWorldCore.kt              # MainAPI — scraping + logo wiring
        │   ├── AnimeWorldSub.kt               # Japanese-audio variant
        │   ├── AnimeWorldDub.kt               # Italian-dubbed variant
        │   ├── AnimeWorldDTOs.kt              # Jackson DTOs (ARM, AW search, AW episode info)
        │   ├── TmdbLogoProvider.kt            # AniList → ARM → TMDB → PNG logo URL
        │   ├── VidguardExtractor.kt           # ExtractorApi for listeamed.net
        │   └── Settings.kt                    # Bottom-sheet settings UI
        └── res/
            ├── drawable/{outline,save_icon}.xml
            ├── layout/settings.xml
            └── values/strings.xml
```

---

## How the logo lookup works

`TmdbLogoProvider.fetchLogo(anilistId)` executes this pipeline:

1. **Cache check** — if we've already resolved this AniList id within the last 24h, return the
   cached result (including negative results — saves ARM/TMDB from being hammered for obscure
   anime that have no TMDB entry).
2. **AniList → TMDB** — `GET https://arm.haglund.dev/api/v2/ids?source=anilist&id={ID}` returns
   a JSON object with `themoviedb` field. ARM returns `null` (HTTP 200 + body `"null"`) when
   the AniList id isn't in their DB.
3. **TMDB → logo URLs** — `GET https://www.themoviedb.org/tv/{tmdb_id}/images/logos?image_language={lang}`
   is the public HTML page (no auth). We regex-scape the HTML for every
   `image.tmdb.org/t/p/original/<hash>.png` URL.
4. **Language fallback** — try the requested language (default `en`); if no logos, fall back
   through `["ja", null]` (Japanese, then unfiltered).
5. **Result** — first available PNG URL is stored on `LoadResponse.logoUrl` and rendered by
   CloudStream as the title logo overlay.

Every step is wrapped in try/catch — a logo lookup failure is logged but never propagates.
The provider still returns a fully usable `LoadResponse`, just without `logoUrl`.

---

## Best-practice compliance

Mapped against §9 "Anti-patterns & Pitfalls to Avoid" of the CloudStream guide:

| Anti-pattern | Status |
| --- | --- |
| §9.1 Hardcoding API keys | ✅ None — all endpoints are public |
| §9.2 Bumping Jackson past 2.13.1 | ✅ Capped at 2.13.1 |
| §9.3 `GlobalScope.async` leaking coroutines | ✅ Uses `amap` instead |
| §9.4 `runBlocking` inside a suspend function | ✅ None |
| §9.5 URL-hack to pass non-URL data | ✅ Uses `newEpisode(data) { ... }` |
| §9.6 Direct constructor construction with `var mainUrl` | ✅ `mainUrl` is a plain `override var` |
| §9.7 Reading plugin companion at construction time | ✅ Companion only holds static URL/cookies |
| §9.8 `requiresResources = true` without subclassing `Plugin` | ✅ We extend `Plugin` (Android) |
| §9.9 Inconsistent `lang` codes | ✅ `lang = "it"` (Dub/Core), `lang = "ja"` (Sub — was `jp`) |
| §9.10 `enableAdult` read but never settable | N/A |
| §9.11 `tvTypes = listOf("Other")` (singular) | ✅ Uses `"AnimeMovie", "Anime", "OVA"` |
| §9.12 Multiple `cloudstream(...)` deps | ✅ Single dependency |
| §9.13 Version not a bare integer | ✅ `version = 21` |

---

## Build

Requirements: JDK 17, Android SDK 35.

```bash
# Generate the gradle wrapper (only needed once)
gradle wrapper --gradle-version 8.12

# Build just this provider
./gradlew :AnimeWorld:make
# → AnimeWorld/build/AnimeWorld.cs3

# Build all providers + plugins.json
./gradlew make makePluginsJson
```

For local install via ADB:

```bash
./gradlew :AnimeWorld:deployWithAdb
```

Or copy `AnimeWorld/build/AnimeWorld.cs3` to your device's
`/storage/emulated/0/Cloudstream3/plugins/` directory.

---

## Install via repository

In CloudStream 3: **Settings → Extensions → Repositories → Add repository**,
then paste:

```
https://raw.githubusercontent.com/doGior/doGiorsHadEnough/main/repo.json
```

The first CI build populates the `builds` branch with `plugins.json` and every `.cs3` artifact.

---

## Endpoints used

| Endpoint | Auth | Purpose |
| --- | --- | --- |
| `https://arm.haglund.dev/api/v2/ids?source=anilist&id={ID}` | None | AniList → TMDB id mapping |
| `https://www.themoviedb.org/tv/{tmdb_id}/images/logos?image_language={lang}` | None | Logo URL scrape (HTML page) |
| `https://image.tmdb.org/t/p/original/{hash}.png` | None | CDN URL for transparent PNG logo |
| `https://www.animeworld.ac/...` | Cookie | Provider source site |

---

## License

Same as the upstream `doGior/doGiorsHadEnough` repo. All third-party endpoints are public and
require no API key.
