// Apple Podcasts TV interface for FreeMyTV
// Mirrors Apple Music TV's dark glass & dynamic blurred-artwork aesthetic with Apple Podcasts purple accents.
// Tabs: Buscar, Início, Novidades, Top charts, Biblioteca + Tocando agora (no lyrics).
// Chrome 66+ compatible (no CSS inset, no flex gap, no ES2020 ?./??).
(function () {
  'use strict';
  if (window.__ptv) { try { window.__ptv.destroy(); } catch (e) {} }

  var native = window.AptvNative || {
    api: function () {},
    ready: function () {},
    exit: function () {},
    playing: function () {},
    keyboard: function () {},
    getVolume: function () { return 0.65; },
    setVolume: function () {}
  };

  var P = window.__ptv = { screens: {}, native: native };

  // ---------------------------------------------------------------- DOM helpers
  function h(tag, a) {
    var e = document.createElement(tag);
    if (typeof a === 'string') {
      e.className = a;
    } else if (a) {
      for (var k in a) {
        if (!Object.prototype.hasOwnProperty.call(a, k)) continue;
        var v = a[k];
        if (v == null || v === false) continue;
        if (k === 'class') e.className = v;
        else if (k === 'text') e.textContent = v;
        else if (k === 'style') e.style.cssText = v;
        else if (k.indexOf('on') === 0) e['_' + k] = v;
        else e.setAttribute(k, v === true ? '' : String(v));
      }
    }
    for (var i = 2; i < arguments.length; i++) appendKid(e, arguments[i]);
    return e;
  }
  function appendKid(e, c) {
    if (c == null || c === false) return;
    if (Array.isArray(c)) {
      for (var i = 0; i < c.length; i++) appendKid(e, c[i]);
    } else {
      e.appendChild(c.nodeType ? c : document.createTextNode(String(c)));
    }
  }

  function attrs(x) { return (x && x.attributes) || {}; }
  function artUrl(a, px, py) {
    if (!a || !a.url) return '';
    return a.url.replace('{w}', String(Math.round(px))).replace('{h}', String(Math.round(py || px))).replace('{f}', 'jpg');
  }
  function artColor(a) { return a && a.bgColor ? '#' + a.bgColor : '#1c1824'; }
  function palette(a) {
    if (!a) return '';
    return [a.bgColor, a.textColor4, a.textColor3, a.textColor2, a.textColor1].filter(Boolean).join(',');
  }
  function plain(s) { return String(s || '').replace(/<[^>]*>/g, '').replace(/\s+/g, ' ').trim(); }
  function imgEl(url, cls, w, hh) {
    if (!url) return h('div', (cls || '') + ' noart');
    var props = { class: cls || '', src: url, loading: 'lazy', decoding: 'async', alt: '' };
    if (w) props.width = w;
    if (hh) props.height = hh;
    return h('img', props);
  }
  function fmtDur(ms) {
    var total = Math.max(0, Math.round((ms || 0) / 1000));
    var hr = Math.floor(total / 3600);
    var mn = Math.floor((total % 3600) / 60);
    var sc = total % 60;
    if (hr > 0) return hr + ' h ' + (mn < 10 ? '0' : '') + mn + ' min';
    return Math.max(1, mn) + ' min';
  }
  function fmtClock(sec) {
    sec = Math.max(0, Math.floor(sec || 0));
    var hr = Math.floor(sec / 3600);
    var mn = Math.floor((sec % 3600) / 60);
    var sc = sec % 60;
    var mm = (hr > 0 && mn < 10 ? '0' : '') + mn;
    var ss = (sc < 10 ? '0' : '') + sc;
    return hr > 0 ? (hr + ':' + mm + ':' + ss) : (mm + ':' + ss);
  }
  function fmtDate(iso) {
    if (!iso) return '';
    try {
      var d = new Date(iso);
      if (isNaN(d.getTime())) return '';
      var now = Date.now();
      var diffDays = Math.floor((now - d.getTime()) / 86400000);
      if (diffDays <= 0) return 'Hoje';
      if (diffDays === 1) return 'Ontem';
      if (diffDays < 7) return diffDays + ' dias atrás';
      var months = ['jan.', 'fev.', 'mar.', 'abr.', 'mai.', 'jun.', 'jul.', 'ago.', 'set.', 'out.', 'nov.', 'dez.'];
      return d.getDate() + ' de ' + months[d.getMonth()];
    } catch (e) { return ''; }
  }
  function epDesc(ep) {
    var d = attrs(ep).description;
    if (!d) return '';
    return plain(typeof d === 'string' ? d : (d.short || d.standard || ''));
  }

  // ---------------------------------------------------------------- Icons (Apple Podcasts crisp SVGs)
  var ICONS = {
    search: '<path fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" d="M11 19a8 8 0 1 0 0-16 8 8 0 0 0 0 16zm9.5 1.5-4.35-4.35"/>',
    home: '<path fill="none" stroke="currentColor" stroke-width="1.9" stroke-linejoin="round" d="M4 10.6 12 4l8 6.6V19a1.5 1.5 0 0 1-1.5 1.5h-4.5v-5.5h-4v5.5H5.5A1.5 1.5 0 0 1 4 19v-8.4z"/>',
    homeFill: '<path d="M12 3.4 3.5 10.4V19a2 2 0 0 0 2 2h4.2v-5.6h4.6V21h4.2a2 2 0 0 0 2-2v-8.6L12 3.4z"/>',
    newGrid: '<rect x="3.5" y="3.5" width="7" height="7" rx="1.6" fill="none" stroke="currentColor" stroke-width="1.8"/><rect x="13.5" y="3.5" width="7" height="7" rx="1.6" fill="none" stroke="currentColor" stroke-width="1.8"/><rect x="3.5" y="13.5" width="7" height="7" rx="1.6" fill="none" stroke="currentColor" stroke-width="1.8"/><rect x="13.5" y="13.5" width="7" height="7" rx="1.6" fill="none" stroke="currentColor" stroke-width="1.8"/>',
    charts: '<path fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" d="M9 6h11M9 12h11M9 18h11"/><text x="3" y="8" font-size="6.2" font-weight="700" fill="currentColor" font-family="sans-serif">1</text><text x="3" y="14" font-size="6.2" font-weight="700" fill="currentColor" font-family="sans-serif">2</text><text x="3" y="20" font-size="6.2" font-weight="700" fill="currentColor" font-family="sans-serif">3</text>',
    library: '<path fill="none" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round" d="M4 7.5A2.5 2.5 0 0 1 6.5 5h11A2.5 2.5 0 0 1 20 7.5v11a2.5 2.5 0 0 1-2.5 2.5h-11A2.5 2.5 0 0 1 4 18.5v-11z"/><path fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" d="M8 9.5h8M8 13.5h8M8 17.5h5"/>',
    play: '<path d="M7.5 4.8c0-.9.98-1.45 1.75-.98l11.2 7.2c.72.46.72 1.5 0 1.96l-11.2 7.2c-.77.47-1.75-.08-1.75-.98V4.8z"/>',
    pause: '<rect x="6" y="4.5" width="4.2" height="15" rx="1.4"/><rect x="13.8" y="4.5" width="4.2" height="15" rx="1.4"/>',
    skipBack15: '<path fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" d="M12 5a7.5 7.5 0 1 1-6.9 4.5M8.2 5.4 12 2.4v5.6"/><text x="12" y="15.3" text-anchor="middle" font-size="7.4" font-weight="700" fill="currentColor" font-family="sans-serif">15</text>',
    skipFwd30: '<path fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" d="M12 5a7.5 7.5 0 1 0 6.9 4.5M15.8 5.4 12 2.4v5.6"/><text x="12" y="15.3" text-anchor="middle" font-size="7.4" font-weight="700" fill="currentColor" font-family="sans-serif">30</text>',
    next: '<path d="M4 6.2c0-.8.88-1.28 1.56-.84l7.84 5.3c.62.4.62 1.28 0 1.68l-7.84 5.3c-.68.44-1.56-.04-1.56-.84V6.2z"/><rect x="16.5" y="5.5" width="2.6" height="13" rx="1.1"/>',
    queue: '<path fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" d="M4 6.5h16M4 12h16M4 17.5h11"/>',
    vol: '<path d="M4 9.5v5h3.5l4.5 4V5.5l-4.5 4H4zm11.2 2.5a3.2 3.2 0 0 0-1.7-2.8v5.6a3.2 3.2 0 0 0 1.7-2.8z"/>',
    check: '<path fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" d="M5 12.5 9.8 17.3 19 7.5"/>',
    podcast: '<circle cx="12" cy="10.2" r="2.2"/><path d="M9.6 14.3c0-1.1.9-1.9 2.4-1.9s2.4.8 2.4 1.9l-.6 5.2c-.1.9-.8 1.5-1.8 1.5s-1.7-.6-1.8-1.5l-.6-5.2z"/><path fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" d="M6.8 15.6a6.4 6.4 0 1 1 10.4 0M4.2 17.8a9.6 9.6 0 1 1 15.6 0"/>'
  };

  function icon(name, size) {
    var s = h('span', 'ic');
    var el = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    el.setAttribute('viewBox', '0 0 24 24');
    el.setAttribute('width', String(size || 18));
    el.setAttribute('height', String(size || 18));
    el.setAttribute('fill', 'currentColor');
    el.setAttribute('aria-hidden', 'true');
    el.innerHTML = ICONS[name] || '';
    s.appendChild(el);
    return s;
  }

  // ---------------------------------------------------------------- Native API bridge + cache
  var reqSeq = 0;
  var pending = {};
  var apiCache = {};

  window.__ptvApiCb = function (id, ok, payload) {
    var entry = pending[id];
    if (!entry) return;
    delete pending[id];
    if (ok) {
      try { entry.resolve(JSON.parse(payload)); }
      catch (e) { entry.reject(new Error('JSON inválido')); }
    } else {
      entry.reject(new Error(payload || 'Erro de rede'));
    }
  };

  function api(path, ttlMs) {
    var ttl = ttlMs != null ? ttlMs : 5 * 60 * 1000;
    var hit = apiCache[path];
    if (hit && Date.now() - hit.t < ttl) return hit.p;
    var p = new Promise(function (resolve, reject) {
      var id = ++reqSeq;
      pending[id] = { resolve: resolve, reject: reject };
      try {
        native.api(id, path);
      } catch (e) {
        delete pending[id];
        reject(e);
      }
    });
    apiCache[path] = { t: Date.now(), p: p };
    p.catch(function () { delete apiCache[path]; });
    return p;
  }

  // ---------------------------------------------------------------- Progress & History persistence
  var PROG_KEY = 'aptv.progress.v1';
  var HIST_KEY = 'aptv.history.v1';
  function readJson(key, fallback) {
    try { var v = localStorage.getItem(key); return v ? JSON.parse(v) : fallback; }
    catch (e) { return fallback; }
  }
  function writeJson(key, val) {
    try { localStorage.setItem(key, JSON.stringify(val)); } catch (e) {}
  }
  var progressMap = readJson(PROG_KEY, {});
  var historyList = readJson(HIST_KEY, []);

  function saveProgress(ep, sec, dur) {
    if (!ep || !ep.id || !dur || dur < 10) return;
    var pct = Math.max(0, Math.min(1, sec / dur));
    progressMap[ep.id] = { sec: Math.floor(sec), dur: Math.floor(dur), pct: pct, at: Date.now() };
    writeJson(PROG_KEY, progressMap);
  }
  function recordHistory(ep) {
    if (!ep || !ep.id) return;
    var slim = {
      id: ep.id,
      type: 'podcast-episodes',
      attributes: {
        name: attrs(ep).name || '',
        artistName: attrs(ep).artistName || '',
        assetUrl: attrs(ep).assetUrl || '',
        artwork: attrs(ep).artwork || null,
        durationInMilliseconds: attrs(ep).durationInMilliseconds || 0,
        releaseDateTime: attrs(ep).releaseDateTime || '',
        description: attrs(ep).description || null,
        genreNames: attrs(ep).genreNames || []
      },
      _showId: ep._showId || ''
    };
    historyList = [slim].concat(historyList.filter(function (x) { return x && x.id !== ep.id; })).slice(0, 30);
    writeJson(HIST_KEY, historyList);
  }
  function getProgress(epId) { return progressMap[epId] || null; }

  // ---------------------------------------------------------------- Audio Engine (HTML5 <audio>)
  var audio = document.createElement('audio');
  audio.preload = 'auto';
  var SPEEDS = [1, 1.25, 1.5, 1.75, 2, 0.75];
  var player = {
    ep: null,
    queue: [],
    qIndex: -1,
    playing: false,
    speedIdx: 0,
    lastSave: 0,
    listeners: []
  };
  function emitPlayer() {
    for (var i = 0; i < player.listeners.length; i++) {
      try { player.listeners[i](); } catch (e) {}
    }
    updateEqState();
  }
  audio.addEventListener('play', function () {
    player.playing = true;
    try { native.playing(true); } catch (e) {}
    emitPlayer();
  });
  audio.addEventListener('pause', function () {
    player.playing = false;
    try { native.playing(false); } catch (e) {}
    if (player.ep && audio.duration) saveProgress(player.ep, audio.currentTime, audio.duration);
    emitPlayer();
  });
  audio.addEventListener('timeupdate', function () {
    var now = Date.now();
    if (player.ep && audio.duration && now - player.lastSave > 4000) {
      player.lastSave = now;
      saveProgress(player.ep, audio.currentTime, audio.duration);
    }
    emitPlayer();
  });
  audio.addEventListener('loadedmetadata', function () { emitPlayer(); });
  audio.addEventListener('ended', function () {
    if (player.ep && audio.duration) saveProgress(player.ep, audio.duration, audio.duration);
    if (player.qIndex >= 0 && player.qIndex + 1 < player.queue.length) {
      playEpisode(player.queue[player.qIndex + 1], player.queue, player.qIndex + 1, false);
    } else {
      player.playing = false;
      try { native.playing(false); } catch (e) {}
      emitPlayer();
    }
  });
  audio.addEventListener('error', function () {
    player.playing = false;
    try { native.playing(false); } catch (e) {}
    toast('Não foi possível reproduzir este episódio');
    emitPlayer();
  });

  function resolveEpisodeUrl(ep) {
    var a = attrs(ep);
    if (a.assetUrl) return Promise.resolve(a.assetUrl);
    if (a.offers && a.offers[0] && a.offers[0].hlsUrl) return Promise.resolve(a.offers[0].hlsUrl);
    return api('/v1/catalog/br/podcast-episodes/' + encodeURIComponent(ep.id) + '?l=pt-BR', 60000).then(function (r) {
      var full = r && r.data && r.data[0];
      var fa = attrs(full);
      if (fa.assetUrl) {
        ep.attributes = Object.assign({}, a, fa);
        return fa.assetUrl;
      }
      if (fa.offers && fa.offers[0] && fa.offers[0].hlsUrl) return fa.offers[0].hlsUrl;
      throw new Error('Áudio indisponível');
    });
  }

  function playEpisode(ep, queue, idx, openNowPlaying) {
    if (!ep) return;
    player.ep = ep;
    player.queue = Array.isArray(queue) && queue.length ? queue.slice() : [ep];
    player.qIndex = typeof idx === 'number' ? idx : 0;
    recordHistory(ep);
    emitPlayer();
    if (openNowPlaying !== false) openTab('now');
    resolveEpisodeUrl(ep).then(function (url) {
      if (player.ep !== ep) return;
      audio.src = url;
      audio.playbackRate = SPEEDS[player.speedIdx];
      var saved = getProgress(ep.id);
      var resumeSec = saved && saved.pct > 0.02 && saved.pct < 0.96 ? saved.sec : 0;
      if (resumeSec > 3) {
        var onMeta = function () {
          audio.removeEventListener('loadedmetadata', onMeta);
          try { audio.currentTime = resumeSec; } catch (e) {}
        };
        audio.addEventListener('loadedmetadata', onMeta);
      }
      var pr = audio.play();
      if (pr && pr.catch) pr.catch(function () {});
    }).catch(function () {
      toast('Áudio não disponível para este episódio');
    });
  }

  function togglePlay() {
    if (!player.ep) return;
    if (audio.paused) {
      var pr = audio.play();
      if (pr && pr.catch) pr.catch(function () {});
    } else {
      audio.pause();
    }
  }
  function seekBy(deltaSec) {
    if (!player.ep || isNaN(audio.currentTime)) return;
    var dur = audio.duration || ((attrs(player.ep).durationInMilliseconds || 0) / 1000);
    var next = Math.max(0, Math.min(dur || 99999, audio.currentTime + deltaSec));
    try { audio.currentTime = next; } catch (e) {}
    emitPlayer();
  }
  function cycleSpeed() {
    player.speedIdx = (player.speedIdx + 1) % SPEEDS.length;
    audio.playbackRate = SPEEDS[player.speedIdx];
    toast('Velocidade: ' + SPEEDS[player.speedIdx] + '×');
    emitPlayer();
  }

  P.media = function (cmd) {
    if (cmd === 'toggle') togglePlay();
    else if (cmd === 'play') { if (player.ep && audio.paused) audio.play(); }
    else if (cmd === 'pause') { if (!audio.paused) audio.pause(); }
    else if (cmd === 'fwd') seekBy(30);
    else if (cmd === 'rew') seekBy(-15);
    else if (cmd === 'next') {
      if (player.qIndex >= 0 && player.qIndex + 1 < player.queue.length) {
        playEpisode(player.queue[player.qIndex + 1], player.queue, player.qIndex + 1, false);
      }
    } else if (cmd === 'prev') {
      if (audio.currentTime > 8) seekBy(-audio.currentTime);
      else if (player.qIndex > 0) {
        playEpisode(player.queue[player.qIndex - 1], player.queue, player.qIndex - 1, false);
      }
    }
  };

  // ---------------------------------------------------------------- Shell & Shadow DOM
  var host = h('div', { id: 'aptv-root' });
  document.body.innerHTML = '';
  document.body.style.cssText = 'margin:0;padding:0;background:#0a0810;overflow:hidden;';
  document.body.appendChild(host);
  var root = host.attachShadow ? host.attachShadow({ mode: 'open' }) : host;

  var styleEl = h('style');
  root.appendChild(styleEl);

  var sky = h('div', 'ptv-sky');
  var bgA = h('canvas', { class: 'ptv-bg', width: 64, height: 36 });
  var bgB = h('canvas', { class: 'ptv-bg', width: 64, height: 36 });
  var veil = h('div', 'ptv-veil');
  sky.appendChild(bgA); sky.appendChild(bgB); sky.appendChild(veil);

  var nav = h('nav', { class: 'nav row', 'aria-label': 'Navegação do Apple Podcasts' });
  var stage = h('div', 'stage');
  var toastEl = h('div', { class: 'toast', role: 'status' });
  root.appendChild(sky);
  root.appendChild(nav);
  root.appendChild(stage);
  root.appendChild(toastEl);

  // ---------------------------------------------------------------- Styles (Apple Music TV parity in Podcasts Royal Violet)
  styleEl.textContent = [
    ':host { all: initial; }',
    '* { box-sizing: border-box; }',
    ':host, .stage { font-family: -apple-system, BlinkMacSystemFont, "SF Pro Display", Roboto, "Noto Sans", system-ui, sans-serif; color: #f4f2fa; -webkit-font-smoothing: antialiased; }',
    '.ic { display: inline-flex; width: 1em; height: 1em; flex: none; align-items: center; justify-content: center; }',
    '.ic svg { width: 100%; height: 100%; }',
    'img { display: block; }',
    '[hidden] { display: none !important; }',

    '.ptv-sky { position: fixed; top: 0; right: 0; bottom: 0; left: 0; z-index: 0; background: #120d1d; overflow: hidden; pointer-events: none; }',
    '.ptv-bg { position: absolute; left: -6%; top: -6%; width: 112%; height: 112%; opacity: 0; transition: opacity .75s ease; -webkit-filter: blur(26px) saturate(1.9) brightness(1.12); filter: blur(26px) saturate(1.9) brightness(1.12); }',
    '.ptv-bg.on { opacity: 1; }',
    '.ptv-veil { position: absolute; top: 0; right: 0; bottom: 0; left: 0; background: radial-gradient(circle at 50% 22%, rgba(168,85,247,.12), rgba(10,8,18,.32) 70%), linear-gradient(180deg, rgba(12,10,20,.26) 0%, rgba(12,10,20,.16) 45%, rgba(10,8,16,.48) 100%); }',

    '.nav { position: fixed; top: 0; left: 0; right: 0; height: 64px; z-index: 5; display: flex; justify-content: center; align-items: center; background: linear-gradient(180deg, rgba(10,8,18,.48), rgba(10,8,18,0)); transition: opacity .35s; }',
    '.nav.ghost { opacity: 0; pointer-events: none; }',
    '.nav.ghost.has-focus { opacity: 1; pointer-events: auto; }',
    '.pill { display: flex; padding: 6px 10px; border-radius: 999px; background: rgba(24,20,34,.72); border: 1px solid rgba(255,255,255,.14); box-shadow: 0 8px 24px rgba(0,0,0,.4); }',
    '.pill > * + * { margin-left: 8px; }',
    '.tab { padding: 8px 16px; border-radius: 999px; font-size: 13px; font-weight: 500; color: rgba(235,232,245,.66); white-space: nowrap; display: flex; align-items: center; transition: background-color .15s, color .15s, transform .15s; cursor: pointer; }',
    '.tab > * + * { margin-left: 6px; }',
    '.tab .ic { font-size: 15px; }',
    '.tab.sel { color: #fff; font-weight: 600; background: rgba(168,85,247,.22); }',
    '.tab.sel .ic { color: #c084fc; }',
    '.tab.focus { background: #fff; color: #0e0a16; transform: scale(1.06); font-weight: 600; }',
    '.tab.focus .ic { color: #7e22ce; }',
    '.eq { display: flex; align-items: flex-end; height: 11px; }',
    '.eq > * + * { margin-left: 2px; }',
    '.eq i { width: 2px; height: 100%; border-radius: 1px; background: #9b95aa; transform-origin: 50% 100%; }',
    '.playing .eq i { background: #c084fc; animation: ptveq .7s ease-in-out infinite alternate; }',
    '.playing .eq i:nth-child(2) { animation-delay: .18s; }',
    '.playing .eq i:nth-child(3) { animation-delay: .36s; }',
    '@keyframes ptveq { 0% { transform: scaleY(.28); } 100% { transform: scaleY(1); } }',

    '.stage { position: fixed; top: 0; right: 0; bottom: 0; left: 0; z-index: 1; }',
    '.screen { position: absolute; top: 64px; right: 0; bottom: 0; left: 0; overflow: hidden; background: transparent; }',
    '.screen.full { top: 0; }',
    '.page { position: relative; padding: 20px 0 96px; transition: transform .24s ease-out; will-change: transform; }',

    'h1 { font-size: 26px; font-weight: 700; letter-spacing: -.4px; margin: 2px 48px 16px; text-shadow: 0 2px 12px rgba(0,0,0,.5); text-wrap: balance; }',
    'h2 { font-size: 18px; font-weight: 600; margin: 0 48px 4px; color: #fff; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; text-shadow: 0 2px 10px rgba(0,0,0,.55); }',
    '.shelf { margin-bottom: 18px; }',
    '.vp { overflow: hidden; padding: 12px 0 10px; }',
    '.track { position: relative; display: flex; padding: 0 48px; transition: transform .22s ease-out; }',
    '.track > * + * { margin-left: 16px; }',

    '.card { flex: none; width: var(--w, 146px); transition: transform .15s ease-out; }',
    '.card .art { position: relative; width: var(--w, 146px); height: var(--h, var(--w, 146px)); border-radius: 12px; overflow: hidden; background: #1e1928; box-shadow: 0 6px 18px rgba(0,0,0,.4); }',
    '.card .art img, .card .art .noart { width: 100%; height: 100%; object-fit: cover; }',
    '.card .t1 { margin-top: 8px; font-size: 12.5px; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; text-shadow: 0 1px 6px rgba(0,0,0,.6); }',
    '.card .t2 { margin-top: 2px; font-size: 11px; color: rgba(235,232,245,.74); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; text-shadow: 0 1px 6px rgba(0,0,0,.55); }',
    '.card.focus { transform: scale(1.09); }',
    '.card.focus .art { box-shadow: 0 12px 26px rgba(0,0,0,.68); border: 2px solid rgba(255,255,255,.88); }',
    '.card .rank { position: absolute; left: 7px; top: 7px; font-size: 10.5px; font-weight: 700; background: rgba(16,12,26,.84); color: #fff; padding: 2px 7px; border-radius: 6px; border: 1px solid rgba(255,255,255,.18); font-variant-numeric: tabular-nums; }',
    '.card .pbar { position: absolute; left: 0; right: 0; bottom: 0; height: 4px; background: rgba(0,0,0,.55); }',
    '.card .pbar i { display: block; height: 100%; background: #c084fc; }',
    '.noart { background: linear-gradient(135deg, #3b2264, #1e1630); }',

    '.spot { flex: none; width: 300px; transition: transform .15s ease-out; }',
    '.spot .lb { height: 54px; overflow: hidden; }',
    '.spot .bd { font-size: 10px; font-weight: 700; letter-spacing: 1.1px; color: #c084fc; text-transform: uppercase; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }',
    '.spot .tt { font-size: 15px; font-weight: 700; margin-top: 2px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }',
    '.spot .st { font-size: 12px; color: rgba(235,232,245,.72); margin-top: 2px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }',
    '.spot .art { position: relative; width: 300px; height: 168px; border-radius: 11px; overflow: hidden; background: #1e1928; margin-top: 6px; box-shadow: 0 8px 20px rgba(0,0,0,.45); }',
    '.spot .art img { width: 100%; height: 100%; object-fit: cover; }',
    '.spot .blurb { position: absolute; left: 0; right: 0; bottom: 0; padding: 28px 12px 9px; font-size: 11px; line-height: 1.35; background: linear-gradient(transparent, rgba(10,8,18,.88)); display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }',
    '.spot.focus { transform: scale(1.05); }',
    '.spot.focus .art { outline: 2.5px solid #fff; outline-offset: 2px; }',

    '.chips { display: flex; padding: 0 48px 10px; flex-wrap: wrap; }',
    '.chips > * { margin-right: 10px; margin-bottom: 8px; }',
    '.chip { display: inline-flex; align-items: center; padding: 8px 16px; border-radius: 20px; background: rgba(255,255,255,.11); font-size: 13px; color: rgba(244,242,250,.88); transition: transform .15s, background-color .15s, color .15s; }',
    '.chip.sel { background: rgba(168,85,247,.36); color: #fff; font-weight: 600; border: 1px solid rgba(192,132,252,.5); }',
    '.chip.focus { background: #fff; color: #0e0a16; transform: scale(1.06); font-weight: 600; }',

    '.list { padding: 4px 36px; }',
    '.list .item { display: flex; align-items: center; padding: 10px 14px; border-radius: 10px; font-size: 14px; transition: background-color .12s, transform .12s; }',
    '.list .item > * + * { margin-left: 14px; }',
    '.list .item.focus { background: #f4f2fa; color: #0e0a16; }',
    '.list .item.focus .b, .list .item.focus .d, .list .item.focus .n { color: #524c60; }',
    '.item .n { width: 26px; text-align: right; color: rgba(235,232,245,.55); font-size: 13px; font-weight: 600; flex: none; font-variant-numeric: tabular-nums; }',
    '.item .th { width: 46px; height: 46px; border-radius: 8px; flex: none; object-fit: cover; background: #1e1928; }',
    '.item .tx { flex: 1; min-width: 0; }',
    '.item .a { font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }',
    '.item .b { font-size: 11.5px; color: rgba(235,232,245,.68); margin-top: 3px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }',
    '.item .d { color: rgba(235,232,245,.62); font-size: 12px; flex: none; font-variant-numeric: tabular-nums; }',
    '.item.now .a { color: #c084fc; }',
    '.item.focus.now .a { color: #7e22ce; }',
    '.item .ep-prog { margin-top: 5px; width: 120px; height: 3px; border-radius: 2px; background: rgba(255,255,255,.18); overflow: hidden; }',
    '.item.focus .ep-prog { background: rgba(0,0,0,.15); }',
    '.item .ep-prog i { display: block; height: 100%; background: #a855f7; }',

    '.detail .side { position: absolute; left: 48px; top: 24px; width: 236px; }',
    '.detail .side .cover { width: 236px; height: 236px; border-radius: 14px; overflow: hidden; background: #1e1928; box-shadow: 0 14px 36px rgba(0,0,0,.5); }',
    '.detail .side .cover img { width: 100%; height: 100%; object-fit: cover; }',
    '.detail .side .nm { font-size: 20px; font-weight: 700; margin-top: 14px; line-height: 1.24; display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden; text-wrap: balance; }',
    '.detail .side .by { font-size: 14px; color: #c084fc; font-weight: 600; margin-top: 4px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }',
    '.detail .side .meta { font-size: 11.5px; color: rgba(235,232,245,.65); margin-top: 6px; }',
    '.detail .side .nt { font-size: 11.5px; color: rgba(235,232,245,.72); margin-top: 10px; line-height: 1.45; display: -webkit-box; -webkit-line-clamp: 5; -webkit-box-orient: vertical; overflow: hidden; }',
    '.detail .page { margin-left: 310px; padding-right: 36px; }',
    '.detail .list { padding: 6px 0; }',

    '.btns { display: flex; padding: 6px 0 12px; }',
    '.btns > * + * { margin-left: 12px; }',
    '.btn { display: inline-flex; align-items: center; padding: 10px 20px; border-radius: 12px; background: rgba(255,255,255,.13); font-size: 13.5px; font-weight: 600; color: #fff; transition: transform .15s, background-color .15s, color .15s; }',
    '.btn > * + * { margin-left: 8px; }',
    '.btn.purple { background: linear-gradient(135deg, #a855f7, #7e22ce); }',
    '.btn.focus { transform: scale(1.06); background: #fff; color: #0e0a16; box-shadow: 0 8px 20px rgba(0,0,0,.5); }',

    '.sbox { margin: 0 48px 16px; width: 520px; height: 46px; border-radius: 12px; background: rgba(28,24,40,.85); border: 1px solid rgba(255,255,255,.14); display: flex; align-items: center; padding: 0 16px; font-size: 15px; color: #9a94aa; transition: transform .15s, box-shadow .15s; }',
    '.sbox > * + * { margin-left: 10px; }',
    '.sbox.focus { transform: scale(1.03); box-shadow: 0 0 0 2px #fff; }',
    '.sbox input { all: unset; flex: 1; color: #fff; font-size: 15px; caret-color: #c084fc; }',

    '.cat-grid { display: flex; flex-wrap: wrap; padding: 6px 48px; }',
    '.cat-card { width: 196px; height: 84px; border-radius: 12px; padding: 14px 16px; margin: 0 14px 14px 0; display: flex; align-items: flex-end; font-size: 14.5px; font-weight: 700; color: #fff; box-shadow: 0 6px 18px rgba(0,0,0,.35); transition: transform .15s; text-shadow: 0 1px 6px rgba(0,0,0,.4); }',
    '.cat-card.focus { transform: scale(1.07); outline: 2.5px solid #fff; outline-offset: 2px; }',

    // Fullscreen Now Playing (NO LYRICS — centered Apple TV layout, optional Up Next queue split)
    '.np { background: #100c18; }',
    '.np .bg { position: absolute; top: 0; right: 0; bottom: 0; left: 0; width: 100%; height: 100%; opacity: 0; transition: opacity .9s ease; }',
    '.np .bg.on { opacity: 1; }',
    '.np .veil { position: absolute; top: 0; right: 0; bottom: 0; left: 0; background: radial-gradient(circle at 30% 45%, rgba(168,85,247,0.08), rgba(0,0,0,0.36) 70%, rgba(0,0,0,0.56)); }',
    '.np .page { position: absolute; top: 0; right: 0; bottom: 0; left: 0; padding: 0; transition: none; }',
    '.np .left { position: absolute; top: 0; bottom: 0; left: calc(50% - 166px); width: 332px; display: flex; flex-direction: column; justify-content: center; align-items: stretch; transition: left .38s cubic-bezier(.22,1,.36,1), width .38s cubic-bezier(.22,1,.36,1); }',
    '.np.showq .left { left: 84px; width: 296px; }',
    '.np .cover { position: relative; width: 332px; height: 332px; border-radius: 14px; overflow: hidden; background: #1e1928; border: 1px solid rgba(255,255,255,0.14); box-shadow: 0 22px 58px rgba(0,0,0,0.55); transition: width .38s ease, height .38s ease; }',
    '.np.showq .cover { width: 296px; height: 296px; }',
    '.np .cover img { position: absolute; top: 0; right: 0; bottom: 0; left: 0; width: 100%; height: 100%; object-fit: cover; }',
    '.np .hdr { display: flex; align-items: center; justify-content: space-between; margin-top: 14px; width: 100%; }',
    '.np .meta-tx { flex: 1; min-width: 0; margin-right: 10px; }',
    '.np .ti { font-size: 15px; font-weight: 700; color: #fff; line-height: 1.25; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }',
    '.np .ar { margin-top: 3px; font-size: 13px; font-weight: 500; color: rgba(255,255,255,0.72); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }',
    '.np .meta-acts { display: flex; align-items: center; flex: none; }',
    '.np .meta-acts > * + * { margin-left: 8px; }',
    '.np .cbtn { height: 28px; min-width: 28px; padding: 0 8px; border-radius: 999px; background: rgba(255,255,255,0.16); color: #fff; font-size: 11.5px; font-weight: 700; display: flex; align-items: center; justify-content: center; transition: transform .15s, background-color .15s, box-shadow .15s; font-variant-numeric: tabular-nums; }',
    '.np .cbtn.on { background: rgba(168,85,247,0.65); }',
    '.np .cbtn.focus { background: #fff; color: #0e0a16; transform: scale(1.14); box-shadow: 0 0 0 2px #fff; }',

    '.np .pr { margin-top: 12px; padding: 6px 0 2px; width: 100%; }',
    '.np .bar-wrap { position: relative; height: 12px; display: flex; align-items: center; }',
    '.np .bar { position: relative; width: 100%; height: 3.5px; border-radius: 2px; background: rgba(255,255,255,0.26); transition: height .15s; }',
    '.np .pr.focus .bar { height: 5.5px; }',
    '.np .fill { position: absolute; top: 0; left: 0; bottom: 0; width: 100%; border-radius: 2px; background: #fff; transform-origin: 0 50%; transform: scaleX(0); }',
    '.np .knob { position: absolute; top: 50%; left: 0; width: 10px; height: 10px; margin-left: -5px; margin-top: -5px; border-radius: 50%; background: #fff; box-shadow: 0 1px 4px rgba(0,0,0,0.45); transition: transform .15s; }',
    '.np .pr.focus .knob { transform: scale(1.38); box-shadow: 0 0 0 3px rgba(192,132,252,0.55); }',
    '.np .tm { display: flex; justify-content: space-between; font-size: 10.5px; color: rgba(255,255,255,0.62); margin-top: 4px; font-variant-numeric: tabular-nums; }',

    '.np .tr { display: flex; justify-content: center; align-items: center; margin-top: 8px; width: 100%; }',
    '.np .tr > * + * { margin-left: 16px; }',
    '.np .tb { width: 42px; height: 42px; border-radius: 50%; display: flex; align-items: center; justify-content: center; color: #fff; transition: transform .15s, background-color .15s, box-shadow .15s; }',
    '.np .tb.big { width: 52px; height: 52px; background: rgba(255,255,255,0.14); }',
    '.np .tb.sm { width: 34px; height: 34px; color: rgba(255,255,255,0.78); font-size: 12px; font-weight: 700; font-variant-numeric: tabular-nums; }',
    '.np .tb.focus { background: rgba(255,255,255,0.26); transform: scale(1.14); box-shadow: 0 0 0 2px rgba(255,255,255,0.9); }',

    '.np .vol { display: flex; align-items: center; margin-top: 12px; padding: 4px 0; width: 100%; }',
    '.np .vol-ic { color: rgba(255,255,255,0.74); flex: none; margin-right: 8px; display: flex; align-items: center; }',
    '.np .vol-bar-wrap { position: relative; flex: 1; height: 10px; display: flex; align-items: center; }',
    '.np .vol-bar { position: relative; width: 100%; height: 3px; border-radius: 2px; background: rgba(255,255,255,0.26); transition: height .15s; }',
    '.np .vol.focus .vol-bar { height: 5px; }',
    '.np .vol-fill { position: absolute; top: 0; left: 0; bottom: 0; width: 100%; border-radius: 2px; background: #fff; transform-origin: 0 50%; transform: scaleX(0.65); }',
    '.np .vol-knob { position: absolute; top: 50%; left: 65%; width: 9px; height: 9px; margin-left: -4.5px; margin-top: -4.5px; border-radius: 50%; background: #fff; transition: transform .15s; }',
    '.np .vol.focus .vol-knob { transform: scale(1.38); box-shadow: 0 0 0 3px rgba(192,132,252,0.5); }',

    '.np .right { position: absolute; left: 432px; right: 56px; top: 82px; bottom: 48px; overflow: hidden; display: none; }',
    '.np.showq .right { display: block; }',
    '.np .right h3 { font-size: 13px; color: rgba(255,255,255,0.62); font-weight: 700; letter-spacing: .8px; text-transform: uppercase; margin: 0 0 12px 14px; }',
    '.np .empty { position: absolute; top: 0; right: 0; bottom: 0; left: 0; display: flex; flex-direction: column; align-items: center; justify-content: center; color: rgba(255,255,255,0.68); font-size: 16px; }',
    '.np .empty .ic { font-size: 46px; color: #a855f7; margin-bottom: 14px; opacity: .85; }',

    '.msg { margin: 56px 48px; color: rgba(235,232,245,.66); font-size: 15.5px; }',
    '.skel { display: flex; padding: 14px 48px; }',
    '.skel > * + * { margin-left: 16px; }',
    '.skel div { width: 146px; height: 146px; border-radius: 12px; background: rgba(255,255,255,.07); flex: none; }',
    '.toast { position: fixed; left: 50%; bottom: 32px; transform: translate(-50%, 20px); opacity: 0; z-index: 20; background: rgba(34,28,48,.94); border: 1px solid rgba(255,255,255,.15); padding: 11px 22px; border-radius: 12px; font-size: 14px; transition: opacity .2s, transform .2s; max-width: 72%; pointer-events: none; }',
    '.toast.on { opacity: 1; transform: translate(-50%, 0); }',
    '@media (prefers-reduced-motion: reduce) { .ptv-bg, .page, .track, .card, .np .left, .np .cover { transition: none !important; } }'
  ].join('\n');

  // ---------------------------------------------------------------- Dynamic blurred artwork sky
  var bgFront = bgA, bgForKey = '', bgTimer = 0;
  function vividHex(hex, minLum) {
    hex = String(hex || '').replace('#', '').trim();
    if (!/^[0-9a-fA-F]{6}$/.test(hex)) return null;
    var r = parseInt(hex.slice(0, 2), 16) / 255;
    var g = parseInt(hex.slice(2, 4), 16) / 255;
    var b = parseInt(hex.slice(4, 6), 16) / 255;
    var max = Math.max(r, g, b), min = Math.min(r, g, b);
    var hh = 0, s = 0, l = (max + min) / 2;
    if (max !== min) {
      var d = max - min;
      s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
      if (max === r) hh = (g - b) / d + (g < b ? 6 : 0);
      else if (max === g) hh = (b - r) / d + 2;
      else hh = (r - g) / d + 4;
      hh /= 6;
    }
    s = Math.min(0.9, s < 0.08 ? s : Math.max(s * 1.3, 0.44));
    l = Math.max(minLum || 0.34, Math.min(0.6, l < 0.24 ? l + 0.24 : l * 1.08));
    var hue2rgb = function (p, q, t) {
      if (t < 0) t += 1;
      if (t > 1) t -= 1;
      if (t < 1 / 6) return p + (q - p) * 6 * t;
      if (t < 1 / 2) return q;
      if (t < 2 / 3) return p + (q - p) * (2 / 3 - t) * 6;
      return p;
    };
    var q = l < 0.5 ? l * (1 + s) : l + s - l * s;
    var p = 2 * l - q;
    return 'rgb(' + Math.round(hue2rgb(p, q, hh + 1 / 3) * 255) + ',' + Math.round(hue2rgb(p, q, hh) * 255) + ',' + Math.round(hue2rgb(p, q, hh - 1 / 3) * 255) + ')';
  }
  function paintCanvas(c, palStr, img) {
    var raw = String(palStr || '').split(',').map(function (x) { return x.trim(); }).filter(Boolean);
    var c1 = vividHex(raw[0], 0.38) || '#5a2d82';
    var c2 = vividHex(raw[1] || raw[2], 0.32) || '#351c5c';
    var c3 = vividHex(raw[2] || raw[3] || raw[0], 0.26) || '#1c1230';
    var g1 = c.createLinearGradient(0, 0, 64, 36);
    g1.addColorStop(0, c1); g1.addColorStop(0.55, c2); g1.addColorStop(1, c3);
    c.fillStyle = g1; c.fillRect(0, 0, 64, 36);
    if (img) {
      c.save(); c.globalAlpha = 0.72;
      c.drawImage(img, 0, 0, img.width, img.height, -6, -6, 76, 48);
      c.restore();
    }
  }
  function setBackdrop(url, palStr) {
    var key = (url || '') + '|' + (palStr || '');
    if (!key || key === bgForKey) return;
    bgForKey = key;
    if (!url) {
      var back = bgFront === bgA ? bgB : bgA;
      paintCanvas(back.getContext('2d'), palStr, null);
      back.classList.add('on'); bgFront.classList.remove('on'); bgFront = back;
      return;
    }
    var im = new Image();
    im.onload = function () {
      if (bgForKey !== key) return;
      var back = bgFront === bgA ? bgB : bgA;
      paintCanvas(back.getContext('2d'), palStr, im);
      back.classList.add('on'); bgFront.classList.remove('on'); bgFront = back;
    };
    im.onerror = function () {
      if (bgForKey !== key || !palStr) return;
      var back = bgFront === bgA ? bgB : bgA;
      paintCanvas(back.getContext('2d'), palStr, null);
      back.classList.add('on'); bgFront.classList.remove('on'); bgFront = back;
    };
    im.src = url;
  }
  function followBackdrop(el) {
    clearTimeout(bgTimer);
    bgTimer = setTimeout(function () {
      var url = '', pal = '';
      if (el && el.dataset) {
        url = el.dataset.art || '';
        pal = el.dataset.pal || '';
      }
      if (!url && el && el.querySelector) {
        var im = el.querySelector('img');
        if (im && im.src) url = im.src;
      }
      if (!url && player.ep) {
        var a = attrs(player.ep).artwork;
        if (a) { url = artUrl(a, 180); pal = palette(a); }
      }
      if (url || pal) setBackdrop(url, pal);
    }, 100);
  }

  var toastT = 0;
  function toast(msg) {
    toastEl.textContent = msg;
    toastEl.classList.add('on');
    clearTimeout(toastT);
    toastT = setTimeout(function () { toastEl.classList.remove('on'); }, 2400);
  }

  // ---------------------------------------------------------------- Top Nav (exact 5 tabs + Tocando agora)
  var TABS = [
    { id: 'search', label: 'Buscar', ic: 'search' },
    { id: 'home', label: 'Início', ic: 'homeFill' },
    { id: 'new', label: 'Novidades', ic: 'newGrid' },
    { id: 'charts', label: 'Top charts', ic: 'charts' },
    { id: 'library', label: 'Biblioteca', ic: 'library' },
    { id: 'now', label: 'Tocando agora', eq: true }
  ];
  var pill = h('div', 'pill');
  var tabEls = {};
  for (var ti = 0; ti < TABS.length; ti++) {
    (function (tb) {
      var el = h('div', {
        class: 'tab f',
        role: 'tab',
        'aria-label': tb.label,
        onok: function () { openTab(tb.id); }
      },
        tb.eq ? h('span', 'eq', h('i'), h('i'), h('i')) : icon(tb.ic, 15),
        h('span', null, tb.label)
      );
      tabEls[tb.id] = el;
      pill.appendChild(el);
    })(TABS[ti]);
  }
  nav.appendChild(pill);

  function updateEqState() {
    if (tabEls.now) tabEls.now.classList.toggle('playing', !!player.playing);
  }

  // ---------------------------------------------------------------- Focus & Spatial D-pad Navigation
  var cur = null;
  var stack = [];
  var currentTab = 'home';
  var navGhostTimer = 0;

  function activeScreen() { return stack.length ? stack[stack.length - 1] : null; }
  function inNav(el) { return !!(el && nav.contains(el)); }

  function focusEl(el) {
    if (!el) return;
    if (cur) cur.classList.remove('focus');
    cur = el;
    cur.classList.add('focus');
    var onNav = inNav(cur);
    nav.classList.toggle('has-focus', onNav);
    var s = activeScreen();
    if (s && s.full) {
      nav.classList.toggle('ghost', !onNav);
      if (onNav) clearTimeout(navGhostTimer);
    } else {
      nav.classList.remove('ghost');
    }
    if (!onNav && s) {
      s.lastFocus = cur;
      scrollIntoView(s, cur);
      followBackdrop(cur);
    }
    if (cur._onfocus) { try { cur._onfocus(cur); } catch (e) {} }
  }

  function scrollIntoView(s, el) {
    if (!s || !s.page || s.full) return;
    // Horizontal track scroll
    var track = el.closest('.track');
    if (track) {
      var vp = track.parentElement;
      var left = el.offsetLeft;
      var w = el.offsetWidth;
      var vpW = vp.clientWidth || 960;
      var maxTx = Math.max(0, track.scrollWidth - vpW + 48);
      var tx = Math.max(0, Math.min(maxTx, left - 48));
      if (left + w < vpW - 48) tx = 0;
      track.style.transform = 'translate3d(' + (-tx) + 'px,0,0)';
    }
    // Vertical page scroll
    var r = el.getBoundingClientRect();
    var sr = s.el.getBoundingClientRect();
    var topPad = 42, botPad = 56;
    var off = s.off || 0;
    if (r.top < sr.top + topPad) {
      off = Math.max(0, off - (sr.top + topPad - r.top));
    } else if (r.bottom > sr.bottom - botPad) {
      off = off + (r.bottom - (sr.bottom - botPad));
    }
    var maxOff = Math.max(0, s.page.scrollHeight - (sr.height || 500) + 40);
    off = Math.max(0, Math.min(maxOff, off));
    s.off = off;
    s.page.style.transform = 'translate3d(0,' + (-off) + 'px,0)';
  }

  function rowsOf(s) {
    if (!s) return [];
    var rows = [].slice.call(s.page.querySelectorAll('.row'));
    return rows.filter(function (r) { return r.querySelector('.f') && r.offsetParent !== null; });
  }
  function focusablesIn(container) {
    return [].slice.call(container.querySelectorAll('.f')).filter(function (x) { return x.offsetParent !== null; });
  }
  function nearestByX(list, refEl) {
    if (!list.length) return null;
    if (!refEl) return list[0];
    var rr = refEl.getBoundingClientRect();
    var cx = rr.left + rr.width / 2;
    var best = list[0], bestD = Infinity;
    for (var i = 0; i < list.length; i++) {
      var r = list[i].getBoundingClientRect();
      var d = Math.abs((r.left + r.width / 2) - cx);
      if (d < bestD) { bestD = d; best = list[i]; }
    }
    return best;
  }

  function move(dir) {
    var s = activeScreen();
    if (!s) return false;
    if (cur && cur._onkey && cur._onkey(dir)) return true;

    if (inNav(cur)) {
      var tabs = focusablesIn(nav);
      var idx = tabs.indexOf(cur);
      if (dir === 'left' && idx > 0) { focusEl(tabs[idx - 1]); return true; }
      if (dir === 'right' && idx < tabs.length - 1) { focusEl(tabs[idx + 1]); return true; }
      if (dir === 'down') {
        var target = (s.lastFocus && s.page.contains(s.lastFocus) && s.lastFocus.offsetParent !== null)
          ? s.lastFocus : s.page.querySelector('.f');
        if (target) { focusEl(target); return true; }
      }
      return true;
    }

    var rows = rowsOf(s);
    var curRow = cur ? cur.closest('.row') : null;
    var rIdx = rows.indexOf(curRow);
    if (rIdx < 0) {
      var first = s.page.querySelector('.f');
      if (first) { focusEl(first); return true; }
      focusEl(tabEls[currentTab] || tabEls.home);
      return true;
    }

    var items = focusablesIn(curRow);
    var iIdx = items.indexOf(cur);

    if (dir === 'left') {
      if (iIdx > 0) { focusEl(items[iIdx - 1]); return true; }
      return true;
    }
    if (dir === 'right') {
      if (iIdx >= 0 && iIdx < items.length - 1) { focusEl(items[iIdx + 1]); return true; }
      return true;
    }
    if (dir === 'up') {
      if (rIdx > 0) {
        var prevRow = rows[rIdx - 1];
        focusEl(nearestByX(focusablesIn(prevRow), cur));
        return true;
      }
      focusEl(tabEls[currentTab] || tabEls.home);
      return true;
    }
    if (dir === 'down') {
      if (rIdx < rows.length - 1) {
        var nextRow = rows[rIdx + 1];
        focusEl(nearestByX(focusablesIn(nextRow), cur));
        return true;
      }
      return true;
    }
    return false;
  }

  P.key = function (k) {
    if (k === 'up' || k === 'down' || k === 'left' || k === 'right') return move(k);
    if (k === 'ok') {
      if (cur && cur._onok) { cur._onok(cur); return true; }
      return false;
    }
    if (k === 'back') {
      var s = activeScreen();
      if (s && s.onback && s.onback()) return true;
      if (stack.length > 1) {
        popScreen();
        return true;
      }
      if (currentTab !== 'home') {
        openTab('home');
        return true;
      }
      if (!inNav(cur) && tabEls[currentTab]) {
        focusEl(tabEls[currentTab]);
        return true;
      }
      return false; // let Java exit PodcastsView back to HomeView
    }
    return false;
  };

  // ---------------------------------------------------------------- Screen stack
  var firstReadySent = false;
  function notifyReady() {
    if (firstReadySent) return;
    firstReadySent = true;
    try { native.ready(); } catch (e) {}
  }

  function makeScreen(name, opts) {
    var full = !!(opts && opts.full);
    var cls = 'screen' + (full ? ' full' : '') + (opts && opts.cls ? ' ' + opts.cls : '');
    var page = h('div', 'page');
    var el = h('div', cls, page);
    return { name: name, el: el, page: page, off: 0, full: full, lastFocus: null, cleanup: null };
  }

  function clearStack() {
    while (stack.length) {
      var old = stack.pop();
      if (old.cleanup) { try { old.cleanup(); } catch (e) {} }
      if (old.el.parentNode) old.el.parentNode.removeChild(old.el);
    }
  }

  function openTab(tabId) {
    currentTab = tabId;
    for (var k in tabEls) {
      if (Object.prototype.hasOwnProperty.call(tabEls, k)) {
        tabEls[k].classList.toggle('sel', k === tabId);
      }
    }
    clearStack();
    var isNow = tabId === 'now';
    var s = makeScreen(tabId, { full: isNow, cls: isNow ? 'np' : '' });
    stack.push(s);
    stage.appendChild(s.el);
    nav.classList.toggle('ghost', isNow);
    if (P.screens[tabId]) P.screens[tabId](s);
  }

  function pushScreen(name, params, opts) {
    var prev = activeScreen();
    if (prev) prev.el.style.display = 'none';
    var s = makeScreen(name, opts);
    stack.push(s);
    stage.appendChild(s.el);
    nav.classList.remove('ghost');
    if (P.screens[name]) P.screens[name](s, params);
  }

  function popScreen() {
    if (stack.length <= 1) return;
    var old = stack.pop();
    if (old.cleanup) { try { old.cleanup(); } catch (e) {} }
    if (old.el.parentNode) old.el.parentNode.removeChild(old.el);
    var prev = activeScreen();
    if (prev) {
      prev.el.style.display = '';
      nav.classList.toggle('ghost', !!prev.full);
      focusEl(prev.lastFocus || prev.page.querySelector('.f') || tabEls[currentTab]);
    }
  }

  function showSkeleton(s) {
    s.page.innerHTML = '';
    s.page.appendChild(h('div', 'skel', h('div'), h('div'), h('div'), h('div'), h('div')));
  }

  function finishScreen(s, preferContent) {
    notifyReady();
    var first = s.page.querySelector('.f');
    if (preferContent && first) focusEl(first);
    else if (!cur || !inNav(cur)) focusEl(first || tabEls[currentTab]);
    if (first) followBackdrop(first);
  }

  // ---------------------------------------------------------------- UI Builders
  function showCard(show, opts) {
    var a = attrs(show);
    var w = (opts && opts.w) || 146;
    var img = artUrl(a.artwork, w * 2);
    var rank = opts && opts.rank;
    return h('div', {
      class: 'card f',
      style: '--w:' + w + 'px',
      'data-art': artUrl(a.artwork, 180),
      'data-pal': palette(a.artwork),
      onok: function () { pushScreen('show', { show: show }, { cls: 'detail' }); }
    },
      h('div', { class: 'art', style: 'background:' + artColor(a.artwork) },
        imgEl(img, '', w, w),
        rank ? h('span', 'rank', '#' + rank) : null
      ),
      h('div', 't1', a.name || ''),
      h('div', 't2', a.artistName || (a.genreNames && a.genreNames[0]) || '')
    );
  }

  function episodeCard(ep, queue, idx, opts) {
    var a = attrs(ep);
    var w = (opts && opts.w) || 156;
    var img = artUrl(a.artwork, w * 2);
    var prog = getProgress(ep.id);
    var sub = [fmtDate(a.releaseDateTime), fmtDur(a.durationInMilliseconds)].filter(Boolean).join(' · ');
    return h('div', {
      class: 'card f',
      style: '--w:' + w + 'px',
      'data-art': artUrl(a.artwork, 180),
      'data-pal': palette(a.artwork),
      onok: function () { playEpisode(ep, queue, idx, true); }
    },
      h('div', { class: 'art', style: 'background:' + artColor(a.artwork) },
        imgEl(img, '', w, w),
        opts && opts.rank ? h('span', 'rank', '#' + opts.rank) : null,
        prog && prog.pct > 0.02 ? h('div', 'pbar', h('i', { style: 'width:' + Math.round(prog.pct * 100) + '%' })) : null
      ),
      h('div', 't1', a.name || ''),
      h('div', 't2', a.artistName ? (a.artistName + (sub ? ' · ' + sub : '')) : sub)
    );
  }

  function shelf(title, cards) {
    if (!cards || !cards.length) return null;
    return h('section', 'shelf row',
      title ? h('h2', null, title) : null,
      h('div', 'vp', h('div', 'track', cards))
    );
  }

  function episodeRowItem(ep, queue, idx, opts) {
    var a = attrs(ep);
    var isNow = player.ep && player.ep.id === ep.id;
    var prog = getProgress(ep.id);
    var meta = [fmtDate(a.releaseDateTime), a.artistName, epDesc(ep)].filter(Boolean).join(' · ');
    return h('div', {
      class: 'item row f' + (isNow ? ' now' : ''),
      'data-art': artUrl(a.artwork, 180),
      'data-pal': palette(a.artwork),
      onok: function () { playEpisode(ep, queue, idx, true); }
    },
      opts && opts.num ? h('span', 'n', String(opts.num)) : null,
      opts && opts.thumb !== false ? imgEl(artUrl(a.artwork, 96), 'th', 46, 46) : null,
      h('div', 'tx',
        h('div', 'a', a.name || ''),
        h('div', 'b', meta),
        prog && prog.pct > 0.02 ? h('div', 'ep-prog', h('i', { style: 'width:' + Math.round(prog.pct * 100) + '%' })) : null
      ),
      h('span', 'd', fmtDur(a.durationInMilliseconds))
    );
  }

  // ---------------------------------------------------------------- Helper: fetch user's followed shows + latest episodes
  function loadLibraryShows() {
    return api('/v1/me/library/podcasts?limit=25&l=pt-BR', 2 * 60 * 1000).then(function (r) {
      return (r && r.data) || [];
    }).catch(function () { return []; });
  }
  function loadLibraryEpisodes() {
    return api('/v1/me/library/podcast-episodes?limit=25&l=pt-BR', 2 * 60 * 1000).then(function (r) {
      return (r && r.data) || [];
    }).catch(function () { return []; });
  }
  function loadLatestFromShows(shows) {
    if (!shows || !shows.length) return Promise.resolve([]);
    var topShows = shows.slice(0, 8);
    return Promise.all(topShows.map(function (sh) {
      return api('/v1/catalog/br/podcasts/' + encodeURIComponent(sh.id) + '/episodes?limit=4&l=pt-BR', 5 * 60 * 1000)
        .then(function (r) {
          var eps = (r && r.data) || [];
          for (var i = 0; i < eps.length; i++) eps[i]._showId = sh.id;
          return eps;
        }).catch(function () { return []; });
    })).then(function (lists) {
      var all = [];
      for (var i = 0; i < lists.length; i++) all = all.concat(lists[i]);
      all.sort(function (a, b) {
        var da = new Date(attrs(a).releaseDateTime || 0).getTime();
        var db = new Date(attrs(b).releaseDateTime || 0).getTime();
        return db - da;
      });
      return all.slice(0, 20);
    });
  }

  // ---------------------------------------------------------------- SCREEN: INÍCIO (Home)
  P.screens.home = function (s) {
    showSkeleton(s);
    Promise.all([
      loadLibraryShows(),
      api('/v1/catalog/br/charts?types=podcasts,podcast-episodes&limit=18&l=pt-BR', 5 * 60 * 1000).catch(function () { return {}; })
    ]).then(function (res) {
      var myShows = res[0] || [];
      var charts = (res[1] && res[1].results) || {};
      var topShows = (charts.podcasts && charts.podcasts[0] && charts.podcasts[0].data) || [];
      var topEps = (charts['podcast-episodes'] && charts['podcast-episodes'][0] && charts['podcast-episodes'][0].data) || [];

      return loadLatestFromShows(myShows).then(function (latestEps) {
        var nodes = [];
        // 1. Continuar ouvindo (local resume)
        var inProg = historyList.filter(function (ep) {
          var p = getProgress(ep.id);
          return p && p.pct > 0.02 && p.pct < 0.96;
        });
        if (inProg.length) {
          nodes.push(shelf('Continuar ouvindo', inProg.map(function (ep, i) {
            return episodeCard(ep, inProg, i, { w: 168 });
          })));
        }
        // 2. Novos episódios dos seus programas
        if (latestEps.length) {
          nodes.push(shelf('A seguir · Novos episódios', latestEps.map(function (ep, i) {
            return episodeCard(ep, latestEps, i, { w: 162 });
          })));
        }
        // 3. Seus programas seguidos
        if (myShows.length) {
          nodes.push(shelf('Seus programas', myShows.map(function (sh) {
            return showCard(sh, { w: 150 });
          })));
        }
        // 4. Episódios em alta
        if (topEps.length) {
          nodes.push(shelf('Episódios em alta no Brasil', topEps.map(function (ep, i) {
            return episodeCard(ep, topEps, i, { w: 150 });
          })));
        }
        // 5. Programas populares
        if (topShows.length) {
          nodes.push(shelf('Programas populares', topShows.map(function (sh) {
            return showCard(sh, { w: 146 });
          })));
        }
        s.page.innerHTML = '';
        if (!nodes.length) s.page.appendChild(h('div', 'msg', 'Nenhum podcast carregado. Verifique sua conexão.'));
        else for (var i = 0; i < nodes.length; i++) if (nodes[i]) s.page.appendChild(nodes[i]);
        finishScreen(s, true);
      });
    });
  };

  // ---------------------------------------------------------------- SCREEN: NOVIDADES (New)
  var FEATURED_GENRES = [
    { id: '1488', name: 'Crimes verídicos' },
    { id: '1303', name: 'Comédia' },
    { id: '1324', name: 'Sociedade e cultura' },
    { id: '1489', name: 'Notícias' },
    { id: '1318', name: 'Tecnologia' }
  ];

  P.screens['new'] = function (s) {
    showSkeleton(s);
    Promise.all([
      api('/v1/catalog/br/charts?types=podcasts,podcast-episodes&limit=12&l=pt-BR', 5 * 60 * 1000).catch(function () { return {}; })
    ].concat(FEATURED_GENRES.map(function (g) {
      return api('/v1/catalog/br/charts?types=podcasts&genre=' + g.id + '&limit=12&l=pt-BR', 10 * 60 * 1000).catch(function () { return {}; });
    }))).then(function (all) {
      var mainRes = (all[0] && all[0].results) || {};
      var mainShows = (mainRes.podcasts && mainRes.podcasts[0] && mainRes.podcasts[0].data) || [];
      var mainEps = (mainRes['podcast-episodes'] && mainRes['podcast-episodes'][0] && mainRes['podcast-episodes'][0].data) || [];

      var nodes = [h('h1', null, 'Novidades')];

      // Editorial-style spotlight row
      if (mainShows.length) {
        var spots = mainShows.slice(0, 6).map(function (sh) {
          var a = attrs(sh);
          return h('div', {
            class: 'spot f',
            'data-art': artUrl(a.artwork, 200),
            'data-pal': palette(a.artwork),
            onok: function () { pushScreen('show', { show: sh }, { cls: 'detail' }); }
          },
            h('div', 'lb',
              h('div', 'bd', (a.genreNames && a.genreNames[0]) || 'EM DESTAQUE'),
              h('div', 'tt', a.name || ''),
              h('div', 'st', a.artistName || '')
            ),
            h('div', { class: 'art', style: 'background:' + artColor(a.artwork) },
              imgEl(artUrl(a.artwork, 600, 340), '', 300, 168),
              epDesc(sh) ? h('div', 'blurb', epDesc(sh)) : null
            )
          );
        });
        nodes.push(shelf('Destaques', spots));
      }

      if (mainEps.length) {
        nodes.push(shelf('Episódios novos em destaque', mainEps.map(function (ep, i) {
          return episodeCard(ep, mainEps, i, { w: 152 });
        })));
      }

      for (var gi = 0; gi < FEATURED_GENRES.length; gi++) {
        var gRes = (all[gi + 1] && all[gi + 1].results) || {};
        var gShows = (gRes.podcasts && gRes.podcasts[0] && gRes.podcasts[0].data) || [];
        if (gShows.length) {
          nodes.push(shelf(FEATURED_GENRES[gi].name, gShows.map(function (sh) {
            return showCard(sh, { w: 146 });
          })));
        }
      }

      s.page.innerHTML = '';
      for (var i = 0; i < nodes.length; i++) if (nodes[i]) s.page.appendChild(nodes[i]);
      finishScreen(s, false);
    });
  };

  // ---------------------------------------------------------------- SCREEN: TOP CHARTS
  var CHART_CATS = [
    { id: '', name: 'Todos' },
    { id: '1488', name: 'Crimes verídicos' },
    { id: '1303', name: 'Comédia' },
    { id: '1489', name: 'Notícias' },
    { id: '1324', name: 'Sociedade e cultura' },
    { id: '1321', name: 'Negócios' },
    { id: '1318', name: 'Tecnologia' },
    { id: '1545', name: 'Esportes' },
    { id: '1512', name: 'Saúde e fitness' }
  ];

  P.screens.charts = function (s) {
    var selGenre = '';
    var body = h('div');
    var chipRow = h('div', 'chips row');

    function renderChips() {
      chipRow.innerHTML = '';
      for (var i = 0; i < CHART_CATS.length; i++) {
        (function (cat) {
          chipRow.appendChild(h('div', {
            class: 'chip f' + (selGenre === cat.id ? ' sel' : ''),
            onok: function () {
              selGenre = cat.id;
              renderChips();
              loadCharts();
            }
          }, cat.name));
        })(CHART_CATS[i]);
      }
    }

    function loadCharts() {
      body.innerHTML = '';
      body.appendChild(h('div', 'skel', h('div'), h('div'), h('div'), h('div'), h('div')));
      var path = '/v1/catalog/br/charts?types=podcasts,podcast-episodes&limit=24&l=pt-BR' + (selGenre ? '&genre=' + selGenre : '');
      api(path, 5 * 60 * 1000).then(function (r) {
        var res = (r && r.results) || {};
        var shows = (res.podcasts && res.podcasts[0] && res.podcasts[0].data) || [];
        var eps = (res['podcast-episodes'] && res['podcast-episodes'][0] && res['podcast-episodes'][0].data) || [];
        body.innerHTML = '';
        if (shows.length) {
          body.appendChild(shelf('Top programas', shows.map(function (sh, i) {
            return showCard(sh, { w: 150, rank: i + 1 });
          })));
        }
        if (eps.length) {
          body.appendChild(h('h2', { style: 'margin: 8px 48px 10px;' }, 'Top episódios'));
          var list = h('div', 'list');
          for (var i = 0; i < eps.length; i++) {
            list.appendChild(episodeRowItem(eps[i], eps, i, { num: i + 1 }));
          }
          body.appendChild(list);
        }
        notifyReady();
      }).catch(function () {
        body.innerHTML = '';
        body.appendChild(h('div', 'msg', 'Não foi possível carregar o Top charts.'));
        notifyReady();
      });
    }

    s.page.innerHTML = '';
    s.page.appendChild(h('h1', null, 'Top charts'));
    s.page.appendChild(chipRow);
    s.page.appendChild(body);
    renderChips();
    loadCharts();
  };

  // ---------------------------------------------------------------- SCREEN: BIBLIOTECA (Library)
  P.screens.library = function (s) {
    var mode = 'shows';
    var chipRow = h('div', 'chips row');
    var body = h('div');
    var LIB_MODES = [
      { id: 'shows', label: 'Programas' },
      { id: 'latest', label: 'Mais recentes' },
      { id: 'saved', label: 'Salvos' },
      { id: 'history', label: 'Histórico' }
    ];

    function renderChips() {
      chipRow.innerHTML = '';
      for (var i = 0; i < LIB_MODES.length; i++) {
        (function (m) {
          chipRow.appendChild(h('div', {
            class: 'chip f' + (mode === m.id ? ' sel' : ''),
            onok: function () { mode = m.id; renderChips(); loadLib(); }
          }, m.label));
        })(LIB_MODES[i]);
      }
    }

    function loadLib() {
      body.innerHTML = '';
      if (mode === 'history') {
        if (!historyList.length) {
          body.appendChild(h('div', 'msg', 'Os episódios que você ouvir aparecerão aqui.'));
          return;
        }
        var hList = h('div', 'list');
        for (var i = 0; i < historyList.length; i++) hList.appendChild(episodeRowItem(historyList[i], historyList, i, {}));
        body.appendChild(hList);
        return;
      }
      body.appendChild(h('div', 'skel', h('div'), h('div'), h('div'), h('div')));
      if (mode === 'shows') {
        loadLibraryShows().then(function (shows) {
          body.innerHTML = '';
          if (!shows.length) {
            body.appendChild(h('div', 'msg', 'Nenhum programa seguido encontrado na sua biblioteca.'));
            return;
          }
          for (var r = 0; r < shows.length; r += 5) {
            var chunk = shows.slice(r, r + 5);
            body.appendChild(shelf(r === 0 ? 'Seus programas (' + shows.length + ')' : '', chunk.map(function (sh) {
              return showCard(sh, { w: 158 });
            })));
          }
        });
      } else if (mode === 'saved') {
        loadLibraryEpisodes().then(function (eps) {
          body.innerHTML = '';
          if (!eps.length) {
            body.appendChild(h('div', 'msg', 'Nenhum episódio salvo na sua biblioteca.'));
            return;
          }
          var list = h('div', 'list');
          for (var i = 0; i < eps.length; i++) list.appendChild(episodeRowItem(eps[i], eps, i, {}));
          body.appendChild(list);
        });
      } else if (mode === 'latest') {
        loadLibraryShows().then(loadLatestFromShows).then(function (eps) {
          body.innerHTML = '';
          if (!eps.length) {
            body.appendChild(h('div', 'msg', 'Nenhum episódio recente encontrado.'));
            return;
          }
          var list = h('div', 'list');
          for (var i = 0; i < eps.length; i++) list.appendChild(episodeRowItem(eps[i], eps, i, {}));
          body.appendChild(list);
        });
      }
    }

    s.page.innerHTML = '';
    s.page.appendChild(h('h1', null, 'Biblioteca'));
    s.page.appendChild(chipRow);
    s.page.appendChild(body);
    renderChips();
    loadLib();
    notifyReady();
  };

  // ---------------------------------------------------------------- SCREEN: BUSCAR (Search)
  var CAT_COLORS = [
    'linear-gradient(135deg,#7e22ce,#4c1d95)',
    'linear-gradient(135deg,#db2777,#831843)',
    'linear-gradient(135deg,#2563eb,#1e3a8a)',
    'linear-gradient(135deg,#059669,#064e3b)',
    'linear-gradient(135deg,#d97706,#78350f)',
    'linear-gradient(135deg,#dc2626,#7f1d1d)',
    'linear-gradient(135deg,#0891b2,#164e63)',
    'linear-gradient(135deg,#9333ea,#581c87)'
  ];

  P.screens.search = function (s) {
    var inp = h('input', { type: 'text', placeholder: 'Buscar programas ou episódios…', 'aria-label': 'Buscar podcasts' });
    var box = h('div', {
      class: 'sbox row f',
      onok: function () {
        inp.focus();
        try { native.keyboard(true); } catch (e) {}
      }
    }, icon('search', 17), inp);
    var resWrap = h('div');
    var deb = 0;

    function renderBrowseCats() {
      resWrap.innerHTML = '';
      resWrap.appendChild(h('h2', { style: 'margin: 6px 48px 10px;' }, 'Explorar categorias'));
      var cats = CHART_CATS.slice(1);
      for (var r = 0; r < cats.length; r += 4) {
        var row = h('div', 'cat-grid row');
        for (var j = r; j < Math.min(r + 4, cats.length); j++) {
          (function (cat, idx) {
            row.appendChild(h('div', {
              class: 'cat-card f',
              style: 'background:' + CAT_COLORS[idx % CAT_COLORS.length],
              onok: function () {
                inp.value = cat.name;
                doSearch(cat.name);
              }
            }, cat.name));
          })(cats[j], j);
        }
        resWrap.appendChild(row);
      }
    }

    function doSearch(term) {
      term = String(term || '').trim();
      if (!term) { renderBrowseCats(); return; }
      resWrap.innerHTML = '';
      resWrap.appendChild(h('div', 'skel', h('div'), h('div'), h('div'), h('div')));
      api('/v1/catalog/br/search?term=' + encodeURIComponent(term) + '&types=podcasts,podcast-episodes&limit=18&l=pt-BR', 60 * 1000)
        .then(function (r) {
          var sr = (r && r.results) || {};
          var shows = (sr.podcasts && sr.podcasts.data) || [];
          var eps = (sr['podcast-episodes'] && sr['podcast-episodes'].data) || [];
          resWrap.innerHTML = '';
          if (shows.length) {
            resWrap.appendChild(shelf('Programas', shows.map(function (sh) {
              return showCard(sh, { w: 146 });
            })));
          }
          if (eps.length) {
            resWrap.appendChild(h('h2', { style: 'margin: 8px 48px 8px;' }, 'Episódios'));
            var list = h('div', 'list');
            for (var i = 0; i < eps.length; i++) list.appendChild(episodeRowItem(eps[i], eps, i, {}));
            resWrap.appendChild(list);
          }
          if (!shows.length && !eps.length) {
            resWrap.appendChild(h('div', 'msg', 'Nenhum resultado para “' + term + '”.'));
          }
        }).catch(function () {
          resWrap.innerHTML = '';
          resWrap.appendChild(h('div', 'msg', 'Erro ao buscar. Tente novamente.'));
        });
    }

    inp.addEventListener('input', function () {
      clearTimeout(deb);
      deb = setTimeout(function () { doSearch(inp.value); }, 380);
    });
    inp.addEventListener('keydown', function (e) {
      if (e.key === 'Enter') {
        clearTimeout(deb);
        doSearch(inp.value);
        inp.blur();
        try { native.keyboard(false); } catch (err) {}
      }
    });

    s.page.innerHTML = '';
    s.page.appendChild(h('h1', null, 'Buscar'));
    s.page.appendChild(box);
    s.page.appendChild(resWrap);
    renderBrowseCats();
    finishScreen(s, true);
  };

  // ---------------------------------------------------------------- SCREEN: DETALHES DO PROGRAMA (Show Detail)
  P.screens.show = function (s, params) {
    var show = params && params.show;
    if (!show) { popScreen(); return; }
    var a = attrs(show);
    setBackdrop(artUrl(a.artwork, 200), palette(a.artwork));

    var side = h('div', 'side',
      h('div', 'cover', imgEl(artUrl(a.artwork, 460), '', 236, 236)),
      h('div', 'nm', a.name || ''),
      h('div', 'by', a.artistName || ''),
      h('div', 'meta', (a.genreNames && a.genreNames.slice(0, 2).join(' · ')) || 'Podcast'),
      h('div', 'nt', epDesc(show))
    );
    s.el.insertBefore(side, s.page);

    var epWrap = h('div');
    var playLatestBtn = h('div', {
      class: 'btn purple f',
      onok: function () {
        if (loadedEps.length) playEpisode(loadedEps[0], loadedEps, 0, true);
      }
    }, icon('play', 16), 'Reproduzir mais recente');

    var loadedEps = [];
    s.page.innerHTML = '';
    s.page.appendChild(h('div', 'btns row', playLatestBtn));
    s.page.appendChild(epWrap);
    epWrap.appendChild(h('div', 'msg', 'Carregando episódios…'));
    focusEl(playLatestBtn);

    api('/v1/catalog/br/podcasts/' + encodeURIComponent(show.id) + '/episodes?limit=40&l=pt-BR', 3 * 60 * 1000)
      .then(function (r) {
        loadedEps = (r && r.data) || [];
        epWrap.innerHTML = '';
        if (!loadedEps.length) {
          epWrap.appendChild(h('div', 'msg', 'Nenhum episódio disponível.'));
          return;
        }
        var list = h('div', 'list');
        for (var i = 0; i < loadedEps.length; i++) {
          loadedEps[i]._showId = show.id;
          list.appendChild(episodeRowItem(loadedEps[i], loadedEps, i, { thumb: false }));
        }
        epWrap.appendChild(list);
      }).catch(function () {
        epWrap.innerHTML = '';
        epWrap.appendChild(h('div', 'msg', 'Não foi possível carregar os episódios.'));
      });
  };

  // ---------------------------------------------------------------- SCREEN: TOCANDO AGORA (Fullscreen, NO LYRICS!)
  P.screens.now = function (s) {
    var showQueue = false;
    var bg1 = h('canvas', { class: 'bg on', width: 64, height: 36 });
    var veilEl = h('div', 'veil');
    s.el.insertBefore(veilEl, s.page);
    s.el.insertBefore(bg1, veilEl);

    if (!player.ep) {
      s.page.innerHTML = '';
      s.page.appendChild(h('div', 'empty',
        icon('podcast', 48),
        h('div', null, 'Nenhum episódio tocando agora'),
        h('div', { style: 'font-size:13px;color:rgba(255,255,255,.48);margin-top:6px;' }, 'Escolha um episódio em Início, Novidades, Top charts ou Biblioteca.')
      ));
      focusEl(tabEls.now);
      notifyReady();
      return;
    }

    var coverImg = h('img', { alt: '', width: 332, height: 332 });
    var titleEl = h('div', 'ti');
    var showEl = h('div', 'ar');
    var speedChip = h('div', {
      class: 'cbtn f',
      'aria-label': 'Velocidade de reprodução',
      onok: cycleSpeed
    }, SPEEDS[player.speedIdx] + '×');
    var queueBtn = h('div', {
      class: 'cbtn f',
      'aria-label': 'Fila de episódios',
      onok: function () {
        showQueue = !showQueue;
        s.el.classList.toggle('showq', showQueue);
        queueBtn.classList.toggle('on', showQueue);
      }
    }, icon('queue', 14));

    var fillEl = h('div', 'fill');
    var knobEl = h('div', 'knob');
    var elapsedEl = h('span', null, '0:00');
    var remEl = h('span', null, '-0:00');
    var prRow = h('div', {
      class: 'pr row f',
      'aria-label': 'Barra de progresso',
      onkey: function (dir) {
        if (dir === 'left') { seekBy(-15); return true; }
        if (dir === 'right') { seekBy(15); return true; }
        return false;
      },
      onok: togglePlay
    },
      h('div', 'bar-wrap', h('div', 'bar', fillEl, knobEl)),
      h('div', 'tm', elapsedEl, remEl)
    );

    var speedBtn = h('div', { class: 'tb sm f', 'aria-label': 'Velocidade', onok: cycleSpeed }, SPEEDS[player.speedIdx] + '×');
    var rewBtn = h('div', { class: 'tb f', 'aria-label': 'Voltar 15 segundos', onok: function () { seekBy(-15); } }, icon('skipBack15', 24));
    var playBtn = h('div', { class: 'tb big f', 'aria-label': 'Reproduzir ou pausar', onok: togglePlay }, icon('play', 26));
    var fwdBtn = h('div', { class: 'tb f', 'aria-label': 'Avançar 30 segundos', onok: function () { seekBy(30); } }, icon('skipFwd30', 24));
    var nextBtn = h('div', {
      class: 'tb sm f',
      'aria-label': 'Próximo episódio',
      onok: function () { P.media('next'); }
    }, icon('next', 20));
    var trRow = h('div', 'tr row', speedBtn, rewBtn, playBtn, fwdBtn, nextBtn);

    var volVal = 0.65;
    try { volVal = Number(native.getVolume()); if (isNaN(volVal)) volVal = 0.65; } catch (e) {}
    var volFill = h('div', 'vol-fill');
    var volKnob = h('div', 'vol-knob');
    function syncVolUi() {
      volFill.style.transform = 'scaleX(' + volVal.toFixed(3) + ')';
      volKnob.style.left = Math.round(volVal * 100) + '%';
    }
    syncVolUi();
    var volRow = h('div', {
      class: 'vol row f',
      'aria-label': 'Volume',
      onkey: function (dir) {
        if (dir === 'left' || dir === 'right') {
          volVal = Math.max(0, Math.min(1, volVal + (dir === 'right' ? 0.08 : -0.08)));
          syncVolUi();
          try { native.setVolume(volVal); } catch (e) {}
          return true;
        }
        return false;
      }
    },
      h('span', 'vol-ic', icon('vol', 16)),
      h('div', 'vol-bar-wrap', h('div', 'vol-bar', volFill, volKnob))
    );

    var leftCol = h('div', 'left',
      h('div', 'cover', coverImg),
      h('div', 'hdr row',
        h('div', 'meta-tx', titleEl, showEl),
        h('div', 'meta-acts', speedChip, queueBtn)
      ),
      prRow,
      trRow,
      volRow
    );

    var qList = h('div', 'list');
    var rightCol = h('div', 'right',
      h('h3', null, 'A seguir na fila'),
      qList
    );

    s.page.innerHTML = '';
    s.page.appendChild(leftCol);
    s.page.appendChild(rightCol);

    var lastEpId = '';
    function syncNow() {
      var ep = player.ep;
      if (!ep) return;
      var a = attrs(ep);
      if (ep.id !== lastEpId) {
        lastEpId = ep.id;
        titleEl.textContent = a.name || '';
        showEl.textContent = a.artistName || '';
        var cu = artUrl(a.artwork, 660);
        if (cu) coverImg.src = cu;
        var im = new Image();
        im.onload = function () { paintCanvas(bg1.getContext('2d'), palette(a.artwork), im); };
        im.onerror = function () { paintCanvas(bg1.getContext('2d'), palette(a.artwork), null); };
        if (cu) im.src = artUrl(a.artwork, 160);
        else paintCanvas(bg1.getContext('2d'), palette(a.artwork), null);

        // Render queue
        qList.innerHTML = '';
        for (var i = Math.max(0, player.qIndex); i < Math.min(player.queue.length, player.qIndex + 8); i++) {
          qList.appendChild(episodeRowItem(player.queue[i], player.queue, i, {}));
        }
      }
      var dur = (audio.duration && !isNaN(audio.duration)) ? audio.duration : ((a.durationInMilliseconds || 0) / 1000);
      var pos = audio.currentTime || 0;
      var pct = dur > 0 ? Math.max(0, Math.min(1, pos / dur)) : 0;
      fillEl.style.transform = 'scaleX(' + pct.toFixed(4) + ')';
      knobEl.style.left = (pct * 100).toFixed(2) + '%';
      elapsedEl.textContent = fmtClock(pos);
      remEl.textContent = '-' + fmtClock(Math.max(0, dur - pos));
      playBtn.innerHTML = '';
      playBtn.appendChild(icon(player.playing ? 'pause' : 'play', 26));
      var spTxt = SPEEDS[player.speedIdx] + '×';
      speedChip.textContent = spTxt;
      speedBtn.textContent = spTxt;
    }

    player.listeners.push(syncNow);
    s.cleanup = function () {
      var idx = player.listeners.indexOf(syncNow);
      if (idx >= 0) player.listeners.splice(idx, 1);
    };
    syncNow();
    focusEl(playBtn);
    notifyReady();
  };

  // ---------------------------------------------------------------- Boot on "Início"
  P.boot = function () {
    openTab('home');
  };
  P.destroy = function () {
    clearStack();
    try { audio.pause(); audio.src = ''; } catch (e) {}
  };
  P.boot();
})();
