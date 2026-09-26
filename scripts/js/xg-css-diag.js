() => {
  const sheets = Array.from(document.styleSheets).map(s => {
    let n = null;
    try { n = s.cssRules ? s.cssRules.length : null; } catch (e) { n = 'blocked'; }
    return { href: (s.href || 'inline').slice(-40), rules: n };
  });
  const bar = document.querySelector('xg-controls');
  const bcs = getComputedStyle(bar);
  const root = document.getElementById('player');
  const rcs = getComputedStyle(root);
  const vid = document.querySelector('video');
  const vcs = getComputedStyle(vid);
  return {
    sheets: sheets,
    bar: { position: bcs.position, zIndex: bcs.zIndex, bottom: bcs.bottom, pointerEvents: bcs.pointerEvents, height: bcs.height },
    root: { position: rcs.position, width: rcs.width, height: rcs.height, background: rcs.backgroundColor },
    video: { position: vcs.position, zIndex: vcs.zIndex, objectFit: vcs.objectFit, width: vcs.width, height: vcs.height },
    hitTest: (() => {
      const el = document.querySelector('.xgplayer-cssfullscreen');
      const r = el.getBoundingClientRect();
      const top = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
      return top ? { tag: top.tagName, cls: String(top.className).slice(0, 60) } : null;
    })()
  };
}
