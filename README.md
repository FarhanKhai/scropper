# Scropper

**Scroll Stopper**: a zero-distraction Android client for YouTube and Instagram.

Scropper wraps the mobile websites (`m.youtube.com`, `instagram.com`) in a native shell and strips out the infinite-scroll feeds: YouTube Shorts, Instagram Reels and, optionally, the Instagram Explore grid. Everything else stays: search, subscriptions, videos, Stories, DMs, profiles and normal posts.

## Features

- **Shorts blocker**: hides the Shorts tab, Shorts shelves on Home and Search, and individual Shorts results. Blocks the Shorts feed API at the network level.
- **Shorts → normal video**: any `youtube.com/shorts/<id>` link (shared or tapped) opens as a regular `watch?v=<id>` player you can't swipe out of.
- **Reels blocker**: hides the Reels tab and Reels posts in the Instagram feed, and blocks the Reels/Clips APIs. Stories, DMs, the feed and profile tabs are explicitly allowed.
- **Explore grid blocker** (optional): empties the Explore recommendation grid while keeping the search bar usable.
- **Ad blocker**: Brave's open-source [`adblock-rust`](https://github.com/brave/adblock-rust) engine running uBlock Origin's filter lists and scriptlets, all on the device. Strips YouTube video ads from the player data before playback, hides promoted cards and Instagram sponsored posts, and blocks ad and tracker requests. Lists refresh daily.
- **Fullscreen like the app**: the fullscreen button rotates landscape videos to landscape, even with auto-rotate off.
- **Two persistent WebViews**: YouTube and Instagram stay alive in memory, so switching between them is instant and keeps scroll position and playback.
- **Edge handle UI**: no navigation bar. A thin pill on the right screen edge opens a floating switcher (YouTube / Instagram / Shield). Drag it to reposition.
- **Background audio** (optional): YouTube keeps playing when you switch apps or tabs.
- **Links stay inside**: YouTube and Instagram links never bounce out to the official apps. Scropper also registers as a handler for those links.

## How it works

```
┌────────────────────── Android (Kotlin, Jetpack Compose) ──────────────────────┐
│  MainActivity           edge handle, switcher panel, Shield settings sheet    │
│  ScropperWebView        2 persistent WebViews, request interception,          │
│                         script injection, pull-to-refresh, link routing       │
│  RustCore (JNI)  ──────────────────────────────┐                              │
└────────────────────────────────────────────────┼──────────────────────────────┘
                                                 ▼
┌──────────────────────── rust-core (libscropper_core.so) ──────────────────────┐
│  should_block_url        network-level blocker (empty 204 response)           │
│  rewrite_navigation_url  /shorts/<id> → /watch?v=<id>                         │
│  get_injection_script    CSS + rAF-throttled MutationObserver per site        │
│  ads                     adblock-rust engine: request filter + scriptlets     │
└───────────────────────────────────────────────────────────────────────────────┘
```

Three layers work together:

1. **Network**: `shouldInterceptRequest` asks Rust whether to block each request. Blocked requests get an empty `204`, so feed APIs return nothing.
2. **Navigation**: `shouldOverrideUrlLoading` rewrites Shorts URLs and keeps YouTube/Instagram links inside the app.
3. **DOM**: a CSS + JavaScript payload is injected at document start (and again on page start and finish). A `MutationObserver` batches DOM changes into one sweep per animation frame, so scrolling stays smooth. Hidden elements are rechecked, so recycled DOM nodes come back when they no longer contain Shorts or Reels.

## Project layout

```
rust-core/        Rust cdylib: blocker, rewriter, injection payload, JNI exports, unit tests
  src/lib.rs
  src/inject.js   JS template filled in by get_injection_script
android/          Gradle project, package com.scropper.app (minSdk 26, targetSdk 35)
  app/src/main/java/com/scropper/app/
    MainActivity.kt      UI: edge handle, switcher panel, settings sheet
    ScropperWebView.kt   WebView setup, clients, dual-WebView stack
    ShieldSettings.kt    SharedPreferences-backed toggles
    AdBlock.kt           loads, caches and updates the ad-block engine
    RustCore.kt          JNI declarations
  app/src/main/assets/adblock/   bundled filter lists + uBlock Origin scriptlets (resources.json)
scripts/
  gen_adblock_resources.py  builds resources.json from uBlock Origin's scriptlet sources
```

## Building

### Requirements

| Tool | Version used |
|---|---|
| JDK | 21 |
| Android SDK | platform `android-35`, build-tools `35.0.0` |
| Android NDK | 27.2.12479018 |
| Rust | stable, with target `aarch64-linux-android` |
| cargo-ndk | 4.x |

Gradle itself comes from the wrapper (`android/gradlew`, Gradle 8.11.1).

```bash
rustup target add aarch64-linux-android
cargo install cargo-ndk
```

### Environment

The original setup keeps every toolchain inside the project in a git-ignored `.toolchain/` folder and activates it with a git-ignored `env.sh`. To reproduce it, create `env.sh` in the repo root and point the paths at your own installs:

```bash
export JAVA_HOME="/path/to/jdk-21"
export ANDROID_HOME="/path/to/android-sdk"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.2.12479018"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$HOME/.cargo/bin:$PATH"
```

If those variables are already set globally, you can skip `env.sh` and drop the `source` prefix from the commands below.

### Test and build

```bash
# Rust unit tests
cd rust-core && source ../env.sh && cargo test

# Debug APK (builds the Rust .so for arm64-v8a via cargo-ndk, then the app)
cd android && source ../env.sh && ./gradlew assembleDebug
```

Output: `android/app/build/outputs/apk/debug/app-debug.apk`

The Gradle task `cargoNdkBuild` runs before every build and writes `libscropper_core.so` into `app/src/main/jniLibs/arm64-v8a/`. That folder is generated, so don't edit or commit it.

### Install

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

Only `arm64-v8a` is built, which covers nearly all current phones. Emulators on x86_64 hosts need an extra ABI added to `cargoNdkBuild` and `abiFilters`.

## Using Scropper

- **Switch apps**: tap the thin bar on the right edge, or swipe it inward. Tap the app you're already on to go back to its home page.
- **Move the handle**: drag it up or down. Its position is remembered.
- **Settings**: open the switcher and tap the shield.
- **Back**: the system Back gesture navigates inside the current site. Back on the site's first page sends Scropper to the background.
- **Pull to refresh**: works when the page is scrolled to the top.

## Ad blocking

How it works:

1. **Network**: every sub-request on YouTube and Instagram pages is checked against uBlock filters, uBlock quick fixes, uBlock privacy and Peter Lowe's ad server list.
2. **Scriptlets**: uBlock Origin's `+js(...)` filters are injected at document start, before the page's own scripts. On YouTube these remove ad fields (`adPlacements`, `playerAds`, `adSlots`) from player responses.
3. **Cosmetic**: CSS hides promoted cards and banners. A fallback skips any video ad that still starts.

Everything runs locally. No request goes to Brave or uBlock Origin except the daily download of the public filter lists. Ad blocking is against YouTube's terms of service; YouTube may show an "ad blockers are not allowed" prompt. Turn off *Block Ads* in Shield if a page misbehaves.

## Known limitations

- Scropper is the **mobile website**, not the official app. There are no push notifications, no downloads, no calls, and Instagram posting is more limited than in the app.
- **Instagram login** may ask you to approve on another device. If that approval hangs, use *Try another way* to get an SMS or email code instead.
- **Instagram feed videos**: Instagram serves most feed videos as Reels, so with *Hide Instagram Reels* on, many normal video posts are hidden too.
- **Background audio** relies on keeping the WebView "visible". Without a foreground service, Android may still stop playback after a while with the screen off.
- **Selectors drift**: YouTube and Instagram change their page markup without notice. If Shorts or Reels start showing up again, the selectors in `rust-core/src/lib.rs` and `inject.js` need updating.
- **Samsung gesture hint**: Samsung reports no bottom inset while still drawing the gesture pill, so Scropper adds a small bottom margin in that case.

## Future work

### Ad blocking follow-ups

- **Scriptlet updates**: filter lists update daily in the app, but the scriptlets in `resources.json` only change with a new build. If uBlock Origin adds a scriptlet that newer filters depend on, regenerate with `scripts/gen_adblock_resources.py` and rebuild. Hosting a generated copy for the app to download would remove that step.
- **Redirect resources**: `$redirect` filters currently just block. uBlock Origin's neutered replacement files are not bundled.
- **Procedural cosmetic filters** (`:has-text()`, `:upward()` and similar) are skipped; only plain CSS filters are applied.
- **Instagram sponsored posts** are detected by their "Sponsored" label in a handful of languages. A language-independent signal would be more robust.

### Other ideas

- Diagnostic logging of blocked requests (debug builds only) to help with login and blocking problems.
- An option to put the handle on the left edge.
- A foreground media service so background audio survives screen-off and shows lock-screen controls.
- More ABIs (`armeabi-v7a`, `x86_64`) for older phones and emulators.
