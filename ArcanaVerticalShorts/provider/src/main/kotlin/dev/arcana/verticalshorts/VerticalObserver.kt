package dev.arcana.verticalshorts

import org.json.JSONObject

/** Observe only the original site's own matching read; never initiate an API request. */
internal object VerticalObserver {
    const val SECURITY_ASSET = "0-17d7h2qc-3z.js"

    fun script(key: String, platform: String, drama: String, episode: Int, lang: String, episodeId: String?): String = """
(function () {
  'use strict';
  var key = ${JSONObject.quote(key)};
  var expected = {platform:${JSONObject.quote(platform)},id:${JSONObject.quote(drama)},ep:${JSONObject.quote(episode.toString())},lang:${JSONObject.quote(lang)},episodeId:${episodeId?.let(JSONObject::quote) ?: "null"}};
  var originalFetch = window.fetch;
  var state = {result:null,closed:false,securityScriptDelivered:false,error:null,readers:new Set(),terminal:false,latest:0,seenPlay:0,selection:null};
  window[key] = state;
  function guard() {
    try { var g = window.__dsGuard;
    return !!g && typeof g.open === 'boolean' && typeof g.bait === 'boolean' && typeof g.lib === 'boolean' &&
      g.open === (g.bait || g.lib) && !g.open; } catch (_) { return false; }
  }
  document.addEventListener('load', function (event) {
    var el = event.target;
    if (!el || el.tagName !== 'SCRIPT') return;
    try {
      var u = new URL(el.src || el.href, location.href);
      if (u.origin === location.origin && u.pathname.endsWith('/${SECURITY_ASSET}')) state.securityScriptDelivered = true;
    } catch (_) {}
  }, true);
  document.addEventListener('error', function (event) {
    var el = event.target;
    try {
      var u = new URL(el && (el.src || el.href), location.href);
      if (u.origin === location.origin && u.pathname.endsWith('/${SECURITY_ASSET}')) stop('security_resource');
    } catch (_) {}
  }, true);
  function stop(code, result) {
    if (state.closed || state.terminal) return;
    state.terminal = true; state.error = code;
    if (result) state.result = result;
    state.readers.forEach(function(r){try{Promise.resolve(r.cancel()).catch(function(){});}catch(_){}});
  }
  function selected(input, init, diagnostic) {
    try {
      var method = (init && init.method) || (input && input.method) || 'GET';
      var u = new URL(typeof input === 'string' || input instanceof URL ? String(input) : input.url, location.href);
      if (String(method).toUpperCase() !== 'GET' || u.origin !== location.origin || u.pathname !== '/api/site/play') return null;
      if (diagnostic) state.seenPlay = Math.min(100, state.seenPlay + 1);
      function mismatch(code) { if (diagnostic) state.selection = code; return null; }
      var allowed = ['platform','id','ep','lang','episodeId'];
      for (var name of u.searchParams.keys()) {
        if (!allowed.includes(name) || u.searchParams.getAll(name).length !== 1) return mismatch('params');
      }
      if (u.searchParams.get('platform') !== expected.platform) return mismatch('platform');
      if (u.searchParams.get('id') !== expected.id) return mismatch('series');
      if (u.searchParams.get('ep') !== expected.ep) return mismatch('episode');
      if (u.searchParams.get('lang') !== expected.lang) return mismatch('language');
      if (expected.episodeId === null ? u.searchParams.has('episodeId') : u.searchParams.get('episodeId') !== expected.episodeId) return mismatch('episode_id');
      if (diagnostic) state.selection = 'matched';
      return {platform:expected.platform,id:expected.id,ep:expected.ep,lang:u.searchParams.get('lang'),episodeId:u.searchParams.get('episodeId')};
    } catch (_) { if (diagnostic) state.selection = 'invalid'; return null; }
  }
  async function observe(response, tuple, sequence) {
    if (state.closed || state.terminal) return;
    if ([401,403,429].includes(response.status)) {
      stop('denied',{status:response.status,retryAfter:(response.headers.get('Retry-After') || '').slice(0,160),tuple:tuple});
      return;
    }
    if (!guard()) { stop('guard_not_permitted'); return; }
    try {
      var finalTuple = selected(response.url);
      if (!finalTuple || JSON.stringify(finalTuple) !== JSON.stringify(tuple)) {
        stop('response_route'); return;
      }
      var copy = response.clone();
      var reader = copy.body && copy.body.getReader();
      if (!reader) { stop('response_body'); return; }
      state.readers.add(reader);
      var decoder = new TextDecoder('utf-8'), text = '', size = 0;
      for (;;) {
        var part = await reader.read();
        if (state.closed || state.terminal) { await reader.cancel(); return; }
        if (part.done) break;
        size += part.value.byteLength;
        if (size > 512000) { await reader.cancel(); stop('response_limit'); return; }
        text += decoder.decode(part.value,{stream:true});
      }
      text += decoder.decode(); state.readers.delete(reader);
      if (!guard()) { stop('guard_not_permitted'); return; }
      var bodyCode = '';
      try {
        var data = JSON.parse(text);
        bodyCode = String(typeof data.error === 'string' ? data.error : data.error && data.error.code || data.code || '').toLowerCase();
      } catch (_) {}
      if (['forbidden','unauthorized','access_denied','bad_origin','bad_referrer','rate_limited','too_many_requests','challenge_required','verification_required','captcha_required'].includes(bodyCode)) {
        stop('body_denied',{status:response.status,retryAfter:(response.headers.get('Retry-After') || '').slice(0,160),tuple:tuple,body:text}); return;
      }
      if (state.closed || state.terminal || sequence !== state.latest) return;
      state.result = {status:response.status,retryAfter:(response.headers.get('Retry-After') || '').slice(0,160),tuple:tuple,body:text};
    } catch (_) { if (!state.closed) stop('response_unavailable'); }
  }
  // Preserve the exact original request arguments, receiver, promise and response.
  // Do not disguise this observer or modify any source guard/media intrinsic.
  window.fetch = function () {
    var args = arguments;
    var promise = Reflect.apply(originalFetch, this, args);
    var tuple = selected(args[0], args[1], true);
    if (tuple && !state.closed && !state.terminal) {
      var sequence = ++state.latest;
      promise.then(function (response) { observe(response, tuple, sequence); }, function () { if (!state.closed) stop('network'); });
    }
    return promise;
  };
})();
""".trimIndent()
}
