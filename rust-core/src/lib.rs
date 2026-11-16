//! Scropper core: network blocker, Shorts URL rewriter, DOM eraser payload generator.
//! Pure logic lives at the top level; the JNI surface is in `jni_bridge`.

const INJECT_TEMPLATE: &str = include_str!("inject.js");
const PAYLOAD_VERSION: u32 = 1;

// ---------------------------------------------------------------------------
// URL helpers
// ---------------------------------------------------------------------------

struct UrlParts<'a> {
    host: &'a str,
    path: &'a str,
}

/// Minimal splitter for absolute http(s) URLs. Returns `None` for anything else.
fn split_url(url: &str) -> Option<UrlParts<'_>> {
    let url = url.trim();
    let scheme_end = url.find("://")?;
    let scheme = &url[..scheme_end];
    if !scheme.eq_ignore_ascii_case("http") && !scheme.eq_ignore_ascii_case("https") {
        return None;
    }
    let rest = &url[scheme_end + 3..];
    let authority_end = rest.find(['/', '?', '#']).unwrap_or(rest.len());
    let authority = &rest[..authority_end];
    let host_port = authority.rsplit('@').next().unwrap_or(authority);
    let host = host_port.split(':').next().unwrap_or(host_port);
    let after = &rest[authority_end..];
    let path_end = after.find(['?', '#']).unwrap_or(after.len());
    let path = if after.starts_with('/') { &after[..path_end] } else { "" };
    Some(UrlParts { host, path })
}

fn host_matches(host: &str, domain: &str) -> bool {
    host == domain
        || (host.len() > domain.len()
            && host.ends_with(domain)
            && host.as_bytes()[host.len() - domain.len() - 1] == b'.')
}

fn is_youtube_host(host: &str) -> bool {
    host_matches(host, "youtube.com")
        || host_matches(host, "youtube-nocookie.com")
        || host_matches(host, "youtubei.googleapis.com")
}

fn is_instagram_host(host: &str) -> bool {
    host_matches(host, "instagram.com")
}

// ---------------------------------------------------------------------------
// Network-level blocker
// ---------------------------------------------------------------------------

const YT_SHORTS_PATTERNS: &[&str] = &["/youtubei/v1/reel/", "reel_watch_sequence", "reel_item_watch"];

/// Instagram paths that must never be blocked (DMs, Stories, Feed, profiles).
/// Note: Stories are served from `/api/v1/feed/reels_tray/` and `/api/v1/feed/reels_media/`.
const IG_ALLOW_PATTERNS: &[&str] = &["/direct/", "/direct_v2/", "/stories/", "/feed/", "/api/v1/users/"];

const IG_REELS_PATTERNS: &[&str] = &["/api/v1/clips/", "/clips/discover/"];

const IG_EXPLORE_PATTERNS: &[&str] = &[
    "/api/v1/discover/topical_explore",
    "/api/v1/discover/web/explore_grid",
    "/api/v1/discover/explore",
];

/// `true` = answer the request with an empty 204 instead of hitting the network.
pub fn should_block_url(
    url: &str,
    host: &str,
    block_yt_shorts: bool,
    block_ig_reels: bool,
    block_ig_explore: bool,
) -> bool {
    if !(block_yt_shorts || block_ig_reels || block_ig_explore) {
        return false;
    }
    let url_lc = url.trim().to_ascii_lowercase();
    let parts = split_url(&url_lc);
    let host_lc = host.trim().to_ascii_lowercase();
    let host = if host_lc.is_empty() {
        parts.as_ref().map(|p| p.host).unwrap_or("")
    } else {
        host_lc.as_str()
    };
    let path = parts.as_ref().map(|p| p.path).unwrap_or("");

    if is_youtube_host(host) {
        return block_yt_shorts && YT_SHORTS_PATTERNS.iter().any(|p| url_lc.contains(p));
    }

    if is_instagram_host(host) {
        if IG_ALLOW_PATTERNS.iter().any(|p| path.contains(p)) {
            return false;
        }
        if block_ig_reels {
            // Top-level `/reels/` only: `/<username>/reels/` is a profile tab and stays allowed.
            if path == "/reels" || path.starts_with("/reels/") {
                return true;
            }
            if IG_REELS_PATTERNS.iter().any(|p| path.contains(p)) {
                return true;
            }
        }
        if block_ig_explore && IG_EXPLORE_PATTERNS.iter().any(|p| path.starts_with(p)) {
            return true;
        }
    }
    false
}

// ---------------------------------------------------------------------------
// URL rewriter
// ---------------------------------------------------------------------------

/// `youtube.com/shorts/<ID>` -> `https://m.youtube.com/watch?v=<ID>`. `None` = leave URL alone.
pub fn rewrite_navigation_url(url: &str) -> Option<String> {
    let parts = split_url(url)?;
    let host = parts.host.to_ascii_lowercase();
    if !host_matches(&host, "youtube.com") {
        return None;
    }
    let rest = parts.path.strip_prefix("/shorts/")?;
    let id = rest.split('/').next().unwrap_or("");
    let valid = !id.is_empty()
        && id.len() <= 64
        && id.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-');
    valid.then(|| format!("https://m.youtube.com/watch?v={id}"))
}

// ---------------------------------------------------------------------------
// DOM eraser payload
// ---------------------------------------------------------------------------

const BASE_CSS: &str = "[data-scropper-hidden],[data-scropper-explore-hidden]{display:none!important}";

const YT_SHORTS_CSS: &str = concat!(
    "ytm-reel-shelf-renderer,",
    "ytm-pivot-bar-item-renderer:has(.pivot-shorts),",
    "ytm-pivot-bar-item-renderer:has(a[href^=\"/shorts\"]),",
    "ytm-rich-section-renderer:has(a[href^=\"/shorts/\"]),",
    "ytm-rich-item-renderer:has(a[href^=\"/shorts/\"]),",
    "ytm-video-with-context-renderer:has(a[href^=\"/shorts/\"]),",
    "ytm-compact-video-renderer:has(a[href^=\"/shorts/\"]),",
    "grid-shelf-view-model:has(a[href^=\"/shorts/\"]),",
    "ytm-shorts-lockup-view-model",
    "{display:none!important}"
);

const IG_REELS_CSS: &str = concat!(
    "a[href*=\"/reels\"],",
    "a:has(svg[aria-label=\"Reels\"]),",
    "article:has(a[href*=\"/reel/\"])",
    "{display:none!important}"
);

const IG_EXPLORE_CSS: &str = concat!(
    "html[data-scropper-explore] main a[href*=\"/p/\"],",
    "html[data-scropper-explore] main a[href*=\"/reel/\"]",
    "{display:none!important}"
);

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum Site {
    Youtube,
    Instagram,
}

impl Site {
    fn parse(site: &str) -> Option<Site> {
        let s = site.trim().to_ascii_lowercase();
        if s == "youtube" || host_matches(&s, "youtube.com") {
            Some(Site::Youtube)
        } else if s == "instagram" || host_matches(&s, "instagram.com") {
            Some(Site::Instagram)
        } else {
            None
        }
    }

    fn name(self) -> &'static str {
        match self {
            Site::Youtube => "youtube",
            Site::Instagram => "instagram",
        }
    }
}

fn js_string(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 2);
    out.push('"');
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '<' => out.push_str("\\x3c"),
            c => out.push(c),
        }
    }
    out.push('"');
    out
}

/// CSS + rAF-throttled MutationObserver script for `site` (`youtube` | `instagram`, or a hostname).
/// Unknown site -> empty string. All toggles off -> script that only tears down earlier hiding.
pub fn get_injection_script(
    site: &str,
    block_yt_shorts: bool,
    block_ig_reels: bool,
    block_ig_explore: bool,
) -> String {
    let Some(site) = Site::parse(site) else {
        return String::new();
    };
    let (shorts, reels, explore) = match site {
        Site::Youtube => (block_yt_shorts, false, false),
        Site::Instagram => (false, block_ig_reels, block_ig_explore),
    };

    let mut css = String::new();
    if shorts || reels || explore {
        css.push_str(BASE_CSS);
    }
    if shorts {
        css.push_str(YT_SHORTS_CSS);
    }
    if reels {
        css.push_str(IG_REELS_CSS);
    }
    if explore {
        css.push_str(IG_EXPLORE_CSS);
    }

    let key = format!(
        "v{PAYLOAD_VERSION}:{}:{}{}{}",
        site.name(),
        shorts as u8,
        reels as u8,
        explore as u8
    );

    INJECT_TEMPLATE
        .replace("__KEY__", &js_string(&key))
        .replace("__SITE__", &js_string(site.name()))
        .replace("__CSS__", &js_string(&css))
        .replace("__SHORTS__", if shorts { "true" } else { "false" })
        .replace("__REELS__", if reels { "true" } else { "false" })
        .replace("__EXPLORE__", if explore { "true" } else { "false" })
}

// ---------------------------------------------------------------------------
// JNI surface: com.scropper.app.RustCore
// ---------------------------------------------------------------------------

mod jni_bridge {
    use jni::objects::{JClass, JString};
    use jni::sys::{jboolean, jstring};
    use jni::JNIEnv;

    fn read(env: &mut JNIEnv, s: &JString) -> String {
        if s.is_null() {
            return String::new();
        }
        env.get_string(s).map(Into::into).unwrap_or_default()
    }

    #[no_mangle]
    pub extern "system" fn Java_com_scropper_app_RustCore_shouldBlockUrl<'l>(
        mut env: JNIEnv<'l>,
        _class: JClass<'l>,
        url: JString<'l>,
        host: JString<'l>,
        block_yt_shorts: jboolean,
        block_ig_reels: jboolean,
        block_ig_explore: jboolean,
    ) -> jboolean {
        let url = read(&mut env, &url);
        let host = read(&mut env, &host);
        super::should_block_url(
            &url,
            &host,
            block_yt_shorts != 0,
            block_ig_reels != 0,
            block_ig_explore != 0,
        ) as jboolean
    }

    #[no_mangle]
    pub extern "system" fn Java_com_scropper_app_RustCore_rewriteNavigationUrl<'l>(
        mut env: JNIEnv<'l>,
        _class: JClass<'l>,
        url: JString<'l>,
    ) -> jstring {
        let url = read(&mut env, &url);
        match super::rewrite_navigation_url(&url) {
            Some(out) => env
                .new_string(out)
                .map(|s| s.into_raw())
                .unwrap_or(std::ptr::null_mut()),
            None => std::ptr::null_mut(),
        }
    }

    #[no_mangle]
    pub extern "system" fn Java_com_scropper_app_RustCore_getInjectionScript<'l>(
        mut env: JNIEnv<'l>,
        _class: JClass<'l>,
        site: JString<'l>,
        block_yt_shorts: jboolean,
        block_ig_reels: jboolean,
        block_ig_explore: jboolean,
    ) -> jstring {
        let site = read(&mut env, &site);
        let script = super::get_injection_script(
            &site,
            block_yt_shorts != 0,
            block_ig_reels != 0,
            block_ig_explore != 0,
        );
        env.new_string(script)
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut())
    }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    const ALL: (bool, bool, bool) = (true, true, true);

    fn blocked(url: &str, host: &str, t: (bool, bool, bool)) -> bool {
        should_block_url(url, host, t.0, t.1, t.2)
    }

    // --- blocker: YouTube ---------------------------------------------------

    #[test]
    fn blocks_youtube_shorts_endpoints() {
        for url in [
            "https://m.youtube.com/youtubei/v1/reel/reel_watch_sequence?prettyPrint=false",
            "https://m.youtube.com/youtubei/v1/reel/reel_item_watch?prettyPrint=false",
            "https://www.youtube.com/youtubei/v1/reel/whatever",
            "https://youtubei.googleapis.com/youtubei/v1/reel/reel_item_watch",
            "https://m.youtube.com/api/x?next=reel_watch_sequence",
            "HTTPS://M.YOUTUBE.COM/youtubei/v1/REEL/reel_item_watch",
        ] {
            let host = split_url(url).unwrap().host.to_string();
            assert!(blocked(url, &host, ALL), "should block {url}");
        }
    }

    #[test]
    fn allows_regular_youtube_endpoints() {
        for url in [
            "https://m.youtube.com/",
            "https://m.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://m.youtube.com/youtubei/v1/browse?prettyPrint=false",
            "https://m.youtube.com/youtubei/v1/player?prettyPrint=false",
            "https://m.youtube.com/youtubei/v1/next?prettyPrint=false",
            "https://m.youtube.com/youtubei/v1/search?prettyPrint=false",
            "https://m.youtube.com/results?search_query=reel+fishing",
        ] {
            assert!(!blocked(url, "m.youtube.com", ALL), "should allow {url}");
        }
    }

    #[test]
    fn youtube_toggle_off_allows_shorts_endpoints() {
        let url = "https://m.youtube.com/youtubei/v1/reel/reel_watch_sequence";
        assert!(!blocked(url, "m.youtube.com", (false, true, true)));
        assert!(blocked(url, "m.youtube.com", (true, false, false)));
    }

    // --- blocker: Instagram -------------------------------------------------

    #[test]
    fn blocks_instagram_reels_endpoints() {
        for url in [
            "https://www.instagram.com/api/v1/clips/home/",
            "https://i.instagram.com/api/v1/clips/discover/stream/",
            "https://www.instagram.com/clips/discover/",
            "https://www.instagram.com/reels/",
            "https://www.instagram.com/reels/Cxyz123/",
            "https://www.instagram.com/reels",
            "https://www.instagram.com/api/v1/clips/user/?hl=en",
        ] {
            assert!(blocked(url, "www.instagram.com", ALL), "should block {url}");
        }
    }

    #[test]
    fn strictly_allows_instagram_dm_stories_feed_profiles() {
        for url in [
            "https://www.instagram.com/",
            "https://www.instagram.com/direct/inbox/",
            "https://www.instagram.com/direct/t/1234567890/",
            "https://www.instagram.com/api/v1/direct_v2/inbox/",
            "https://www.instagram.com/api/v1/direct_v2/threads/123/",
            "https://www.instagram.com/stories/someuser/3141592653/",
            "https://www.instagram.com/api/v1/feed/timeline/",
            "https://www.instagram.com/api/v1/feed/reels_tray/",
            "https://www.instagram.com/api/v1/feed/reels_media/?reel_ids=1",
            "https://www.instagram.com/api/v1/feed/user/123/",
            "https://www.instagram.com/api/v1/users/web_profile_info/?username=someuser",
            "https://www.instagram.com/someuser/",
            "https://www.instagram.com/someuser/reels/",
            "https://www.instagram.com/someuser/tagged/",
            "https://www.instagram.com/p/Cabc123/",
            "https://www.instagram.com/accounts/login/",
            "https://www.instagram.com/graphql/query",
        ] {
            assert!(!blocked(url, "www.instagram.com", ALL), "should allow {url}");
        }
    }

    #[test]
    fn allowlist_wins_over_reels_patterns() {
        assert!(!blocked(
            "https://www.instagram.com/api/v1/direct_v2/threads/1/clips/discover/",
            "www.instagram.com",
            ALL
        ));
        assert!(!blocked(
            "https://www.instagram.com/api/v1/feed/x/api/v1/clips/",
            "www.instagram.com",
            ALL
        ));
    }

    #[test]
    fn instagram_reels_toggle_off_allows_reels() {
        let t = (true, false, true);
        assert!(!blocked("https://www.instagram.com/api/v1/clips/home/", "www.instagram.com", t));
        assert!(!blocked("https://www.instagram.com/reels/", "www.instagram.com", t));
    }

    #[test]
    fn instagram_explore_toggle_controls_explore_grid_only() {
        let grid = "https://www.instagram.com/api/v1/discover/web/explore_grid/?max_id=0";
        let topical = "https://www.instagram.com/api/v1/discover/topical_explore/";
        let search = "https://www.instagram.com/api/v1/web/search/topsearch/?query=cats";
        assert!(blocked(grid, "www.instagram.com", (false, false, true)));
        assert!(blocked(topical, "www.instagram.com", (false, false, true)));
        assert!(!blocked(grid, "www.instagram.com", (true, true, false)));
        assert!(!blocked(search, "www.instagram.com", ALL));
        assert!(!blocked("https://www.instagram.com/explore/search/", "www.instagram.com", ALL));
    }

    // --- blocker: general ---------------------------------------------------

    #[test]
    fn all_toggles_off_blocks_nothing() {
        let off = (false, false, false);
        assert!(!blocked("https://m.youtube.com/youtubei/v1/reel/reel_item_watch", "m.youtube.com", off));
        assert!(!blocked("https://www.instagram.com/api/v1/clips/home/", "www.instagram.com", off));
    }

    #[test]
    fn unrelated_hosts_never_blocked() {
        for (url, host) in [
            ("https://example.com/reels/", "example.com"),
            ("https://example.com/youtubei/v1/reel/reel_item_watch", "example.com"),
            ("https://notinstagram.com/api/v1/clips/home/", "notinstagram.com"),
            ("https://fakeyoutube.com/youtubei/v1/reel/x", "fakeyoutube.com"),
            ("https://accounts.google.com/signin?continue=reel_watch_sequence", "accounts.google.com"),
            ("https://scontent.cdninstagram.com/v/reels/video.mp4", "scontent.cdninstagram.com"),
        ] {
            assert!(!blocked(url, host, ALL), "should allow {url}");
        }
    }

    #[test]
    fn empty_host_falls_back_to_url_host() {
        assert!(blocked("https://www.instagram.com/api/v1/clips/home/", "", ALL));
        assert!(blocked("https://m.youtube.com/youtubei/v1/reel/reel_item_watch", "", ALL));
        assert!(!blocked("not a url", "", ALL));
        assert!(!blocked("", "", ALL));
    }

    #[test]
    fn host_argument_is_case_insensitive() {
        assert!(blocked("https://www.instagram.com/api/v1/clips/home/", "WWW.Instagram.COM", ALL));
    }

    // --- rewriter -----------------------------------------------------------

    #[test]
    fn rewrites_shorts_to_watch() {
        let want = Some("https://m.youtube.com/watch?v=abc_DEF-123".to_string());
        for url in [
            "https://youtube.com/shorts/abc_DEF-123",
            "https://www.youtube.com/shorts/abc_DEF-123",
            "https://m.youtube.com/shorts/abc_DEF-123",
            "http://m.youtube.com/shorts/abc_DEF-123",
            "https://m.youtube.com/shorts/abc_DEF-123/",
            "https://youtube.com/shorts/abc_DEF-123?si=trackingjunk&feature=share",
            "https://youtube.com/shorts/abc_DEF-123#t=5",
            "HTTPS://WWW.YOUTUBE.COM/shorts/abc_DEF-123",
            "  https://youtube.com/shorts/abc_DEF-123  ",
        ] {
            assert_eq!(rewrite_navigation_url(url), want, "url: {url}");
        }
    }

    #[test]
    fn leaves_non_shorts_urls_alone() {
        for url in [
            "https://m.youtube.com/",
            "https://m.youtube.com/watch?v=abc123",
            "https://m.youtube.com/shorts/",
            "https://m.youtube.com/shorts",
            "https://m.youtube.com/feed/shorts/abc",
            "https://m.youtube.com/shorts/bad%20id",
            "https://m.youtube.com/shorts/<script>",
            "https://example.com/shorts/abc123",
            "https://notyoutube.com/shorts/abc123",
            "https://www.instagram.com/reels/abc123/",
            "https://example.com/?u=https://youtube.com/shorts/abc123",
            "intent://youtube.com/shorts/abc123",
            "",
            "garbage",
        ] {
            assert_eq!(rewrite_navigation_url(url), None, "url: {url}");
        }
    }

    // --- injection payload --------------------------------------------------

    fn no_placeholders(script: &str) {
        for p in ["__KEY__", "__SITE__", "__CSS__", "__SHORTS__", "__REELS__", "__EXPLORE__"] {
            assert!(!script.contains(p), "unreplaced placeholder {p}");
        }
    }

    fn balanced(script: &str) {
        for (open, close) in [('(', ')'), ('{', '}'), ('[', ']')] {
            assert_eq!(
                script.matches(open).count(),
                script.matches(close).count(),
                "unbalanced {open}{close}"
            );
        }
    }

    #[test]
    fn youtube_payload_hides_shorts() {
        let s = get_injection_script("youtube", true, true, true);
        no_placeholders(&s);
        balanced(&s);
        for needle in [
            "MutationObserver",
            "requestAnimationFrame",
            "ytm-pivot-bar-item-renderer",
            "ytm-reel-shelf-renderer",
            "ytm-rich-section-renderer",
            "ytm-video-with-context-renderer",
            "ytm-compact-video-renderer",
            r#"a[href^=\"/shorts/\"]"#,
            "display:none!important",
            r#"site: "youtube""#,
            "shorts: true",
            "reels: false",
            "explore: false",
            "/watch?v=",
        ] {
            assert!(s.contains(needle), "missing {needle}");
        }
        // Instagram CSS must not leak into the YouTube payload.
        assert!(!s.contains(r#"svg[aria-label=\"Reels\"]"#));
        assert!(!s.contains("html[data-scropper-explore] main"));
    }

    #[test]
    fn instagram_payload_hides_reels_and_explore() {
        let s = get_injection_script("instagram", true, true, true);
        no_placeholders(&s);
        balanced(&s);
        for needle in [
            "MutationObserver",
            "requestAnimationFrame",
            r#"a[href*=\"/reels\"]"#,
            r#"svg[aria-label=\"Reels\"]"#,
            r#"article:has(a[href*=\"/reel/\"])"#,
            "html[data-scropper-explore] main",
            r#"site: "instagram""#,
            "shorts: false",
            "reels: true",
            "explore: true",
        ] {
            assert!(s.contains(needle), "missing {needle}");
        }
        assert!(!s.contains("ytm-reel-shelf-renderer,"));
    }

    #[test]
    fn instagram_explore_is_optional() {
        let s = get_injection_script("instagram", true, true, false);
        assert!(s.contains("reels: true"));
        assert!(s.contains("explore: false"));
        assert!(!s.contains("html[data-scropper-explore] main"));

        let s = get_injection_script("instagram", true, false, true);
        assert!(s.contains("reels: false"));
        assert!(s.contains("explore: true"));
        assert!(s.contains("html[data-scropper-explore] main"));
        assert!(!s.contains(r#"article:has(a[href*=\"/reel/\"])"#));
    }

    #[test]
    fn instagram_payload_never_targets_dms_or_stories() {
        let s = get_injection_script("instagram", true, true, true);
        assert!(!s.contains("/direct"));
        assert!(!s.contains("/stories"));
    }

    #[test]
    fn toggles_off_yield_teardown_only_payload() {
        let yt = get_injection_script("youtube", false, true, true);
        no_placeholders(&yt);
        assert!(yt.contains(r#"css: """#));
        assert!(yt.contains("shorts: false"));

        let ig = get_injection_script("instagram", true, false, false);
        assert!(ig.contains(r#"css: """#));
        assert!(ig.contains("reels: false"));
        assert!(ig.contains("explore: false"));
    }

    #[test]
    fn payload_key_changes_with_toggles() {
        let a = get_injection_script("instagram", false, true, false);
        let b = get_injection_script("instagram", false, true, true);
        let c = get_injection_script("instagram", true, true, false);
        assert_ne!(a, b);
        // YouTube toggle is irrelevant to the Instagram payload.
        assert_eq!(a, c);
        assert!(a.contains(r#"key: "v1:instagram:010""#));
        assert!(b.contains(r#"key: "v1:instagram:011""#));
    }

    #[test]
    fn site_accepts_names_and_hosts() {
        assert_eq!(
            get_injection_script("m.youtube.com", true, false, false),
            get_injection_script("youtube", true, false, false)
        );
        assert_eq!(
            get_injection_script("WWW.INSTAGRAM.COM", false, true, true),
            get_injection_script("instagram", false, true, true)
        );
        assert_eq!(get_injection_script("example.com", true, true, true), "");
        assert_eq!(get_injection_script("", true, true, true), "");
    }

    #[test]
    fn payload_is_idempotent_iife() {
        let s = get_injection_script("youtube", true, false, false);
        assert!(s.trim_start().starts_with("(function"));
        assert!(s.trim_end().ends_with("})();"));
        assert!(s.contains("window.__scropper"));
        assert!(s.contains("prev.key === CFG.key"));
    }

    #[test]
    fn js_string_escapes() {
        assert_eq!(js_string(r#"a"b\c"#), r#""a\"b\\c""#);
        assert_eq!(js_string("x\ny</style>"), r#""x\ny\x3c/style>""#);
    }

    // --- helpers ------------------------------------------------------------

    #[test]
    fn split_url_parts() {
        let p = split_url("https://user@www.example.com:8443/a/b?x=1#frag").unwrap();
        assert_eq!(p.host, "www.example.com");
        assert_eq!(p.path, "/a/b");
        let p = split_url("https://example.com?x=/y").unwrap();
        assert_eq!(p.host, "example.com");
        assert_eq!(p.path, "");
        assert!(split_url("ftp://example.com/").is_none());
        assert!(split_url("example.com/a").is_none());
    }

    #[test]
    fn host_matching_respects_label_boundaries() {
        assert!(host_matches("youtube.com", "youtube.com"));
        assert!(host_matches("m.youtube.com", "youtube.com"));
        assert!(!host_matches("fakeyoutube.com", "youtube.com"));
        assert!(!host_matches("youtube.com.evil.io", "youtube.com"));
    }
}
