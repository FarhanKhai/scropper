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
    ): String
}
