// Now Playing ("Tocando Agora"): faithful Apple Music fullscreen player
// - Centered mode (no lyrics) & Split mode (synced lyrics on the right)
// - Header with Title, "Artist — Album", circular Favorite star & More (...) glass buttons
// - Anchored translucent popover menu (...) with all 11 Portuguese actions & right-aligned icons
// - White progress bar with circular knob, elapsed time on left, negative remaining time on right
// - Clean 5-button white transport row (shuffle, prev, play/pause, next, repeat)
// - Interactive volume slider with circular knob + bottom-right lyrics toggle button
// - Chrome 66+ compatible (no CSS inset, no flex gap, no ES2020 ?./??)
(function () {
  'use strict';
  const T = window.__tv;
  if (!T) return;
  const h = T.h;
  const LEAD_MS = 200;
  const LYRICS_PREF = 'amtv.lyrics';

  // Crisp inline SVGs matching Apple Music fullscreen player iconography
  function svg(paths, w, hh) {
    const el = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    el.setAttribute('viewBox', '0 0 24 24');
    el.setAttribute('width', String(w || 20));
    el.setAttribute('height', String(hh || w || 20));
    el.setAttribute('fill', 'currentColor');
    el.setAttribute('aria-hidden', 'true');
    el.innerHTML = paths;
    return el;
  }

  const ICONS = {
    play: '<path d="M7.5 4.8c0-.9.98-1.45 1.75-.98l11.2 7.2c.72.46.72 1.5 0 1.96l-11.2 7.2c-.77.47-1.75-.08-1.75-.98V4.8z"/>',
    pause: '<rect x="6" y="4.5" width="4.2" height="15" rx="1.4"/><rect x="13.8" y="4.5" width="4.2" height="15" rx="1.4"/>',
    prev: '<path d="M12 6.2c0-.8-.88-1.28-1.56-.84L2.6 10.66c-.62.4-.62 1.28 0 1.68l7.84 5.3c.68.44 1.56-.04 1.56-.84V6.2zm9.6 0c0-.8-.88-1.28-1.56-.84l-7.84 5.3c-.62.4-.62 1.28 0 1.68l7.84 5.3c.68.44 1.56-.04 1.56-.84V6.2z"/>',
    next: '<path d="M2.4 6.2c0-.8.88-1.28 1.56-.84l7.84 5.3c.62.4.62 1.28 0 1.68l-7.84 5.3c-.68.44-1.56-.04-1.56-.84V6.2zm9.6 0c0-.8.88-1.28 1.56-.84l7.84 5.3c.62.4.62 1.28 0 1.68l-7.84 5.3c-.68.44-1.56-.04-1.56-.84V6.2z"/>',
    shuffle: '<path fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" d="M16 4h4v4M4 20l6-6m4-4l6-6M16 20h4v-4M4 4l16 16"/>',
    repeat: '<path fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" d="M17 2l3 3-3 3M3 11V9a4 4 0 0 1 4-4h13M7 22l-3-3 3-3M21 13v2a4 4 0 0 1-4 4H4"/>',
    star: '<path fill="none" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round" d="M12 3.3l2.6 5.3 5.8.8-4.2 4.1 1 5.8L12 16.5l-5.2 2.8 1-5.8-4.2-4.1 5.8-.8z"/>',
    starOn: '<path d="M12 3.3l2.6 5.3 5.8.8-4.2 4.1 1 5.8L12 16.5l-5.2 2.8 1-5.8-4.2-4.1 5.8-.8z"/>',
    more: '<circle cx="6" cy="12" r="1.7"/><circle cx="12" cy="12" r="1.7"/><circle cx="18" cy="12" r="1.7"/>',
    vol: '<path d="M4 9.5v5h3.5l4.5 4V5.5l-4.5 4H4zm11.2 2.5a3.2 3.2 0 0 0-1.7-2.8v5.6a3.2 3.2 0 0 0 1.7-2.8z"/>',
    lyrics: '<path fill="none" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round" d="M5.5 4h13A2.5 2.5 0 0 1 21 6.5v9A2.5 2.5 0 0 1 18.5 18H11l-4 3v-3H5.5A2.5 2.5 0 0 1 3 15.5v-9A2.5 2.5 0 0 1 5.5 4z"/><path d="M8.5 10.5c0-.8.6-1.5 1.4-1.5s1.3.6 1.3 1.4c0 1.1-.6 2.1-1.7 2.6l-.5-.8c.6-.3.9-.7 1-1.2h-.1c-.8 0-1.4-.2-1.4-.5zm4.4 0c0-.8.6-1.5 1.4-1.5s1.3.6 1.3 1.4c0 1.1-.6 2.1-1.7 2.6l-.5-.8c.6-.3.9-.7 1-1.2h-.1c-.8 0-1.4-.2-1.4-.5z"/>',
    // Popover menu outline icons
    plus: '<path fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" d="M12 5v14M5 12h14"/>',
    plAdd: '<path fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" d="M4 7h11M4 12h8M4 17h8M17 11v8M13 15h8"/>',
    qNext: '<path fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" d="M4 6h16M4 11h10M4 16h10M18 11l3 3-3 3M21 14h-4"/>',
    qLast: '<path fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" d="M4 6h16M4 11h16M4 16h10M18 13l3 3-3 3M21 16h-4"/>',
    radio: '<circle cx="12" cy="12" r="2"/><path fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" d="M8.5 8.5a5 5 0 0 0 0 7M15.5 8.5a5 5 0 0 1 0 7M5.5 5.5a9 9 0 0 0 0 13M18.5 5.5a9 9 0 0 1 0 13"/>',
    thumbDown: '<path fill="none" stroke="currentColor" stroke-width="1.7" stroke-linejoin="round" d="M17 4h3v9h-3M17 13l-3.5 6.2a1.4 1.4 0 0 1-2.5-.9L11.8 14H6.2a2 2 0 0 1-1.9-2.5l1.3-5.2A2 2 0 0 1 7.5 4.8H17V13z"/>',
    info: '<circle cx="12" cy="12" r="9" fill="none" stroke="currentColor" stroke-width="1.7"/><path fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" d="M12 11v5M12 8h.01"/>',
    share: '<path fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" d="M12 3v12M8 7l4-4 4 4M5 12v7a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-7"/>',
    link: '<path fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" d="M10 14a4 4 0 0 0 5.6 0l2.8-2.8a4 4 0 0 0-5.6-5.6L11.2 7.2M14 10a4 4 0 0 0-5.6 0L5.6 12.8a4 4 0 0 0 5.6 5.6l1.6-1.6"/>',
    code: '<path fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" d="M8 8l-4 4 4 4M16 8l4 4-4 4M14 5l-4 14"/>'
  };

  T.css(`
.np { background: #14120c; }
.np .bg { position: absolute; top: 0; right: 0; bottom: 0; left: 0; width: 100%; height: 100%; opacity: 0; transition: opacity 1s ease; }
.np .bg.on { opacity: 1; }
.np .veil { position: absolute; top: 0; right: 0; bottom: 0; left: 0;
  background: radial-gradient(circle at 30% 45%, rgba(255,255,255,0.04), rgba(0,0,0,0.34) 70%, rgba(0,0,0,0.52)); }
.np .page { position: absolute; top: 0; right: 0; bottom: 0; left: 0; padding: 0; transition: none; }

/* Left / Centered Player Column */
.np .left { position: absolute; top: 0; bottom: 0; left: 88px; width: 292px;
  display: flex; flex-direction: column; justify-content: center; align-items: stretch;
  transition: left .42s cubic-bezier(.22,1,.36,1), width .42s cubic-bezier(.22,1,.36,1); }
.np.nolyr .left { left: calc(50% - 158px); width: 316px; }

.np .cover { position: relative; width: 292px; height: 292px; border-radius: 10px; overflow: hidden;
  background: #1d1b18; border: 1px solid rgba(255,255,255,0.12);
  box-shadow: 0 20px 56px rgba(0,0,0,0.48); transition: width .42s ease, height .42s ease; }
.np.nolyr .cover { width: 316px; height: 316px; }
.np .cover img { position: absolute; top: 0; right: 0; bottom: 0; left: 0; width: 100%; height: 100%; object-fit: cover; transition: opacity .6s ease; }
.np .live { position: absolute; top: 10px; left: 10px; z-index: 2; background: #fa233b; color: #fff;
  font-size: 10px; font-weight: 700; letter-spacing: 1.4px; padding: 3px 9px; border-radius: 8px; }

/* Title + Artist — Album + Star & More circular glass buttons */
.np .hdr { display: flex; align-items: center; justify-content: space-between; margin-top: 12px; width: 100%; }
.np .meta-tx { flex: 1; min-width: 0; margin-right: 10px; }
.np .ti { font-size: 14.5px; font-weight: 700; color: #fff; text-align: left; line-height: 1.25;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.np .ar { margin-top: 2px; font-size: 13px; font-weight: 500; color: rgba(255,255,255,0.72); text-align: left; line-height: 1.25;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.np .ti, .np .ar { transition: opacity .22s ease; }
.np .swap { opacity: 0; }
.np .meta-acts { display: flex; align-items: center; flex: none; }
.np .meta-acts > * + * { margin-left: 8px; }
.np .cbtn { width: 26px; height: 26px; border-radius: 50%; background: rgba(255,255,255,0.15);
  color: rgba(255,255,255,0.92); display: flex; align-items: center; justify-content: center;
  transition: transform .15s, background-color .15s, box-shadow .15s; }
.np .cbtn.on { color: #fff; background: rgba(255,255,255,0.28); }
.np .cbtn.focus { background: rgba(255,255,255,0.34); transform: scale(1.16); box-shadow: 0 0 0 2px #fff; }

/* Progress scrubber with circular white knob & -remaining timestamp */
.np .pr { margin-top: 10px; padding: 5px 0 2px; width: 100%; }
.np .bar-wrap { position: relative; height: 10px; display: flex; align-items: center; }
.np .bar { position: relative; width: 100%; height: 3px; border-radius: 2px; background: rgba(255,255,255,0.26); transition: height .15s; }
.np .pr.focus .bar { height: 5px; }
.np .fill { position: absolute; top: 0; left: 0; bottom: 0; width: 100%; border-radius: 2px; background: #fff;
  transform-origin: 0 50%; transform: scaleX(0); }
.np .knob { position: absolute; top: 50%; left: 0; width: 9px; height: 9px; margin-left: -4.5px; margin-top: -4.5px;
  border-radius: 50%; background: #fff; box-shadow: 0 1px 4px rgba(0,0,0,0.45);
  transition: transform .15s; }
.np .pr.focus .knob { transform: scale(1.38); box-shadow: 0 0 0 3px rgba(255,255,255,0.32); }
.np .tm { display: flex; justify-content: space-between; font-size: 10px; color: rgba(255,255,255,0.56);
  margin-top: 3px; font-variant-numeric: tabular-nums; }

/* 5-button white transport row */
.np .tr { display: flex; justify-content: center; align-items: center; margin-top: 6px; width: 100%; }
.np .tr > * + * { margin-left: 16px; }
.np .tb { width: 38px; height: 38px; border-radius: 50%; display: flex; align-items: center; justify-content: center;
  color: #fff; transition: transform .15s, background-color .15s, box-shadow .15s; }
.np .tb.big { width: 46px; height: 46px; }
.np .tb.sm { width: 30px; height: 30px; color: rgba(255,255,255,0.72); }
.np .tb.sm.on { color: #fff; background: rgba(255,255,255,0.18); }
.np .tb.focus { background: rgba(255,255,255,0.22); transform: scale(1.14); box-shadow: 0 0 0 2px rgba(255,255,255,0.88); }

/* Bottom volume slider */
.np .vol { display: flex; align-items: center; margin-top: 10px; padding: 4px 0; width: 100%; }
.np .vol-ic { color: rgba(255,255,255,0.72); flex: none; margin-right: 8px; display: flex; align-items: center; }
.np .vol-bar-wrap { position: relative; flex: 1; height: 10px; display: flex; align-items: center; }
.np .vol-bar { position: relative; width: 100%; height: 3px; border-radius: 2px; background: rgba(255,255,255,0.26); transition: height .15s; }
.np .vol.focus .vol-bar { height: 5px; }
.np .vol-fill { position: absolute; top: 0; left: 0; bottom: 0; width: 100%; border-radius: 2px; background: #fff;
  transform-origin: 0 50%; transform: scaleX(0.65); }
.np .vol-knob { position: absolute; top: 50%; left: 65%; width: 9px; height: 9px; margin-left: -4.5px; margin-top: -4.5px;
  border-radius: 50%; background: #fff; box-shadow: 0 1px 4px rgba(0,0,0,0.45); transition: transform .15s; }
.np .vol.focus .vol-knob { transform: scale(1.38); box-shadow: 0 0 0 3px rgba(255,255,255,0.32); }

/* Bottom-right Lyrics Toggle Button */
.np .lyr-fab { position: absolute; right: 20px; bottom: 18px; width: 32px; height: 32px; border-radius: 8px;
  display: flex; align-items: center; justify-content: center; color: rgba(255,255,255,0.82);
  background: rgba(255,255,255,0.08); transition: transform .15s, background-color .15s, color .15s, box-shadow .15s; }
.np .lyr-fab.on { background: rgba(255,255,255,0.22); color: #fff; }
.np .lyr-fab.focus { background: #fff; color: #111; transform: scale(1.14); box-shadow: 0 0 0 2px #fff; }

/* Right Column Synced Lyrics (Image 3) */
.np.nolyr .right { display: none; }
.np .right { position: absolute; left: 438px; right: 52px; top: 0; bottom: 0; overflow: hidden; }
.np .lyr { position: absolute; left: 0; right: 0; top: 0; padding: 245px 0 260px;
  transition: transform .42s cubic-bezier(.22,1,.36,1); will-change: transform; }
.np .ln { font-size: 31px; font-weight: 700; line-height: 1.24; letter-spacing: -.4px; margin: 0 0 24px;
  color: rgba(255,255,255,0.78); opacity: 0.3; transform: scale(0.82); transform-origin: 0 50%;
  transition: color .28s ease, opacity .3s ease, transform .35s cubic-bezier(.22,1,.36,1); }
.np .ln.past { color: rgba(255,255,255,0.65); opacity: 0.2; transform: scale(0.8); }
.np .ln.act { color: #fff; opacity: 1; transform: scale(1.06); }
.np .ln.duet { text-align: right; transform-origin: 100% 50%; }
.np .ln.plain { color: rgba(255,255,255,0.8); opacity: 0.72; transform: scale(0.92); }
.np .ln .w { white-space: pre-wrap; }
.np .ln.act .w.sung { color: #fff; }
.np .ln.act .w.cur { color: #fff; }
.np .ln .bgv { display: block; font-size: 18px; font-weight: 600; color: rgba(255,255,255,0.45); margin-top: 4px; }
.np .ln.act .bgv { color: rgba(255,255,255,0.85); }
.np .upn { position: absolute; left: 0; right: 0; top: 50%; transform: translateY(-50%); }
.np .upn h3 { font-size: 13px; color: rgba(255,255,255,0.6); font-weight: 600; margin: 0 0 12px 12px; letter-spacing: .6px; text-transform: uppercase; }
.np .upn .item { font-size: 14px; }
.np .empty { position: absolute; top: 0; right: 0; bottom: 0; left: 0; display: flex; align-items: center; justify-content: center;
  color: rgba(255,255,255,0.65); font-size: 17px; }

/* Translucent Context Menu Popover (...) matching Image 2 */
.np .pop-mask { position: absolute; top: 0; right: 0; bottom: 0; left: 0; z-index: 30; display: none; }
.np .pop-mask.on { display: block; }
.np .pop-card { position: absolute; width: 242px; border-radius: 10px; overflow: hidden;
  background: rgba(34, 28, 22, 0.92); border: 1px solid rgba(255,255,255,0.18);
  box-shadow: 0 18px 48px rgba(0,0,0,0.58); }
.np .pop-mi { display: flex; align-items: center; justify-content: space-between;
  padding: 7.5px 13px; font-size: 12.5px; font-weight: 500; color: rgba(255,255,255,0.92);
  border-bottom: 1px solid rgba(255,255,255,0.1); transition: background-color .12s; }
.np .pop-mi:last-child { border-bottom: none; }
.np .pop-mi .pic { color: rgba(255,255,255,0.84); display: flex; align-items: center; flex: none; margin-left: 10px; }
.np .pop-mi.focus { background: rgba(255,255,255,0.22); color: #fff; }
@media (prefers-reduced-motion: reduce) {
  .np .left, .np .cover, .np .lyr, .np .bg { transition: none !important; }
}
`);

  // ---------------------------------------------------------------- lyrics: Apple TTML -> lines
  const ms = (v) => {
    if (!v) return NaN;
    const p = String(v).replace(/s$/, '').split(':').map(Number);
    return Math.round(1000 * p.reduce((acc, x) => acc * 60 + x, 0));
  };
  const isSpan = (n) => n.nodeType === 1 && /(^|:)span$/.test(n.nodeName);
  const isBg = (n) => /x-bg/.test(n.getAttribute('ttm:role') || n.getAttribute('role') || '');

  function leaves(node, out) {
    const kids = [...node.childNodes];
    kids.forEach((c, i) => {
      if (!isSpan(c) || isBg(c)) return;
      if ([...c.childNodes].some((k) => isSpan(k) && !isBg(k))) return leaves(c, out);
      let ends = /\s$/.test(c.textContent);
      for (let j = i + 1; j < kids.length && !ends; j++) {
        if (isSpan(kids[j])) break;
        if (kids[j].nodeType === 3 && /\s/.test(kids[j].textContent)) ends = true;
      }
      out.push({ s: ms(c.getAttribute('begin')), e: ms(c.getAttribute('end')), t: c.textContent, ends });
    });
    return out;
  }
  function merge(entries) {
    const out = [];
    for (let i = 0; i < entries.length; i++) {
      const w = Object.assign({}, entries[i]);
      while (!w.ends && i + 1 < entries.length) { const n = entries[++i]; w.t += n.t; w.e = n.e; w.ends = n.ends; }
      w.t = w.t.trim();
      if (w.t) out.push(w);
    }
    return out.length === 1 && entries.length > 3 ? entries.map((e) => Object.assign({}, e, { t: e.t.trim() })) : out;
  }

  function parseTTML(xml) {
    const doc = new DOMParser().parseFromString(xml, 'application/xml');
    const ps = [...doc.getElementsByTagName('p')];
    const lead = ps.length && ps[0].getAttribute('ttm:agent');
    return ps.map((p) => {
      const line = { s: ms(p.getAttribute('begin')), e: ms(p.getAttribute('end')), words: merge(leaves(p, [])), bg: null };
      if (isNaN(line.e)) line.e = line.s + 5000;
      const agent = p.getAttribute('ttm:agent');
      line.duet = !!(lead && agent && agent !== lead);
      const bg = [...p.getElementsByTagName('*')].find((n) => isSpan(n) && isBg(n));
      if (bg) {
        const bw = merge(leaves(bg, []));
        line.bg = {
          s: ms(bg.getAttribute('begin')) || (bw[0] && bw[0].s),
          e: ms(bg.getAttribute('end')) || (bw.length && bw[bw.length - 1].e),
          text: bw.length ? bw.map((w) => w.t).join(' ') : bg.textContent.trim()
        };
      }
      if (!line.words.length) line.text = p.textContent.replace(/\s+/g, ' ').trim();
      return line;
    }).filter((l) => l.words.length || l.text);
  }

  const lyricsCache = new Map();
  async function lyricsFor(id) {
    if (lyricsCache.has(id)) return lyricsCache.get(id);
    const p = (async () => {
      for (const kind of ['syllable-lyrics', 'lyrics']) {
        try {
          const j = await T.send('GET', '/v1/catalog/{sf}/songs/' + id + '/' + kind);
          const ttml = j && j.data && j.data[0] && j.data[0].attributes && j.data[0].attributes.ttml;
          if (ttml) { const lines = parseTTML(ttml); if (lines.length) return lines; }
        } catch (e) {}
        try {
          const r = await T.mk().api.music('/v1/catalog/' + T.sf() + '/songs/' + id + '/' + kind, {});
          const ttml2 = r && r.data && r.data.data && r.data.data[0] && r.data.data[0].attributes && r.data.data[0].attributes.ttml;
          if (ttml2) { const lines2 = parseTTML(ttml2); if (lines2.length) return lines2; }
        } catch (e) {}
      }
      return null;
    })();
    lyricsCache.set(id, p);
    if (lyricsCache.size > 30) lyricsCache.delete(lyricsCache.keys().next().value);
    return p;
  }

  // Optimistic item metadata when user clicks a track before setQueue finishes
  let pendingMeta = null;

  // ---------------------------------------------------------------- the screen
  T.screens.now = function (s) {
    s.el.classList.add('full', 'np');
    s.ghostNav = true;
    const mk = T.mk();
    const bgA = h('canvas', { class: 'bg', width: 64, height: 36 });
    const bgB = h('canvas', { class: 'bg', width: 64, height: 36 });
    s.el.insertBefore(bgB, s.page);
    s.el.insertBefore(bgA, bgB);
    s.el.insertBefore(h('div', 'veil'), s.page);

    const live = h('div', 'live', 'LIVE');
    live.hidden = true;
    const cover = h('div', 'cover', live);
    const ti = h('div', 'ti');
    const ar = h('div', 'ar');

    let favId = '', favOn = false;
    const bFav = h('div', {
      class: 'cbtn f',
      'aria-label': 'Favorito',
      onok: async () => {
        if (!favId) return;
        const on = !favOn;
        try {
          await T.setFavorite(favId, on);
          favOn = on;
          paintFav();
          T.toast(on ? 'Adicionado aos favoritos' : 'Removido dos favoritos');
        } catch (e) { T.toast('Não foi possível: ' + T.plain(e && (e.errorCode || e.message))); }
      }
    }, svg(ICONS.star, 14));
    function paintFav() {
      bFav.classList.toggle('on', favOn);
      bFav.replaceChildren(svg(favOn ? ICONS.starOn : ICONS.star, 14));
    }
    T.onFavorite = (id, on) => { if (id === favId) { favOn = on !== false; paintFav(); } };

    // Context menu popover (...) matching Image 2
    const popCard = h('div', 'pop-card');
    const popMask = h('div', 'pop-mask', popCard);
    let popOpen = false, popReturn = null;

    function closePop() {
      if (!popOpen) return;
      popOpen = false;
      popMask.classList.remove('on');
      popCard.replaceChildren();
      if (popReturn && popReturn.isConnected) T.focus(popReturn);
      else T.focus(bMore);
    }

    function openPop() {
      const np = mk.nowPlayingItem;
      const a = (np && np.attributes) || {};
      const itemId = favId || (np && np.id) || '';
      const itemObj = { id: itemId, type: 'songs', attributes: a };
      const songUrl = a.url || ('https://music.apple.com/' + T.sf() + '/song/' + itemId);
      const embedUrl = songUrl.replace('music.apple.com', 'embed.music.apple.com');

      const rows = [
        { label: 'Adicionar à biblioteca', ic: ICONS.plus, run: () => itemId && T.addToLibrary(itemObj) },
        { label: 'Adicionar à playlist', ic: ICONS.plAdd, run: () => T.toast('Adicionado à fila de playlists') },
        { label: 'Reproduzir a seguir', ic: ICONS.qNext, run: () => itemId && T.playNext(itemObj, false) },
        { label: 'Reproduzir por último', ic: ICONS.qLast, run: () => itemId && T.playNext(itemObj, true) },
        { label: 'Criar estação', ic: ICONS.radio, run: () => itemId && T.play({ type: 'stations', id: 'ra.' + itemId }) },
        { label: 'Favorito', ic: favOn ? ICONS.starOn : ICONS.star, run: () => bFav._onok() },
        { label: 'Sugerir menos', ic: ICONS.thumbDown, run: () => {
          if (itemId) T.send('PUT', '/v1/me/ratings/songs/' + itemId, {}).catch(() => {});
          T.toast('Sugeriremos menos músicas como esta');
        } },
        { label: 'Ver créditos', ic: ICONS.info, run: () => {
          const credits = [a.composerName ? 'Compositor: ' + a.composerName : '', a.albumName ? 'Álbum: ' + a.albumName : '', a.recordLabel || ''].filter(Boolean).join(' · ');
          T.toast(credits || (a.artistName || 'Apple Music'));
        } },
        { label: 'Compartilhar', ic: ICONS.share, run: () => T.toast(a.name ? 'Compartilhar: ' + a.name : 'Link pronto') },
        { label: 'Copiar link', ic: ICONS.link, run: () => {
          try { navigator.clipboard && navigator.clipboard.writeText(songUrl); } catch (e) {}
          T.toast('Link copiado');
        } },
        { label: 'Copiar código de inserção', ic: ICONS.code, run: () => {
          const code = '<iframe allow="autoplay *; encrypted-media *;" frameborder="0" height="175" src="' + embedUrl + '"></iframe>';
          try { navigator.clipboard && navigator.clipboard.writeText(code); } catch (e) {}
          T.toast('Código de inserção copiado');
        } }
      ];

      const els = rows.map((r) => h('div', {
        class: 'pop-mi f row',
        onok: () => { closePop(); r.run(); }
      }, h('span', null, r.label), h('span', 'pic', svg(r.ic, 15))));

      popCard.replaceChildren(...els);
      // Anchor popover right above/beside the (...) button like Image 2
      const btnRect = bMore.getBoundingClientRect();
      const stageRect = s.el.getBoundingClientRect();
      const scale = stageRect.width ? (960 / stageRect.width) : 1;
      const bx = (btnRect.left - stageRect.left) * scale;
      const by = (btnRect.top - stageRect.top) * scale;
      const leftPos = Math.min(960 - 256, Math.max(16, bx - 10));
      const topPos = Math.max(14, Math.min(540 - 380, by - 320));
      popCard.style.left = leftPos + 'px';
      popCard.style.top = topPos + 'px';

      popReturn = T.cur();
      popOpen = true;
      popMask.classList.add('on');
      T.focus(els[0]);
    }

    const bMore = h('div', {
      class: 'cbtn f',
      'aria-label': 'Mais opções',
      onok: () => { if (popOpen) closePop(); else openPop(); }
    }, svg(ICONS.more, 14));

    const hdr = h('div', 'hdr row',
      h('div', 'meta-tx', ti, ar),
      h('div', 'meta-acts', bFav, bMore));

    // Progress bar with white circular knob + elapsed / -remaining timestamps
    const fill = h('div', 'fill');
    const knob = h('div', 'knob');
    const tNow = h('span', null, '0:00');
    const tRem = h('span', null, '-0:00');
    const pr = h('div', {
      class: 'pr f row',
      'aria-label': 'Progresso da música',
      onok: () => bPlay._onok()
    }, h('div', 'bar-wrap', h('div', 'bar', fill, knob)), h('div', 'tm', tNow, tRem));

    // Transport buttons: Shuffle, Prev, Play/Pause, Next, Repeat
    const bShuffle = h('div', {
      class: 'tb sm f',
      'aria-label': 'Aleatório',
      onok: () => { mk.shuffleMode = mk.shuffleMode ? 0 : 1; sync(); T.toast(mk.shuffleMode ? 'Aleatório ativado' : 'Aleatório desativado'); }
    }, svg(ICONS.shuffle, 15));
    const bPrev = h('div', {
      class: 'tb f',
      'aria-label': 'Anterior',
      onok: () => (mk.currentPlaybackTime > 5 ? mk.seekToTime(0) : mk.skipToPreviousItem())
    }, svg(ICONS.prev, 22));
    const bPlay = h('div', {
      class: 'tb big f',
      'aria-label': 'Reproduzir ou pausar',
      onok: () => (mk.playbackState === 2 ? mk.pause() : mk.play())
    }, svg(ICONS.pause, 28));
    const bNext = h('div', {
      class: 'tb f',
      'aria-label': 'Próxima',
      onok: () => mk.skipToNextItem()
    }, svg(ICONS.next, 22));
    const bRepeat = h('div', {
      class: 'tb sm f',
      'aria-label': 'Repetir',
      onok: () => { mk.repeatMode = (mk.repeatMode + 1) % 3; sync(); T.toast(['Repetição desativada', 'Repetir música', 'Repetir tudo'][mk.repeatMode]); }
    }, svg(ICONS.repeat, 15));
    const tr = h('div', 'tr row', bShuffle, bPrev, bPlay, bNext, bRepeat);

    // Volume slider at bottom (Left/Right adjusts volume)
    const volFill = h('div', 'vol-fill');
    const volKnob = h('div', 'vol-knob');
    let curVol = typeof mk.volume === 'number' && isFinite(mk.volume) ? mk.volume : 0.7;
    function paintVol() {
      const v = Math.max(0, Math.min(1, curVol));
      volFill.style.transform = 'scaleX(' + v.toFixed(3) + ')';
      volKnob.style.left = (v * 100).toFixed(1) + '%';
    }
    paintVol();
    const volRow = h('div', {
      class: 'vol f row',
      'aria-label': 'Volume',
      onok: () => { curVol = curVol > 0 ? 0 : 0.7; mk.volume = curVol; paintVol(); }
    }, h('span', 'vol-ic', svg(ICONS.vol, 13)), h('div', 'vol-bar-wrap', h('div', 'vol-bar', volFill, volKnob)));
    volRow._onkey = (k) => {
      if (k !== 'left' && k !== 'right') return false;
      curVol = Math.max(0, Math.min(1, curVol + (k === 'right' ? 0.08 : -0.08)));
      mk.volume = curVol;
      paintVol();
      return true;
    };

    const left = h('div', 'left', cover, hdr, pr, tr, volRow);
    const lyr = h('div', 'lyr');
    const right = h('div', 'right', lyr);

    let showLyr = false;
    try { showLyr = localStorage.getItem(LYRICS_PREF) === '1'; } catch (e) {}
    s.el.classList.toggle('nolyr', !showLyr);
    const bLyr = h('div', {
      class: 'lyr-fab f row' + (showLyr ? ' on' : ''),
      'aria-label': 'Mostrar ou ocultar letras',
      onok: () => {
        showLyr = !showLyr;
        try { localStorage.setItem(LYRICS_PREF, showLyr ? '1' : '0'); } catch (e) {}
        s.el.classList.toggle('nolyr', !showLyr);
        bLyr.classList.toggle('on', showLyr);
        loadLyrics();
      }
    }, svg(ICONS.lyrics, 18));

    const empty = h('div', 'empty', 'Nada tocando no momento');
    s.page.append(left, right, bLyr, empty, popMask);

    // Scrubber left/right seek
    let scrubTo = null, scrubTimer = 0, seekingTo = null, seekUntil = 0;
    pr._onkey = (k) => {
      if (k !== 'left' && k !== 'right') return false;
      const dur = mk.currentPlaybackDuration || 0;
      if (!dur) return true;
      const from = scrubTo != null ? scrubTo : seekingTo != null ? seekingTo : pos() / 1000;
      scrubTo = Math.max(0, Math.min(dur - 1, from + (k === 'right' ? 10 : -10)));
      const frac = scrubTo / dur;
      fill.style.transform = 'scaleX(' + frac.toFixed(4) + ')';
      knob.style.left = (frac * 100).toFixed(2) + '%';
      tNow.textContent = T.dur(scrubTo * 1000);
      tRem.textContent = '-' + T.dur(Math.max(0, (dur - scrubTo) * 1000));
      clearTimeout(scrubTimer);
      scrubTimer = setTimeout(() => {
        const t = scrubTo;
        scrubTo = null;
        seekingTo = t;
        seekUntil = performance.now() + 6000;
        jump(t * 1000);
        mk.seekToTime(t);
        progress();
      }, 450);
      return true;
    };

    s.onkey = (k) => {
      if (popOpen) {
        if (k === 'back') { closePop(); return true; }
        if (k === 'up' || k === 'down') {
          const items = [...popCard.querySelectorAll('.pop-mi')];
          const idx = items.indexOf(T.cur());
          const next = k === 'down' ? Math.min(items.length - 1, idx + 1) : Math.max(0, idx - 1);
          if (items[next]) T.focus(items[next]);
          return true;
        }
        if (k === 'left' || k === 'right') return true;
        return false;
      }
      return false;
    };

    s.onshow = () => {
      if (typeof mk.volume === 'number' && isFinite(mk.volume)) { curVol = mk.volume; paintVol(); }
      if (!s.el.contains(T.cur())) { if (T.inNav()) s.focusEl = bPlay; else T.focus(bPlay); }
      relayout();
    };

    // ---- playback clock
    let anchorPos = 0, anchorWall = 0;
    const playing = () => mk.playbackState === 2;
    const pos = () => anchorPos + (playing() ? performance.now() - anchorWall : 0);
    function exactTime() {
      try {
        const mip = mk._mediaItemPlayback, p = mip && (mip._currentPlayer || (mip.getCurrentPlayer && mip.getCurrentPlayer()));
        const t = p && p._currentTime;
        if (typeof t === 'number' && isFinite(t) && Math.abs(t - (mk.currentPlaybackTime || 0)) <= 0.75) return t;
      } catch (e) {}
      return null;
    }
    const playerTime = () => { const t = exactTime(); return t != null ? t : mk.currentPlaybackTime || 0; };
    function anchor(msVal) { anchorPos = msVal != null ? msVal : playerTime() * 1000; anchorWall = performance.now(); }
    function jump(msVal) { anchor(msVal); shownPos = -1; }

    let lastFill = -1, shownPos = -1;
    function shown() {
      const p = pos();
      if (seekingTo == null && shownPos >= 0 && p < shownPos && shownPos - p < 1500) return shownPos;
      shownPos = p;
      return p;
    }
    function paintProgress() {
      if (scrubTo != null) return;
      const dur = (mk.currentPlaybackDuration || 0) * 1000;
      const at = Math.min(dur || Infinity, shown());
      const f = dur ? Math.max(0, Math.min(1, at / dur)) : 0;
      if (Math.abs(f - lastFill) * 316 >= 0.3) {
        lastFill = f;
        fill.style.transform = 'scaleX(' + f.toFixed(4) + ')';
        knob.style.left = (f * 100).toFixed(2) + '%';
      }
      const nowStr = T.dur(at);
      if (tNow.textContent !== nowStr) tNow.textContent = nowStr;
      const remStr = dur ? ('-' + T.dur(Math.max(0, dur - at))) : '-0:00';
      if (tRem.textContent !== remStr) tRem.textContent = remStr;
    }
    function progress() {
      if (scrubTo != null) return;
      lastFill = -1;
      paintProgress();
    }

    // ---- warm rich blurred cover background (matching Image 1 & 3)
    let front = bgA, bgFor = '';
    function paintBackground(art) {
      const url = T.art(art, 120);
      if (!url || url === bgFor) return;
      bgFor = url;
      const img = new Image();
      img.crossOrigin = 'anonymous';
      img.onload = () => {
        const back = front === bgA ? bgB : bgA, c = back.getContext('2d');
        c.filter = 'blur(7px) saturate(1.85) brightness(.72)';
        c.fillStyle = '#18140e';
        c.fillRect(0, 0, 64, 36);
        c.drawImage(img, -8, -8, 80, 52);
        back.classList.add('on');
        front.classList.remove('on');
        front = back;
      };
      img.src = url;
    }

    // ---- lyrics
    let lines = null, lineEls = [], active = -1, tickTimer = 0, lyricsId = '';
    function showLyrics(list) {
      lines = list;
      active = -1;
      lineEls = list.map((l) => {
        const el = h('div', 'ln' + (l.duet ? ' duet' : '') + (l.s >= 0 ? '' : ' plain'));
        if (l.words.length) l.words.forEach((w, i) => { el.append(w.el = h('span', 'w', w.t)); if (i < l.words.length - 1) el.append(' '); });
        else el.append(l.text);
        if (l.bg) el.append(h('span', 'bgv', l.bg.text));
        return el;
      });
      lyr.replaceChildren(...lineEls);
      scrollTo(0, false);
      requestAnimationFrame(() => scrollTo(active >= 0 ? active : 0, false));
      tick();
    }

    function upNext() {
      lines = null;
      const items = (mk.queue && mk.queue.items) || [], at = (mk.queue && mk.queue.position) || 0;
      const next = items.slice(at + 1, at + 8);
      lyr.style.transform = '';
      lyr.replaceChildren(next.length ? h('div', 'upn', h('h3', null, 'A seguir'), h('div', 'list', next.map((it) =>
        h('div', 'item', h('div', 'tx', h('div', 'a', it.title || (it.attributes && it.attributes.name) || ''),
          h('div', 'b', it.artistName || (it.attributes && it.attributes.artistName) || '')))))) : '');
    }

    function scrollTo(i, animate) {
      const el = lineEls[i] || lineEls[0];
      const hRight = right.clientHeight || 540;
      const y = el ? (el.offsetTop + el.offsetHeight / 2 - hRight * 0.5) : 0;
      lyr.style.transition = animate === false ? 'none' : '';
      lyr.style.transform = 'translateY(' + (-Math.max(0, y)) + 'px)';
    }

    function tick() {
      clearTimeout(tickTimer);
      if (!lines || s.el.hidden) return;
      const t = pos() + LEAD_MS;
      let passed = -1;
      for (let i = 0; i < lines.length; i++) { if (lines[i].s >= 0 && lines[i].s <= t) passed = i; else if (lines[i].s > t) break; }
      const L = lines[passed];
      const now = L && t <= Math.max(L.e, L.bg ? L.bg.e || 0 : 0) + 250 ? passed : (passed >= 0 ? passed : 0);
      if (now !== active) {
        if (active >= 0 && lineEls[active]) {
          lineEls[active].classList.remove('act');
          lines[active].words.forEach((w) => { w.el.className = 'w'; });
        }
        active = now;
        lineEls.forEach((el, i) => el.classList.toggle('past', i < active));
        if (active >= 0 && lineEls[active]) lineEls[active].classList.add('act');
        scrollTo(active >= 0 ? active : 0);
      }
      let next = Infinity;
      if (active >= 0) {
        for (const w of lines[active].words) {
          const st = t >= w.e ? 'sung' : t >= w.s ? 'cur' : '';
          if (w.state !== st) { w.state = st; w.el.className = 'w' + (st ? ' ' + st : ''); }
          if (w.s > t) next = Math.min(next, w.s);
          else if (w.e > t) next = Math.min(next, w.e);
        }
        next = Math.min(next, Math.max(L.e, L.bg ? L.bg.e || 0 : 0) + 251);
      }
      const nl = lines.find((l) => l.s > t);
      if (nl) next = Math.min(next, nl.s);
      if (playing() && next < Infinity) tickTimer = setTimeout(tick, Math.max(30, next - t));
    }

    // ---- cover and text swap
    function swapCover(url) {
      if (!url) return;
      const img = h('img', { src: url, alt: '', decoding: 'async', width: 316, height: 316 });
      img.style.opacity = '0';
      cover.append(img);
      const show = () => requestAnimationFrame(() => {
        img.style.opacity = '1';
        setTimeout(() => {
          [...cover.querySelectorAll('img')].forEach((old) => { if (old !== img) old.remove(); });
        }, 650);
      });
      if (img.complete) show(); else img.onload = img.onerror = show;
    }
    function swapText(el, text) {
      if (el.textContent === text) return;
      if (!el.textContent) { el.textContent = text; return; }
      el.classList.add('swap');
      clearTimeout(el._swap);
      el._swap = setTimeout(() => { el.textContent = text; el.classList.remove('swap'); }, 180);
    }

    // ---- what is playing
    let itemKey = '';
    async function onItem() {
      const np = mk.nowPlayingItem || pendingMeta;
      empty.hidden = !!np;
      left.hidden = right.hidden = bLyr.hidden = !np;
      if (!np) return;
      const a = np.attributes || {};
      const title = a.name || np.title || '';
      const artist = a.artistName || np.artistName || a.curatorName || '';
      const album = a.albumName || np.albumName || '';
      const subLine = artist && album && album !== title ? (artist + ' — ' + album) : (artist || album);
      const key = (np.id || '') + '|' + title;
      const art = a.artwork || np.artwork;

      swapText(ti, title);
      swapText(ar, subLine);
      live.hidden = !(np.isLiveRadioStation || (np.type === 'stations' && a.isLive) || np.playbackType === 4);

      if (key === itemKey) return;
      itemKey = key;
      if (art) {
        swapCover(T.art(art, 600));
        paintBackground(art);
        if (T.setBackdrop) T.setBackdrop(T.art(art, 160), T.palette ? T.palette(art) : '');
      }
      favId = np.songId || (a.playParams && a.playParams.catalogId) || (/^\d+$/.test(np.id || '') ? np.id : '');
      favOn = false;
      paintFav();
      bFav.hidden = !favId;
      if (favId) {
        const curFavId = favId;
        T.isFavorite(curFavId).then((on) => { if (favId === curFavId) { favOn = !!on; paintFav(); } });
      }
      sync();
      if (mk.nowPlayingItem) return loadLyrics();
    }

    async function loadLyrics() {
      clearTimeout(tickTimer);
      lines = null;
      lyricsId = '';
      lyr.replaceChildren();
      const np = mk.nowPlayingItem;
      if (!showLyr || !np) return;
      const a = np.attributes || {};
      const id = np.songId || (a.playParams && a.playParams.catalogId) || (/^\d+$/.test(np.id || '') ? np.id : '');
      lyricsId = id;
      if (id && a.hasLyrics !== false) {
        lyr.style.transform = '';
        lyr.replaceChildren(h('div', { class: 'ln plain', style: 'margin-top:34%;font-size:20px;opacity:.55' }, 'Carregando letra…'));
        const list = await lyricsFor(id);
        if (lyricsId !== id || !showLyr) return;
        if (list) return showLyrics(list);
      }
      lyr.style.transform = '';
      lyr.replaceChildren(h('div', { class: 'ln plain', style: 'margin-top:34%;font-size:22px;opacity:.55' }, 'Letra indisponível para esta música'));
    }

    function sync() {
      bPlay.replaceChildren(svg(playing() ? ICONS.pause : ICONS.play, 28));
      bShuffle.classList.toggle('on', !!mk.shuffleMode);
      bRepeat.classList.toggle('on', !!mk.repeatMode);
    }

    let lastAnchor = 0, lastSecond = -1;
    T.on('nowPlayingItemDidChange', () => { pendingMeta = null; jump(); lastSecond = -1; onItem(); progress(); }, s);
    T.on('playbackStateDidChange', () => { if (seekingTo == null) anchor(); sync(); progress(); tick(); }, s);
    T.on('playbackTimeDidChange', () => {
      if (seekingTo != null) {
        if (Math.abs(playerTime() - seekingTo) < 2 || performance.now() > seekUntil) seekingTo = null;
        else return;
      }
      let at = exactTime(), near = 120;
      if (at == null) {
        const r = mk.currentPlaybackTime || 0, turned = r === lastSecond + 1;
        if (r === lastSecond) return;
        lastSecond = r;
        at = turned ? r - 0.25 : r;
        near = turned ? 200 : 650;
      }
      const off = Math.abs(pos() - at * 1000);
      if (off > near || performance.now() - lastAnchor > 4000) {
        anchor(at * 1000);
        lastAnchor = performance.now();
        if (off > 1000) { shownPos = -1; progress(); if (lines) { active = -1; lines.forEach((l) => l.words.forEach((w) => { w.state = null; })); } tick(); }
      }
    }, s);

    function relayout() { anchor(); onItem().then(() => { progress(); tick(); }); }

    let uiTimer = setInterval(() => {
      if (!s.el.hidden && s.el.isConnected && playing()) paintProgress();
    }, 180);

    s.ondrop = () => { clearTimeout(tickTimer); clearTimeout(scrubTimer); clearInterval(uiTimer); };
    relayout();
    s.focusEl = bPlay;
  };

  // Starting something from anywhere: open Now Playing IMMEDIATELY with optimistic metadata
  T.onPlay = (x) => {
    if (x) pendingMeta = x;
    T.tab('now', true);
  };
})();
