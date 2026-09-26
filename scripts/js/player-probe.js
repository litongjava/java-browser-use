() => {
  const root = document.getElementById('player');
  const r = root.getBoundingClientRect();
  const v = document.querySelector('video');
  const nodes = Array.from(root.querySelectorAll('*'));
  const cls = [];
  nodes.forEach(e => { if (typeof e.className === 'string' && e.className) cls.push(e.className); });
  const clickable = nodes
    .filter(e => /full|icon|web/i.test(String(e.className)))
    .map(e => {
      const rr = e.getBoundingClientRect();
      return {
        tag: e.tagName,
        cls: String(e.className).slice(0, 60),
        title: e.getAttribute('title') || e.getAttribute('aria-label') || '',
        w: Math.round(rr.width),
        h: Math.round(rr.height)
      };
    });
  return {
    rootClass: root.className,
    rect: [Math.round(r.left), Math.round(r.top), Math.round(r.width), Math.round(r.height)],
    viewport: [window.innerWidth, window.innerHeight],
    docFullscreenElement: document.fullscreenElement ? String(document.fullscreenElement.className) : null,
    videoSrc: v ? String(v.src).slice(0, 50) : null,
    currentTime: v ? +v.currentTime.toFixed(2) : null,
    paused: v ? v.paused : null,
    clickable: clickable,
    classNames: Array.from(new Set(cls)).slice(0, 60)
  };
}
