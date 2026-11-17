package com.scropper.app

/** JNI bridge to `rust-core` (`libscropper_core.so`). Names must match `Java_com_scropper_app_RustCore_*`. */
object RustCore {
    init {
        System.loadLibrary("scropper_core")
    }

    /** `true` = answer the request with an empty 204. Safe to call from any thread. */
    @JvmStatic
    external fun shouldBlockUrl(
        url: String,
        host: String,
        blockYtShorts: Boolean,
        blockIgReels: Boolean,
        blockIgExplore: Boolean,
    ): Boolean

    /** `youtube.com/shorts/<ID>` -> `https://m.youtube.com/watch?v=<ID>`, or `null` when no rewrite applies. */
    @JvmStatic
    external fun rewriteNavigationUrl(url: String): String?

    /** CSS + throttled MutationObserver payload for `site` (`youtube` | `instagram`). */
    @JvmStatic
    external fun getInjectionScript(
        site: String,
        blockYtShorts: Boolean,
        blockIgReels: Boolean,
        blockIgExplore: Boolean,
        blockAds: Boolean,
    ): String

    /** Builds the ad-block engine from UTF-8 filter-list text. Returns the serialized engine to cache. */
    @JvmStatic
    external fun adblockLoadLists(trustedLists: ByteArray, untrustedLists: ByteArray, resourcesJson: ByteArray): ByteArray?

    /** Restores an engine serialized by [adblockLoadLists]. `false` = cache unusable. */
    @JvmStatic
    external fun adblockLoadCache(serialized: ByteArray, resourcesJson: ByteArray): Boolean

    /** `requestType`: script, image, stylesheet, xmlhttprequest, sub_frame, media, font, other. */
    @JvmStatic
    external fun adblockShouldBlock(url: String, sourceUrl: String, requestType: String, method: String): Boolean

    /** uBlock Origin scriptlets + cosmetic CSS for the page at `url`. Empty when nothing applies. */
    @JvmStatic
    external fun adblockCosmeticScript(url: String): String
}
