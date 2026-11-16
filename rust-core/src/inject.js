(function () {
  'use strict';
  var CFG = { key: __KEY__, site: __SITE__, css: __CSS__, shorts: __SHORTS__, reels: __REELS__, explore: __EXPLORE__ };
  var prev = window.__scropper;
  if (prev && prev.key === CFG.key) { prev.sweep(); return; }
  if (prev) { try { prev.stop(); } catch (_) {} }

  var HID = 'data-scropper-hidden';
  var EXP = 'data-scropper-explore';
  var EXP_HID = 'data-scropper-explore-hidden';
  var STYLE_ID = 'scropper-style';
  var SHORT_LINK = 'a[href^="/shorts/"]';
  var REEL_LINK = 'a[href*="/reel/"]';
  var POST_LINKS = 'a[href*="/p/"], a[href*="/reel/"]';

  function each(sel, fn, root) {
    var list = (root || document).querySelectorAll(sel);
    for (var i = 0; i < list.length; i++) fn(list[i]);
  }
  // `proof` = selector that must keep matching inside el for it to stay hidden
  // (sites recycle DOM nodes; a recycled node must reappear).
  function hide(el, proof) {
    if (el && !el.hasAttribute(HID)) el.setAttribute(HID, proof || '');
  }
  function revalidate() {
    each('[' + HID + ']', function (el) {
      var proof = el.getAttribute(HID);
      if (proof && !el.querySelector(proof)) el.removeAttribute(HID);
    });
  }
  function ensureStyle() {
    var root = document.head || document.documentElement;
    if (!root) return;
    var s = document.getElementById(STYLE_ID);
    if (!CFG.css) { if (s) s.remove(); return; }
    if (!s) { s = document.createElement('style'); s.id = STYLE_ID; root.appendChild(s); }
    if (s.textContent !== CFG.css) s.textContent = CFG.css;
  }

  function sweepYoutube() {
    if (!CFG.shorts) return;
    var m = location.pathname.match(/^\/shorts\/([A-Za-z0-9_-]+)/);
    if (m) { location.replace('/watch?v=' + m[1]); return; }
    each('ytm-pivot-bar-item-renderer', function (el) {
      if (el.querySelector('.pivot-shorts, a[href^="/shorts"]') || /^\s*shorts\s*$/i.test(el.textContent || '')) hide(el);
    });
    each('ytm-reel-shelf-renderer', function (el) { hide(el); });
    each(SHORT_LINK, function (a) {
      hide(a.closest('ytm-video-with-context-renderer, ytm-compact-video-renderer, ytm-rich-item-renderer, ytm-shorts-lockup-view-model'), SHORT_LINK);
      hide(a.closest('ytm-rich-section-renderer, grid-shelf-view-model'), SHORT_LINK);
    });
  }

  function hideExploreGrid(el) {
    for (var c = el.firstElementChild; c; c = c.nextElementSibling) {
      if (c.matches('input')) continue;
      if (c.querySelector('input')) hideExploreGrid(c);
      else if (c.matches(POST_LINKS) || c.querySelector(POST_LINKS)) c.setAttribute(EXP_HID, '');
    }
  }
  function clearExplore() {
    var de = document.documentElement;
    if (de && de.hasAttribute(EXP)) de.removeAttribute(EXP);
    each('[' + EXP_HID + ']', function (el) { el.removeAttribute(EXP_HID); });
  }
  function sweepInstagram() {
    var p = location.pathname;
    if (CFG.reels) {
      if (/^\/reels(\/|$)/.test(p)) { location.replace('/'); return; }
      each('a[href*="/reels"]', function (a) { hide(a); });
      each('svg[aria-label="Reels"]', function (s) {
        hide(s.closest('a, [role="link"], [role="button"]') || s.parentElement);
      });
      each('article', function (ar) { if (ar.querySelector(REEL_LINK)) hide(ar, REEL_LINK); });
    }
    if (CFG.explore && /^\/explore\/?$/.test(p)) {
      if (document.documentElement) document.documentElement.setAttribute(EXP, '');
      each('main', hideExploreGrid);
    } else {
      clearExplore();
    }
  }

  function sweep() {
    ensureStyle();
    revalidate();
    if (CFG.site === 'youtube') sweepYoutube();
    else if (CFG.site === 'instagram') sweepInstagram();
  }

  // Throttle: any burst of DOM mutations collapses into one sweep per frame.
  var pending = false;
  var stopped = false;
  var raf = window.requestAnimationFrame ? window.requestAnimationFrame.bind(window) : function (f) { return setTimeout(f, 16); };
  function schedule() {
    if (pending || stopped) return;
    pending = true;
    raf(function () { pending = false; if (!stopped) sweep(); });
  }

  // Tell native pull-to-refresh whether the touch started inside a scrolled inner scroller.
  function onTouch(e) {
    var el = e.target, inner = false;
    while (el && el.nodeType === 1 && el !== document.body && el !== document.documentElement) {
      if (el.scrollTop > 0 && el.scrollHeight > el.clientHeight) { inner = true; break; }
      el = el.parentElement;
    }
    try { if (window.ScropperNative) window.ScropperNative.setInnerScrolled(inner); } catch (_) {}
  }

  var observer = new MutationObserver(schedule);
  observer.observe(document, { childList: true, subtree: true, attributes: true, attributeFilter: ['href'] });
  var EVENTS = ['popstate', 'pageshow', 'DOMContentLoaded', 'yt-navigate-finish'];
  EVENTS.forEach(function (n) { window.addEventListener(n, schedule, true); });
  document.addEventListener('touchstart', onTouch, { capture: true, passive: true });

  function stop() {
    stopped = true;
    observer.disconnect();
    EVENTS.forEach(function (n) { window.removeEventListener(n, schedule, true); });
    document.removeEventListener('touchstart', onTouch, { capture: true });
    var s = document.getElementById(STYLE_ID);
    if (s) s.remove();
    each('[' + HID + ']', function (el) { el.removeAttribute(HID); });
    clearExplore();
  }

  window.__scropper = { key: CFG.key, sweep: sweep, stop: stop };
  sweep();
})();
