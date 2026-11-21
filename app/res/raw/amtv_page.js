// Injected into music.apple.com once it has loaded (MusicView.onPageFinished). Idempotent.
// Includes Chrome 66+ WebView polyfills and automatic MusicKit v3 bootstrapper when Apple's ES2020 bundle doesn't run on older WebViews.
(function () {
  if (window.__amtv) return;

  // --- Chrome 66+ Polyfills ---
  if (typeof window.globalThis === 'undefined') window.globalThis = window;
  if (!Array.prototype.flat) {
    Array.prototype.flat = function (d) {
      d = d === undefined ? 1 : Number(d);
      var r = [];
      (function f(a, l) {
        for (var i = 0; i < a.length; i++) {
          if (Array.isArray(a[i]) && l > 0) f(a[i], l - 1);
          else r.push(a[i]);
        }
      })(this, d);
      return r;
    };
  }
  if (!Array.prototype.flatMap) {
    Array.prototype.flatMap = function (fn, ctx) { return this.map(fn, ctx).flat(1); };
  }
  if (!Object.fromEntries) {
    Object.fromEntries = function (iter) {
      var o = {};
      if (!iter) return o;
      for (var p of iter) if (p) o[p[0]] = p[1];
      return o;
    };
  }
  if (!Element.prototype.replaceChildren) {
    var rc = function () {
      while (this.firstChild) this.removeChild(this.firstChild);
      for (var i = 0; i < arguments.length; i++) {
        var c = arguments[i];
        if (c != null && c !== false) this.appendChild(c.nodeType ? c : document.createTextNode(String(c)));
      }
    };
    Element.prototype.replaceChildren = rc;
    if (typeof DocumentFragment !== 'undefined') DocumentFragment.prototype.replaceChildren = rc;
    if (typeof ShadowRoot !== 'undefined') ShadowRoot.prototype.replaceChildren = rc;
  }

  // Block Apple's background HLS animated artwork videos that waste bandwidth/CPU
  var MOTION = /^https:\/\/mvod\.itunes\.apple\.com\/itunes-assets\//;
  var xopen = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function (method, url) {
    var args = Array.prototype.slice.call(arguments);
    if (MOTION.test(String(url))) args[1] = 'data:,';
    return xopen.apply(this, args);
  };

  var DIAG = 'amtv.diag', diagLog = [];
  try { diagLog = JSON.parse(localStorage.getItem(DIAG)) || []; } catch (e) {}
  function diag(entry) {
    entry.at = new Date().toISOString().slice(5, 19).replace('T', ' ');
    diagLog.push(entry);
    if (diagLog.length > 40) diagLog.splice(0, diagLog.length - 40);
    try { localStorage.setItem(DIAG, JSON.stringify(diagLog)); } catch (e) {}
  }
  var AUTH = /^https:\/\/auth\.[a-z.]*apple\.com\/auth\//;
  var xfetch = window.fetch;
  window.fetch = function (input) {
    var url = String(typeof input === 'string' ? input : (input && input.url) || input);
    if (MOTION.test(url)) return Promise.resolve(new Response('', { status: 404 }));
    var p = xfetch.apply(this, arguments);
    if (AUTH.test(url)) {
      var e = { ev: 'auth-service', url: url.replace(/\?.*/, '') };
      p.then(function (r) {
        e.status = r.status;
        if (r.ok) return diag(e);
        return r.clone().text().then(function (t) { e.body = t.slice(0, 200); diag(e); }, function () { diag(e); });
      }, function (err) { e.error = String(err); diag(e); });
    }
    return p;
  };

  var raf = window.requestAnimationFrame.bind(window), held = [], awakeUntil = 0;

  // Automatic MusicKit v3 bootstrapper for WebViews where Apple's ES2020 index.js bundle doesn't execute
  var DEFAULT_DEV_TOKEN = 'eyJ0eXAiOiJKV1QiLCJhbGciOiJFUzI1NiIsImtpZCI6IldlYlBsYXlLaWQifQ.eyJpc3MiOiJBTVBXZWJQbGF5IiwiaWF0IjoxNzkzOTAwNzA4LCJleHAiOjE3OTk5NDg3MDgsInJvb3RfaHR0cHNfb3JpZ2luIjpbImFwcGxlLmNvbSJdfQ.7L-PYJHjO8PpFdhKS-OSkf_snyFvg5cq-JDERUs_N_UcUX9Vk0kf1WLatgDeEvhB_Arg1b5Tl-99G1v6hUwH0A';
  var DEFAULT_USER_TOKEN = '';
  var mkBooting = null;

  function applyUserToken(mk) {
    var ut = DEFAULT_USER_TOKEN;
    try { ut = localStorage.getItem('amtv.user.token') || DEFAULT_USER_TOKEN; } catch (e) {}
    if (!ut) return;
    try {
      document.cookie = 'media-user-token=' + ut + '; domain=.apple.com; path=/; max-age=31536000; secure';
      document.cookie = 'media-user-token=' + ut + '; domain=.music.apple.com; path=/; max-age=31536000; secure';
      document.cookie = 'media-user-token=' + ut + '; domain=music.apple.com; path=/; max-age=31536000; secure';
      localStorage.setItem('music.ampwebplay.media-user-token', ut);
    } catch (e) {}
    if (mk) {
      try { mk.musicUserToken = ut; } catch (e) {}
      try { if (mk._services && mk._services.itemLoader) mk._services.itemLoader.musicUserToken = ut; } catch (e) {}
      try { if (mk._services && mk._services.apiManager) mk._services.apiManager.userToken = ut; } catch (e) {}
    }
  }

  async function syncUserToken(mk) {
    if (!mk) return;
    applyUserToken(mk);
    if (mk.storefrontCountryCode !== mk.storefrontId) {
      try { await mk.me(); } catch (e) {}
    }
  }

  async function ensureMusicKit() {
    if (window.MusicKit && window.MusicKit.getInstance) {
      try {
        var existing = window.MusicKit.getInstance();
        if (existing) {
          await syncUserToken(existing);
          return existing;
        }
      } catch (e) {}
    }
    if (mkBooting) return mkBooting;
    mkBooting = (async function () {
      applyUserToken(null);
      if (!window.MusicKit || typeof window.MusicKit.configure !== 'function') {
        try {
          var v3 = await xfetch('https://js-cdn.music.apple.com/musickit/v3/musickit.js').then(function (r) { return r.text(); });
          (0, eval)(v3);
        } catch (e) {
          console.log('amtv musickit load err', e);
        }
      }
      if (!window.MusicKit || typeof window.MusicKit.configure !== 'function') return null;
      try {
        var inst = window.MusicKit.getInstance();
        if (inst) {
          await syncUserToken(inst);
          return inst;
        }
      } catch (e) {}

      // Try to extract latest developerToken from the page's index.js bundle if different, else use DEFAULT_DEV_TOKEN
      var token = DEFAULT_DEV_TOKEN;
      try {
        var idxScript = document.querySelector('script[src*="/assets/index"]');
        if (idxScript && idxScript.src) {
          var txt = await Promise.race([
            xfetch(idxScript.src).then(function (r) { return r.text(); }),
            new Promise(function (_, rej) { setTimeout(rej, 1500); })
          ]);
          var m = txt && txt.match(/eyJ0eXAiOiJKV1QiLCJhbGciOiJFUzI1NiIsImtpZCI6IldlYlBsYXlLaWQifQ\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+/);
          if (m && m[0]) token = m[0];
        }
      } catch (e) {}

      var sf = 'br';
      try {
        var pm = location.pathname.match(/^\/([a-z]{2})(\/|$)/i);
        if (pm) sf = pm[1].toLowerCase();
      } catch (e) {}

      try {
        var ut = DEFAULT_USER_TOKEN;
        try { ut = localStorage.getItem('amtv.user.token') || DEFAULT_USER_TOKEN; } catch (e) {}
        var mk = await window.MusicKit.configure({
          developerToken: token,
          musicUserToken: ut,
          app: { name: 'Apple Music', version: '1.0', build: '2646.4.0-external' },
          bitrate: window.MusicKit.PlaybackBitrate ? window.MusicKit.PlaybackBitrate.HIGH : 256,
          disableLogoutURL: true,
          features: ['fetch-container-pages'],
          persist: 'cookie',
          storefrontId: sf,
          sourceType: 16
        });
        await syncUserToken(mk);
        await maybeInstallNativeWidevine(mk);
        return mk;
      } catch (e) {
        console.log('amtv configure err', e);
        try {
          var inst2 = window.MusicKit.getInstance();
          await syncUserToken(inst2);
          await maybeInstallNativeWidevine(inst2);
          return inst2;
        } catch (e2) { return null; }
      }
    })();
    return mkBooting;
  }

  var wvInstalled = false;
  async function maybeInstallNativeWidevine(mk) {
    if (wvInstalled || !mk || !window.AmtvNative || typeof window.AmtvNative.wvPlay !== 'function') return;
    // Check if WebView natively supports Widevine EME
    var hasNativeWv = false;
    try {
      if (navigator.requestMediaKeySystemAccess) {
        await navigator.requestMediaKeySystemAccess('com.widevine.alpha', [{
          initDataTypes: ['cenc'],
          audioCapabilities: [{ contentType: 'audio/mp4; codecs="mp4a.40.2"' }]
        }]);
        hasNativeWv = true;
      }
    } catch (e) {}
    if (hasNativeWv) return; // WebView supports Widevine natively (e.g. Chrome 83+), no native fallback needed

    wvInstalled = true;
    var mp = mk._services && mk._services.mediaItemPlayback;
    if (!mp || typeof mp._playAccordingToPlaybackType !== 'function') return;

    var wvActive = false, wvState = 0, wvPos = 0, wvDur = 0, wvItem = null, wvVol = 1, wvTimer = 0;

    function fire(ev, detail) {
      try {
        if (mk._services && mk._services.dispatcher && mk._services.dispatcher.publish) {
          mk._services.dispatcher.publish(ev, detail || {});
        }
      } catch (e) {}
    }

    function startPoll() {
      clearInterval(wvTimer);
      wvTimer = setInterval(function () {
        if (!wvActive) { clearInterval(wvTimer); return; }
        if (wvState === 2) {
          try {
            wvPos = window.AmtvNative.wvPos();
            var d = window.AmtvNative.wvDur();
            if (d > 0) wvDur = d;
          } catch (e) {}
          fire('playbackTimeDidChange', {
            currentPlaybackTime: wvPos,
            currentPlaybackDuration: wvDur,
            currentPlaybackTimeRemaining: Math.max(0, wvDur - wvPos)
          });
        }
      }, 400);
    }

    window.__amtvWvEvent = function (ev, pos, dur, detail) {
      if (!wvActive && ev !== 'loading') return;
      if (typeof pos === 'number' && isFinite(pos)) wvPos = pos;
      if (typeof dur === 'number' && dur > 0 && isFinite(dur)) wvDur = dur;
      if (ev === 'loading') {
        wvState = 1;
        fire('playbackStateDidChange', { state: 1 });
      } else if (ev === 'playing') {
        wvState = 2;
        fire('playbackStateDidChange', { state: 2 });
        fire('mediaCanPlay', {});
        fire('playbackTimeDidChange', {
          currentPlaybackTime: wvPos,
          currentPlaybackDuration: wvDur,
          currentPlaybackTimeRemaining: Math.max(0, wvDur - wvPos)
        });
        startPoll();
      } else if (ev === 'paused') {
        wvState = 3;
        fire('playbackStateDidChange', { state: 3 });
      } else if (ev === 'ended') {
        wvState = 5;
        clearInterval(wvTimer);
        if (mk.repeatMode === 1 && wvItem) {
          mp._playAccordingToPlaybackType(wvItem, true);
        } else {
          wvActive = false;
          try {
            var q = mk.queue;
            if (q && q.items && q.position + 1 < q.items.length) {
              mk.skipToNextItem();
            } else if (mk.repeatMode === 2 && q && q.items && q.items.length) {
              mk.changeToMediaAtIndex(0);
            } else {
              fire('playbackStateDidChange', { state: 5 });
            }
          } catch (e) {
            fire('playbackStateDidChange', { state: 5 });
          }
        }
      } else if (ev === 'error') {
        wvState = 4;
        wvActive = false;
        clearInterval(wvTimer);
        console.log('amtv wv error:', detail);
        fire('playbackStateDidChange', { state: 4 });
      }
    };

    var origPlayType = mp._playAccordingToPlaybackType.bind(mp);
    mp._playAccordingToPlaybackType = async function (item, userInit, opts) {
      if (item && mk.isAuthorized && !item.isLiveRadioStation && !item.rawAssetUrl) {
        try {
          if (mp._currentPlayer && mp._currentPlayer.audio) {
            try { mp._currentPlayer.audio.pause(); } catch (e) {}
          }
          await mp.prepareForEncryptedPlayback(item, { bitrate: 256 });
          var assets = item.assets || [];
          var ctrAsset = assets.find(function (a) { return a.flavor === '28:ctrp256'; })
            || assets.find(function (a) { return a.flavor && a.flavor.indexOf('ctr') >= 0; })
            || assets[0];
          var m3u8Url = (ctrAsset && ctrAsset.URL) || item.assetURL;
          var licUrl = item.keyURLs && item.keyURLs['hls-key-server-url'];
          if (m3u8Url && licUrl) {
            var durSec = (item.attributes && item.attributes.durationInMillis)
              ? (item.attributes.durationInMillis / 1000) : 0;
            wvActive = true;
            wvItem = item;
            wvState = 1;
            wvPos = (opts && opts.startTime) || 0;
            wvDur = durSec;
            if (mp._currentPlayer) {
              try { mp._currentPlayer._nowPlayingItem = item; } catch (e) {}
            }
            fire('nowPlayingItemDidChange', { item: item });
            fire('playbackStateDidChange', { state: 1 });
            window.AmtvNative.wvPlay(
              m3u8Url,
              licUrl,
              mk.developerToken || '',
              mk.musicUserToken || '',
              String(item.songId || item.id || ''),
              !!item.isCloudItem,
              durSec
            );
            startPoll();
            return item;
          }
        } catch (e) {
          console.log('amtv wv prepare err', e);
        }
      }
      wvActive = false;
      clearInterval(wvTimer);
      try { window.AmtvNative.wvStop(); } catch (e) {}
      return origPlayType(item, userInit, opts);
    };

    var mkProto = Object.getPrototypeOf(mk);
    var descState = Object.getOwnPropertyDescriptor(mkProto, 'playbackState');
    var descTime = Object.getOwnPropertyDescriptor(mkProto, 'currentPlaybackTime');
    var descDur = Object.getOwnPropertyDescriptor(mkProto, 'currentPlaybackDuration');
    var descNow = Object.getOwnPropertyDescriptor(mkProto, 'nowPlayingItem');
    var descVol = Object.getOwnPropertyDescriptor(mkProto, 'volume');
    var descIsPlaying = Object.getOwnPropertyDescriptor(mkProto, 'isPlaying');

    Object.defineProperty(mk, 'playbackState', {
      configurable: true,
      get: function () { return wvActive ? wvState : (descState && descState.get ? descState.get.call(mk) : 0); }
    });
    Object.defineProperty(mk, 'isPlaying', {
      configurable: true,
      get: function () { return wvActive ? (wvState === 2) : (descIsPlaying && descIsPlaying.get ? descIsPlaying.get.call(mk) : false); }
    });
    Object.defineProperty(mk, 'currentPlaybackTime', {
      configurable: true,
      get: function () {
        if (!wvActive) return descTime && descTime.get ? descTime.get.call(mk) : 0;
        try { return window.AmtvNative.wvPos(); } catch (e) { return wvPos; }
      }
    });
    Object.defineProperty(mk, 'currentPlaybackDuration', {
      configurable: true,
      get: function () {
        if (!wvActive) return descDur && descDur.get ? descDur.get.call(mk) : 0;
        try { var d = window.AmtvNative.wvDur(); return d > 0 ? d : wvDur; } catch (e) { return wvDur; }
      }
    });
    Object.defineProperty(mk, 'nowPlayingItem', {
      configurable: true,
      get: function () { return wvActive ? wvItem : (descNow && descNow.get ? descNow.get.call(mk) : null); }
    });
    Object.defineProperty(mk, 'volume', {
      configurable: true,
      get: function () { return wvActive ? wvVol : (descVol && descVol.get ? descVol.get.call(mk) : 1); },
      set: function (v) {
        wvVol = Math.max(0, Math.min(1, Number(v) || 0));
        try { window.AmtvNative.wvVolume(wvVol); } catch (e) {}
        if (descVol && descVol.set) { try { descVol.set.call(mk, wvVol); } catch (e) {} }
      }
    });

    var origPause = mk.pause.bind(mk);
    var origPlay = mk.play.bind(mk);
    var origStop = mk.stop.bind(mk);
    var origSeek = mk.seekToTime.bind(mk);

    mk.pause = async function () {
      if (wvActive) {
        wvState = 3;
        window.AmtvNative.wvPause();
        fire('playbackStateDidChange', { state: 3 });
        return;
      }
      return origPause();
    };
    mk.play = async function () {
      if (wvActive) {
        wvState = 2;
        window.AmtvNative.wvResume();
        fire('playbackStateDidChange', { state: 2 });
        return;
      }
      return origPlay();
    };
    mk.stop = async function () {
      if (wvActive) {
        wvActive = false;
        wvState = 4;
        clearInterval(wvTimer);
        window.AmtvNative.wvStop();
        fire('playbackStateDidChange', { state: 4 });
        return;
      }
      return origStop();
    };
    mk.seekToTime = async function (t) {
      if (wvActive) {
        wvPos = Math.max(0, Number(t) || 0);
        window.AmtvNative.wvSeek(wvPos);
        fire('playbackTimeDidChange', {
          currentPlaybackTime: wvPos,
          currentPlaybackDuration: wvDur,
          currentPlaybackTimeRemaining: Math.max(0, wvDur - wvPos)
        });
        return;
      }
      return origSeek(t);
    };
  }

  window.__amtv = {
    diag: diag,
    diagLog: function () { return diagLog.slice(); },
    ensureMusicKit: ensureMusicKit,
    syncUserToken: syncUserToken,
    wake: function (ms) {
      awakeUntil = Math.max(awakeUntil, performance.now() + (ms || 2500));
      var h = held;
      held = [];
      h.forEach(function (cb) { raf(cb); });
    }
  };

  window.requestAnimationFrame = function (cb) {
    if (performance.now() > awakeUntil && /\/scene[~.-]/.test(new Error().stack || '')) {
      held.push(cb);
      return 0;
    }
    return raf(cb);
  };

  ensureMusicKit().then(function (mk) {
    if (mk && mk.addEventListener) {
      mk.addEventListener('nowPlayingItemDidChange', function () { window.__amtv.wake(3000); });
    }
  });
})();
