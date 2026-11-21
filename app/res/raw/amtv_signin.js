// Apple's sign-in pages (the idmsa.apple.com popup, then authorize.music.apple.com), made usable
// with a TV remote AND via QR Code on the user's phone (http://<TV_IP>:8090/applemusic).
(function () {
  if (window.__amtvSignin) return;
  const S = window.__amtvSignin = {};
  const HINTS = {
    en: '↑ ↓  choose   ·   OK  type or press   ·   Back  cancel',
    de: '↑ ↓  auswählen   ·   OK  eingeben oder drücken   ·   Zurück  abbrechen',
    es: '↑ ↓  elegir   ·   OK  escribir o presionar   ·   Atrás  cancelar',
    fr: '↑ ↓  choisir   ·   OK  écrire ou valider   ·   Retour  annuler',
    pt: '↑ ↓  escolher no controle   ·   OK  digitar   ·   Ou escaneie o QR Code ao lado com o celular',
    ru: '↑ ↓  выбрать   ·   OK  ввести или нажать   ·   Назад  отмена',
    hi: '↑ ↓  चुनें   ·   OK  टाइप करें या दबाएँ   ·   वापस जाएँ  रद्द करें',
    ja: '↑ ↓  選択   ·   OK  入力または決定   ·   戻る  キャンセル',
    zh: '↑ ↓  选择   ·   OK  输入或按下   ·   返回  取消',
  };
  const lang = (() => {
    const nl = String(window.__amtvLang || 'pt').toLowerCase(), l = nl.split(/[-_]/)[0];
    if (l === 'zh' && /hant|-tw|-hk|-mo/.test(nl)) return 'pt';
    return HINTS[l] ? l : 'pt';
  })();

  const CSS = `
    .amtv-focus { outline: 4px solid #fa233b !important; outline-offset: 4px !important; border-radius: 12px !important; }
    input:not([type=hidden]) { font-size: 22px !important; min-height: 56px !important; }
    button, a[href] { font-size: 18px !important; }
  `;

  function widget() {
    const f = document.getElementById('aid-auth-widget-iFrame');
    try { return f && f.contentDocument; } catch (e) { return null; }
  }

  function widgetWin() {
    const f = document.getElementById('aid-auth-widget-iFrame');
    try { return f && f.contentWindow; } catch (e) { return null; }
  }

  function styled(d) {
    if (!d || !d.documentElement || d.getElementById('amtv-signin-css')) return;
    const st = d.createElement('style');
    st.id = 'amtv-signin-css';
    st.textContent = CSS;
    (d.head || d.documentElement).appendChild(st);
  }

  function hint() {
    if (!document.body) return;
    if (!document.getElementById('amtv-signin-hint')) {
      const b = document.createElement('div');
      b.id = 'amtv-signin-hint';
      b.textContent = HINTS[lang];
      b.style.cssText = 'position:fixed;left:0;right:0;bottom:0;padding:14px 0;text-align:center;z-index:2147483647;'
        + 'font:17px -apple-system,Roboto,sans-serif;color:#444;background:rgba(245,245,247,.96);border-top:1px solid #ddd';
      document.body.appendChild(b);
    }
    if (window.__amtvQrSvg && !document.getElementById('amtv-qr-card')) {
      // Shift the main login container slightly left so the QR card sits cleanly on the right
      const pageStyle = document.createElement('style');
      pageStyle.id = 'amtv-qr-layout-css';
      pageStyle.textContent = 'body { padding-right: 340px !important; box-sizing: border-box !important; }';
      (document.head || document.documentElement).appendChild(pageStyle);

      const card = document.createElement('div');
      card.id = 'amtv-qr-card';
      card.style.cssText = 'position:fixed;right:32px;top:50%;transform:translateY(-54%);width:300px;padding:22px 20px;'
        + 'background:#16161a;color:#f5f5f7;border-radius:22px;box-shadow:0 18px 48px rgba(0,0,0,.35);'
        + 'z-index:2147483646;font-family:-apple-system,Roboto,sans-serif;text-align:center;border:1px solid rgba(255,255,255,.12);';
      const badge = document.createElement('div');
      badge.textContent = 'LOGIN RÁPIDO POR QR CODE';
      badge.style.cssText = 'font-size:11px;font-weight:700;letter-spacing:1.1px;color:#fa233b;margin-bottom:8px;';
      const title = document.createElement('div');
      title.textContent = 'Entrar pelo Celular';
      title.style.cssText = 'font-size:19px;font-weight:700;margin-bottom:6px;color:#fff;';
      const sub = document.createElement('div');
      sub.textContent = 'Escaneie com a câmera do iPhone ou Android para usar suas senhas salvas e o código de 6 dígitos:';
      sub.style.cssText = 'font-size:12.5px;line-height:1.4;color:#a8a8b0;margin-bottom:14px;';
      const qrBox = document.createElement('div');
      qrBox.style.cssText = 'width:184px;height:184px;margin:0 auto 12px;padding:10px;background:#fff;border-radius:16px;display:flex;align-items:center;justify-content:center;';
      qrBox.innerHTML = window.__amtvQrSvg;
      const urlEl = document.createElement('div');
      urlEl.textContent = window.__amtvQrUrl || '';
      urlEl.style.cssText = 'font-size:11.5px;color:#8e8e93;word-break:break-all;font-family:monospace;';
      const statusEl = document.createElement('div');
      statusEl.id = 'amtv-qr-status';
      statusEl.style.cssText = 'margin-top:10px;font-size:12px;font-weight:600;color:#30d158;display:none;';
      card.appendChild(badge);
      card.appendChild(title);
      card.appendChild(sub);
      card.appendChild(qrBox);
      card.appendChild(urlEl);
      card.appendChild(statusEl);
      document.body.appendChild(card);
    }
  }

  function setQrStatus(msg) {
    const el = document.getElementById('amtv-qr-status');
    if (el) {
      el.textContent = msg;
      el.style.display = msg ? 'block' : 'none';
    }
  }

  const shown = (e) => e.getClientRects().length > 0 && getComputedStyle(e).visibility !== 'hidden';

  function items() {
    const d = widget() || document;
    return [...d.querySelectorAll('input:not([type=hidden]):not([disabled]), button:not([disabled]), a[href], [role=button]:not([aria-disabled=true])')]
      .filter(shown);
  }

  let cur = null;
  function mark(el) {
    if (cur && cur !== el) cur.classList.remove('amtv-focus');
    cur = el;
    if (el) { el.classList.add('amtv-focus'); el.scrollIntoView({ block: 'nearest' }); }
  }

  const seen = new WeakSet();
  function keep() {
    styled(document);
    styled(widget());
    hint();
    const list = items();
    let fresh = null;
    for (const e of list) if (e.tagName === 'INPUT' && !seen.has(e)) { seen.add(e); if (!fresh) fresh = e; }
    if (fresh) mark(fresh);
    else if (list.length && (!cur || !cur.isConnected || list.indexOf(cur) < 0)) {
      mark(list.find((e) => e.tagName === 'INPUT')
        || list.find((e) => e.tagName === 'BUTTON' || e.getAttribute('role') === 'button') || list[0]);
    }
  }

  function setInputValue(win, doc, el, val) {
    if (!el) return;
    el.focus();
    try {
      const proto = (win && win.HTMLInputElement && win.HTMLInputElement.prototype) || HTMLInputElement.prototype;
      const desc = Object.getOwnPropertyDescriptor(proto, 'value');
      if (desc && desc.set) desc.set.call(el, val);
      else el.value = val;
    } catch (e) {
      el.value = val;
    }
    try {
      if (win && win.$) {
        win.$(el).val(val).trigger('input').trigger('change').trigger('keyup');
      }
    } catch (e) {}
    const Ev = (win && win.Event) || Event;
    const Kb = (win && win.KeyboardEvent) || KeyboardEvent;
    el.dispatchEvent(new Ev('input', { bubbles: true }));
    el.dispatchEvent(new Ev('change', { bubbles: true }));
    el.dispatchEvent(new Kb('keyup', { bubbles: true, key: 'a', keyCode: 65 }));
  }

  // Triggered when the user submits their Apple ID & Password from the QR Code mobile page
  S.fillLogin = async function (email, password) {
    setQrStatus('Preenchendo ID Apple enviado pelo celular…');
    const doc = widget() || document;
    const win = widgetWin() || window;
    const acc = doc.getElementById('account_name_text_field') || doc.querySelector('input[type="text"], input[type="email"]');
    const btn = doc.getElementById('sign-in') || doc.querySelector('button[id*="sign-in"], button[type="submit"]');
    if (acc && email) {
      setInputValue(win, doc, acc, email);
    }
    await new Promise((r) => setTimeout(r, 250));
    let pwd = doc.getElementById('password_text_field') || doc.querySelector('input[type="password"]');
    const pwdReady = () => {
      pwd = doc.getElementById('password_text_field') || doc.querySelector('input[type="password"]');
      if (!pwd || pwd.disabled) return false;
      const r = pwd.getBoundingClientRect();
      const group = pwd.closest('.password, .form-row, .si-field-container');
      if (group && /hide|hidden|collapse/i.test(group.className) && !/show|open/i.test(group.className)) return false;
      return r.height > 10 && getComputedStyle(pwd).visibility !== 'hidden';
    };

    // Click Continue on the email step so Apple expands the password field
    if (btn && !btn.disabled) {
      btn.click();
    }
    if (password) {
      for (let i = 0; i < 30; i++) {
        await new Promise((r) => setTimeout(r, 250));
        if (pwdReady()) break;
      }
      pwd = doc.getElementById('password_text_field') || doc.querySelector('input[type="password"]');
      if (pwd) {
        setQrStatus('Preenchendo senha e autenticando…');
        setInputValue(win, doc, pwd, password);
        await new Promise((r) => setTimeout(r, 300));
        const b2 = doc.getElementById('sign-in') || doc.querySelector('button[id*="sign-in"], button[type="submit"]');
        if (b2 && !b2.disabled) b2.click();
        else {
          const Kb = (win && win.KeyboardEvent) || KeyboardEvent;
          pwd.dispatchEvent(new Kb('keydown', { bubbles: true, key: 'Enter', keyCode: 13, which: 13 }));
          pwd.dispatchEvent(new Kb('keyup', { bubbles: true, key: 'Enter', keyCode: 13, which: 13 }));
        }
        setQrStatus('Se solicitado, digite o código de 6 dígitos no celular.');
      }
    }
    return 'ok';
  };

  // Triggered when the user enters the 6-digit 2FA verification code on the QR Code mobile page
  S.fill2fa = async function (code) {
    code = String(code || '').replace(/\D+/g, '').slice(0, 6);
    if (!code) return 'empty';
    setQrStatus('Enviando código de verificação (' + code + ')…');
    const doc = widget() || document;
    const win = widgetWin() || window;
    const inputs = [...doc.querySelectorAll('input:not([type=hidden]):not([disabled])')]
      .filter((i) => i.id !== 'account_name_text_field' && i.id !== 'password_text_field' && shown(i));
    if (inputs.length >= 6) {
      for (let i = 0; i < 6 && i < code.length; i++) {
        setInputValue(win, doc, inputs[i], code[i]);
        await new Promise((r) => setTimeout(r, 70));
      }
    } else if (inputs.length >= 1) {
      setInputValue(win, doc, inputs[0], code);
    }
    // Wait briefly and auto-click Trust / Continue ("Confiar" / "Continuar") if Apple prompts
    for (let k = 0; k < 16; k++) {
      await new Promise((r) => setTimeout(r, 400));
      const d2 = widget() || document;
      const btns = [...d2.querySelectorAll('button:not([disabled])')].filter(shown);
      const trust = btns.find((b) => /confiar|trust|continuar|continue/i.test(b.textContent || ''));
      if (trust) {
        trust.click();
      }
    }
    setQrStatus('Código enviado! Concluindo login…');
    return 'ok';
  };

  S.key = function (k) {
    keep();
    const list = items();
    if (!list.length || !cur) return 'none';
    const i = list.indexOf(cur);
    if (k === 'down' || k === 'right') mark(list[Math.min(list.length - 1, i + 1)]);
    else if (k === 'up' || k === 'left') mark(list[Math.max(0, i - 1)]);
    else if (k === 'ok') {
      if (cur.tagName === 'INPUT') {
        const r = cur.getBoundingClientRect(), f = document.getElementById('aid-auth-widget-iFrame');
        const o = f && cur.ownerDocument !== document ? f.getBoundingClientRect() : { left: 0, top: 0 };
        return 'tap:' + Math.round(o.left + r.left + Math.min(r.width / 2, 120)) + ',' + Math.round(o.top + r.top + r.height / 2);
      }
      cur.click();
      return 'click';
    }
    return 'moved';
  };

  setInterval(keep, 600);
  keep();
})();
