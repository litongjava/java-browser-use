() => {
  const root = document.getElementById('player');
  const all = Array.from(root.querySelectorAll('*'));
  const cls = Array.from(new Set(all.map(el => (typeof el.className === 'string' ? el.className : '')).filter(Boolean)));
  return {
    ready: window.__ready,
    err: window.__err,
    hlsPlayerGlobal: typeof window.HlsPlayer,
    hlsPlayerKeys: window.HlsPlayer ? Object.keys(window.HlsPlayer) : null,
    playerKeys: window.Player ? Object.keys(window.Player).slice(0, 30) : null,
    rootClass: root.className,
    rootHtmlLen: root.innerHTML.length,
    classNames: cls.slice(0, 80)
  };
}
