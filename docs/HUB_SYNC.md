# Android TV Hub sync

This document records the TV client integration and the checks used for acceptance criteria AC-96–AC-114. The app keeps local playback, favorites, and site changes available while Hub is offline. Device credentials are encrypted with Android Keystore; the sync policy and transport are in `app/src/main/java/com/nova/tv/HubSyncPolicy.kt`, `HubSyncApi.kt`, and `HubSyncEngine.kt`.

## Implementation map

| Area | Implementation |
|---|---|
| PIN binding and device credential | `VideoScreens.kt` cloud sync settings; `HubSyncEngine.bind`; encrypted `DeviceCredentialStore` in `HubSyncApi.kt` |
| Durable outbox, migration, local history cap | `CineoDatabase.kt`; mutation capture and remote application in `MacCmsRepository.kt` |
| Conflict and resync policy | `HubSyncPolicy.kt`; pulls/pushes/rebuild paging in `HubSyncEngine.kt` |
| Rebuild paging | Sends `known_generation`, `cursor` (initially 0), and `limit` (500); while `has_more` is true, continues with `next_seq` and the same generation. Rebuild pages are applied as snapshot data, and the baseline is stored when the first page arrives. |
| Cloud settings | `HubSyncEngine.applyCloudSettings`; read-only settings presentation in `VideoScreens.kt`; TMDB request preferences in `TmdbMetadataRepository.kt` |
| Sync triggers | Startup pull in `MainActivity.onCreate`; manual pull/push/full push in `VideoScreens.kt`; progress, pause/exit, debounced favorite and push-only retry in `HubSyncEngine.kt` |
| Request trigger header | Push, pull, rebuild, and cloud-settings requests include `X-Sync-Trigger` from `SyncTrigger`; cloud settings are applied after pull |
| Full push | Snapshot local site/history/favorites/progress records in batches capped at 500 envelopes and 1 MiB; settings are never uploaded |

## Trigger matrix

| Event | Action | `X-Sync-Trigger` |
|---|---|---|
| Cold application start | Pull pages, apply cloud TMDB key/preferences; rebuild on cursor expiry | `launch_pull` |
| Returning from background | No automatic pull or push | — |
| Playback interval elapsed and current progress changed | Push one current progress envelope | `auto_progress` |
| Playback pause or player exit | Push pending progress immediately | `auto_progress` |
| Favorite toggle | Debounce 2 seconds, then push pending favorite state/tombstone | `auto_favorite` |
| Failed push retry | Push outbox only, honoring exponential backoff and `Retry-After` | `retry` |
| Settings: Push all | Enqueue local site/history/favorites/progress and send bounded batches | `full_push` |
| Settings: Pull now | Pull pages and apply cloud settings; rebuild on cursor expiry | `manual_pull` |
| Settings: Push and pull | Snapshot local site/history/favorites/progress, then pull only after all snapshot batches succeed | `full_push`, then `manual_pull` |

The response envelope is successful only when `code == 200`. A `40900` response starts a rebuild; after all snapshot pages complete, the merged local snapshot is enqueued under the new generation/baseline, while stale outbox items are isolated from upload and can be explicitly discarded in settings. PIN errors `40001`, `42900`, and `42901` have distinct user messages; `42901` displays the remaining time rounded up from `retry_after_seconds`.

Removing a binding clears credentials and sync state on this TV only. To invalidate the server credential, revoke the device in the Hub management page.

## Acceptance coverage

| AC | Implementation or verification procedure |
|---|---|
| 96 | PIN field filters input to digits, accepts 8 digits, and keeps bind disabled until complete. On TV, use only the D-pad to enter 7 digits and verify bind is disabled; enter the eighth and verify it becomes available. |
| 97 | Bind success persists the credential, enqueues the local snapshot, and starts sync. On a connected test Hub, bind and verify the success state and first sync. |
| 98 | `40001` is surfaced as an invalid or expired PIN; retry with a newly generated PIN. |
| 99 | `42901` uses `retry_after_seconds` and rounds up to minutes. Exercise with a test response and verify the displayed wait. |
| 100 | Bind does not clear existing history or favorites; local rows remain while their current state is queued for upload. Compare local counts before and after binding. |
| 101 | After first sync against a test Hub, compare remote history and favorite counts with the TV's non-deleted local records. |
| 102 | Settings expose unbound, syncing, last success with local time, last failure reason, offline, and revoked states. Exercise each state with a test Hub or controlled transport response. |
| 103 | The sync engine uses an atomic in-flight guard. Trigger repeated manual sync while one request is pending and verify only one push/pull sequence is active. |
| 104 | `MainActivity.onCreate` performs one startup pull; `onResume` has no sync trigger. Verify one launch pull and no additional pull after background/foreground return. |
| 105 | `syncProgressIfDue` uses the local 5/10/15/30/60-second setting (default 10) and pushes only changed progress; pause and player exit push the current progress item. Verify request body contains one progress envelope and no pull occurs. |
| 106 | `HubSyncPolicyTest.retryHonorsServerDelayAndCapsJitteredBackoff` covers the capped backoff. Repeated failures should show increasing delays with ±20% jitter, capped at 300 seconds. |
| 107 | The transport carries `Retry-After` into retry policy; the unit test checks that a 45-second server delay remains 45 seconds. Verify with a test server response. |
| 108 | Transport work runs off the UI thread and failures become an offline sync state. With Hub unreachable, verify the home, playback, and favorites remain usable. |
| 109 | Local mutations update immediately and persist to outbox. Restore Hub connectivity and verify queued favorites upload in the next sync. |
| 110 | A `40301` response marks the device revoked and displays “设备已解绑，请重新绑定”; local records are retained. Verify with a revoked test device. |
| 111 | Revoked credentials cancel scheduled retries and automatic sync exits while revoked. Verify no further requests occur until a new binding is saved. |
| 112 | Cloud-owned TMDB settings are read-only while bound and show the cloud management notice. Verify attempted local edits do not change the stored value. |
| 113 | Push filters out `setting` envelopes. Inspect a test push payload and confirm it contains no `entity=setting`. |
| 114 | `MacCmsRepository` retains the 50-row local history cap. Insert 51 distinct records and verify only the newest 50 remain. |

## Unit tests

Run the focused policy tests:

```sh
./gradlew testDebugUnitTest --tests com.nova.tv.HubSyncPolicyTest
```

The policy tests cover trigger names, supported progress interval fallback, changed-progress detection, 500-item/1 MiB batch bounds, outbox merge, conflict ordering, retry behavior, response-envelope success semantics, rebuild generation/baseline filtering, paging fields, `next_seq` pagination, and `40900` classification. Engine-level transport interactions and D-pad interaction still require device acceptance. Run the complete acceptance build checks with:

```sh
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

## Contract and integration limits

The backend contract update for rebuild pagination is pending; the client follows the PM decision to send `cursor` and `limit` and reuse the same `known_generation` across pages. Server-side device revocation and full TV-to-Hub integration require the backend deployment. The PIN lockout timing, Android TV D-pad flows, SQLite upgrades on existing installations, and the acceptance procedures listed above require execution against an emulator/device and test Hub.
