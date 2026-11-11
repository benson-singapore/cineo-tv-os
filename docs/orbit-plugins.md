# Orbit plugins on Android TV

The Orbit entry is available in the TV navigation menu. It uses the existing Hub device binding and calls only the Hub device API:

- `GET /api/v1/device/orbit-plugins`
- `GET /api/v1/device/orbit-plugins/{configId}/capabilities`
- `POST /api/v1/device/orbit-plugins/{configId}/invoke`

The Hub device token is sent in the Authorization header only to the configured Hub URL. Orbit URLs, plugin IDs, API key IDs, and admin credentials are never sent to Orbit by the TV. A configuration's `channel_id` is authoritative: the TV does not override it. To search using a channel that declares search, configure that channel as a separate Hub Orbit entry.

## Current schema adapter

The adapter is concentrated in `OrbitPluginRepository.kt`. It consumes the Hub envelope and preserves the additional `data.data` business-result layer. It reads the configured channel's executable operations, required/optional parameters, defaults, target declarations, scope, pagination declaration, and feature metadata. The current sanitized real sample supports these mappings:

| Orbit response | TV mapping |
| --- | --- |
| feed item `id` or `itemKey`, `title`, `summary`/`content`, `image`/`cover` | `RemoteVideo` fields |
| item `tags` | On-screen local category filter |
| `data.next` object | Stored JSON cursor, returned unchanged on the next operation |
| chapters item `id` or `itemKey`, `title` | D-pad selectable episodes |
| chapter detail item `url`, `title` | Existing Media3 `PlaybackEpisode` |

The observed `duanju` channel declares `feed`, `page`, `chapters`, and `chapterDetail`; its `search` operation is not declared, so the TV does not show a search box for that config. The separate `search` channel declares `search` with a required `query`, so a Hub entry bound to that channel gets the search input. The TV never borrows a plugin-global operation to call a channel that did not declare it.

Optional and required string parameters are surfaced as text filters, excluding the `query` and declared pagination parameter. Tag values are also offered as category filters when returned in list items. Switching config, query, or applied filters clears list pagination. Chapters keep their own page context and only paginate when the bound channel declares a `page` operation scoped to `chapters`.

## Playback and supported limits

The sampled playback result contains a URL but no media request headers or DRM declaration. The TV accepts only hierarchical HTTPS media URLs without embedded user info and retains signed query strings. It passes the URI to the existing Media3 player, which already detects HLS from the `.m3u8` path. Hub authorization is not copied to the media request. HTTP, file, data, malformed, DRM-only, or missing media addresses are reported as unsupported. Additional media headers and DRM are not implemented without a verified protocol declaration.

Unknown item layouts, missing `items`, empty/null business data, malformed Hub envelopes, revoked credentials, network failures, and unsupported channel capabilities produce an explicit error state rather than a fabricated empty success. Retry is available from the page. No UI/simulator or real playback verification was performed.

## User review steps

1. Bind the TV to a Cineo Hub account and enable an Orbit configuration in Hub.
2. Open **Orbit** from the TV navigation menu; use **刷新** to retry plugin discovery.
3. Select a configured plugin/channel entry and confirm the channel label and list appear.
4. For a `search` channel, search a title; for list items with `tags`, switch between **全部** and a tag.
5. Use **加载更多**, open a work, select an episode, and verify the Media3 player starts.
6. Also inspect an unbound, revoked, offline, empty, and non-search-channel state where practical.

