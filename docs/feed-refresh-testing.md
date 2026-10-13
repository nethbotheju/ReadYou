# Feed refresh checks

## Build and automated tests

```sh
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew :app:testGithubDebugUnitTest :app:testGithubReleaseUnitTest \
    :app:assembleGithubDebug :app:assembleGithubRelease :app:assembleGithubDebugAndroidTest
```

With an emulator running, test the Room migration and icon updates:

```sh
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew :app:connectedGithubDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=me.ash.reader.infrastructure.db.FeedIconMigrationTest,me.ash.reader.infrastructure.db.FeedIconDaoTest
```

## Emulator checklist

The PR workflow produces an optimized release APK, which is smaller than a debug APK. Test that release APK as well as the debug build. Force-stop and reopen the app several times with an existing account, and check the All, Unread, and Starred filters: startup collectors must not encounter uninitialized flows or leave the feed list stuck loading.

1. Install the debug APK as an update (do not uninstall or clear storage). Existing subscriptions, saved icons, articles, and read/starred states should survive the version 7 → 8 database migration.
2. Open a local account with approximately 15 feeds. Previously saved articles should be readable immediately.
3. Refresh. New articles should appear as each feed finishes. The status should count completed feed attempts; an unavailable feed must not discard successful feeds' articles.
4. Scroll down while refreshing. The list must not repeatedly jump to the top.
5. Include a slow/unreachable feed. Its network request has a 20-second total deadline; each feed's refresh has a 30-second coroutine deadline. The queue is limited to 16 concurrent feeds, so larger subscriptions can require multiple batches.
6. Check a feed without an icon. Article refresh must finish independently of icon lookup. An icon may appear afterward, without restarting the refresh spinner.
7. Refresh again and restart the app. Existing icons should not be rediscovered. Unsuccessful missing-icon lookups have a persisted one-day cooldown; the existing manual reload-icon action bypasses it.
8. If a feed fails, a brief failure count should appear after refresh. The app must not automatically repeat the entire local refresh in a retry loop. Try again manually or wait for the next scheduled refresh.

## Behavior and limits

- Icon work is a separate, unique WorkManager job per account, with two concurrent lookups, a ten-second lookup deadline, and a five-second deadline per HTTP call. It respects the account's Wi-Fi/charging restrictions.
- Icon URLs remain in Room; image bytes use the existing Coil memory/disk cache. Cached images can be downloaded again after cache eviction, but this is not part of article synchronization.
- Archive cleanup and full-content prefetch are post-sync work and do not keep the article refresh spinner active.
- Per-feed progress/deadlines and failure isolation apply to local RSS refresh. Remote providers retain their API synchronization strategy, but site-icon discovery has been removed from that critical path.
- Debug log tags: `FeedRefresh` (download/parsing), `LocalRssService` (save/failure/summary), and `FeedIcon` (icon lookup timing).
