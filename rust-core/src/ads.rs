//! Ad blocking: Brave's `adblock` engine fed with uBlock Origin filter lists and scriptlets.
//!
//! One global engine. Kotlin either builds it from list text (slow, then caches the serialized
//! form) or restores it from that cache (fast). Until one of those succeeds nothing is blocked.

use std::sync::RwLock;

use adblock::cosmetic_filter_cache::ProceduralOrActionFilter;
use adblock::lists::{FilterSet, ParseOptions};
use adblock::request::Request;
use adblock::resources::{PermissionMask, Resource};
use adblock::Engine;

static ENGINE: RwLock<Option<Engine>> = RwLock::new(None);

/// Matches `"permission": 1` in the generated `resources.json` (uBlock Origin `requiresTrust`).
const TRUSTED: PermissionMask = PermissionMask::from_bits(1);

fn parse_resources(resources_json: &str) -> Vec<Resource> {
    serde_json::from_str(resources_json).unwrap_or_default()
}

fn install(mut engine: Engine, resources_json: &str) {
    engine.use_resources(parse_resources(resources_json));
    if let Ok(mut slot) = ENGINE.write() {
        *slot = Some(engine);
    }
}

/// Builds the engine from filter-list text and installs it. Returns the serialized engine for
/// caching. `trusted_lists` may use privileged (`trusted-*`) scriptlets; `untrusted_lists` may not.
pub fn load_from_lists(trusted_lists: &str, untrusted_lists: &str, resources_json: &str) -> Vec<u8> {
    let mut set = FilterSet::new(false);
    set.add_filter_list(
        trusted_lists.to_string(),
        ParseOptions {
            permissions: TRUSTED,
            ..ParseOptions::default()
        },
    );
    set.add_filter_list(untrusted_lists.to_string(), ParseOptions::default());
    let engine = Engine::new_with_filter_set(set);
    let serialized = engine.serialize();
    install(engine, resources_json);
    serialized
}

/// Restores an engine serialized by [`load_from_lists`]. `false` = cache unusable, rebuild.
pub fn load_from_cache(serialized: &[u8], resources_json: &str) -> bool {
    let mut engine = Engine::default();
    if engine.deserialize(serialized).is_err() {
        return false;
    }
    install(engine, resources_json);
    true
}

pub fn is_ready() -> bool {
    ENGINE.read().map(|e| e.is_some()).unwrap_or(false)
}

/// `request_type`: `script`, `image`, `stylesheet`, `xmlhttprequest`, `sub_frame`, `media`,
/// `font`, `websocket` or `other`.
pub fn should_block(url: &str, source_url: &str, request_type: &str, method: &str) -> bool {
    let Ok(guard) = ENGINE.read() else {
        return false;
    };
    let Some(engine) = guard.as_ref() else {
        return false;
    };
    match Request::new(url, source_url, request_type, method) {
        Ok(request) => engine.check_network_request(&request).should_block(),
        Err(_) => false,
    }
}

/// Document-start script for `url`: uBlock Origin scriptlets (run in the page's main world)
/// followed by a `<style>` with the site's cosmetic filters. Empty when nothing applies.
pub fn cosmetic_script(url: &str) -> String {
    let Ok(guard) = ENGINE.read() else {
        return String::new();
    };
    let Some(engine) = guard.as_ref() else {
        return String::new();
    };
    let resources = engine.url_cosmetic_resources(url);

    // One rule per selector: a selector the WebView can't parse must not void the others.
    let mut selectors: Vec<&String> = resources.hide_selectors.iter().collect();
    selectors.sort();
    let mut css = String::new();
    for selector in selectors {
        css.push_str(selector);
        css.push_str("{display:none!important}\n");
    }
    let mut styled: Vec<(String, String)> = resources
        .procedural_actions
        .iter()
        .filter_map(|json| serde_json::from_str::<ProceduralOrActionFilter>(json).ok())
        .filter_map(|filter| filter.as_css())
        .collect();
    styled.sort();
    for (selector, style) in styled {
        css.push_str(&format!("{selector}{{{style}}}\n"));
    }

    if css.is_empty() && resources.injected_script.is_empty() {
        return String::new();
    }

    let mut out = String::with_capacity(resources.injected_script.len() + css.len() + 512);
    out.push_str("(function(){\nif(window.__scropperAds)return;window.__scropperAds=true;\n");
    // uBlock Origin scriptlets expect this global; empty = no logging channel.
    out.push_str("const scriptletGlobals={};\n");
    out.push_str(&resources.injected_script);
    out.push_str("\n(function(){var css=");
    out.push_str(&crate::js_string(&css));
    out.push_str(
        ";if(!css)return;function add(){var r=document.head||document.documentElement;\
         if(!r)return false;var s=document.createElement('style');s.id='scropper-ads-style';\
         s.textContent=css;r.appendChild(s);return true;}\
         if(!add())document.addEventListener('DOMContentLoaded',add);})();\n})();",
    );
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;

    /// The engine is process-global; tests that replace it must not interleave.
    static LOCK: Mutex<()> = Mutex::new(());

    const LIST: &str = "\
||doubleclick.net^
||ads.example.com^$script
@@||ads.example.com/allowed.js
example.com##.ad-banner
example.com##.promo:style(visibility: hidden !important)
example.com##+js(set-constant, adsEnabled, false)
example.com##+js(trusted-thing, x)
";

    const RESOURCES: &str = r#"[
        {"name":"set-constant.js","aliases":["set.js"],"kind":{"mime":"application/javascript"},
         "content":"ZnVuY3Rpb24gc2V0Q29uc3RhbnQoYSwgYikgeyB3aW5kb3dbYV0gPSBiOyB9"},
        {"name":"trusted-thing.js","aliases":[],"kind":{"mime":"application/javascript"},
         "content":"ZnVuY3Rpb24gdHJ1c3RlZFRoaW5nKGEpIHsgfQ==","permission":1}
    ]"#;

    #[test]
    fn blocks_ad_requests_and_honours_exceptions() {
        let _guard = LOCK.lock().unwrap();
        load_from_lists(LIST, "", RESOURCES);
        assert!(is_ready());
        let page = "https://m.youtube.com/watch?v=abc";
        assert!(should_block("https://ad.doubleclick.net/pixel?x=1", page, "image", "GET"));
        assert!(should_block("https://ads.example.com/tag.js", page, "script", "GET"));
        // `$script` filter must not match other request types.
        assert!(!should_block("https://ads.example.com/tag.png", page, "image", "GET"));
        assert!(!should_block("https://ads.example.com/allowed.js", page, "script", "GET"));
        assert!(!should_block("https://m.youtube.com/youtubei/v1/player", page, "xmlhttprequest", "POST"));
        assert!(!should_block("not a url", page, "other", "GET"));
    }

    #[test]
    fn cosmetic_script_has_scriptlets_and_css() {
        let _guard = LOCK.lock().unwrap();
        load_from_lists(LIST, "", RESOURCES);
        let script = cosmetic_script("https://example.com/page");
        assert!(script.contains("function setConstant("), "{script}");
        assert!(script.contains(r#"setConstant("adsEnabled", "false")"#), "{script}");
        assert!(script.contains("trustedThing("), "trusted list may use trusted scriptlets");
        assert!(script.contains(".ad-banner{display:none!important}"));
        assert!(script.contains(".promo{visibility: hidden !important}"));
        assert!(script.contains("const scriptletGlobals={}"));
        assert!(script.contains("__scropperAds"));
        assert_eq!(cosmetic_script("https://unrelated.org/"), "");
    }

    #[test]
    fn untrusted_lists_cannot_use_trusted_scriptlets() {
        let _guard = LOCK.lock().unwrap();
        load_from_lists("", LIST, RESOURCES);
        let script = cosmetic_script("https://example.com/page");
        assert!(script.contains("setConstant("));
        assert!(!script.contains("trustedThing("), "{script}");
    }

    #[test]
    fn cache_round_trip_preserves_rules() {
        let _guard = LOCK.lock().unwrap();
        let serialized = load_from_lists(LIST, "", RESOURCES);
        assert!(!serialized.is_empty());
        // Replace the engine with an empty one, then restore from the cache.
        load_from_lists("", "", "[]");
        assert!(!should_block("https://ad.doubleclick.net/x", "https://a.com/", "image", "GET"));
        assert!(load_from_cache(&serialized, RESOURCES));
        assert!(should_block("https://ad.doubleclick.net/x", "https://a.com/", "image", "GET"));
        assert!(cosmetic_script("https://example.com/").contains("trustedThing("));
        assert!(!load_from_cache(b"garbage", RESOURCES));
    }

    /// Runs against the real bundled lists when present (they live in the Android assets).
    #[test]
    fn bundled_lists_cover_youtube() {
        let _guard = LOCK.lock().unwrap();
        let dir = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../android/app/src/main/assets/adblock");
        let read = |name: &str| std::fs::read_to_string(dir.join(name));
        let (Ok(filters), Ok(quick), Ok(privacy), Ok(lowe), Ok(resources)) = (
            read("ublock-filters.txt"),
            read("ublock-quick-fixes.txt"),
            read("ublock-privacy.txt"),
            read("peter-lowe.txt"),
            read("resources.json"),
        ) else {
            eprintln!("bundled lists not found, skipping");
            return;
        };
        let trusted = format!("{filters}\n{quick}\n{privacy}");
        let serialized = load_from_lists(&trusted, &lowe, &resources);
        assert!(load_from_cache(&serialized, &resources));

        let page = "https://m.youtube.com/watch?v=abc";
        assert!(should_block("https://googleads.g.doubleclick.net/pagead/id", page, "xmlhttprequest", "GET"));
        assert!(!should_block("https://m.youtube.com/youtubei/v1/player?prettyPrint=false", page, "xmlhttprequest", "POST"));
        assert!(!should_block("https://i.ytimg.com/vi/abc/hqdefault.jpg", page, "image", "GET"));

        let script = cosmetic_script("https://m.youtube.com/");
        assert!(script.contains("function jsonPrune("), "YouTube ad scriptlets missing");
        assert!(script.contains("adPlacements"), "YouTube ad-pruning rules missing");
        assert!(script.contains("function safeSelf("), "scriptlet dependency missing");
        eprintln!(
            "youtube script: {} bytes, engine cache: {} bytes",
            script.len(),
            serialized.len()
        );
        let ig = cosmetic_script("https://www.instagram.com/");
        eprintln!("instagram script: {} bytes", ig.len());
    }
}
