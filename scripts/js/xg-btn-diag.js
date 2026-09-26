() => {
  const el = document.querySelector('.xgplayer-cssfullscreen');
  if (!el) return { found: false };
  const cs = getComputedStyle(el);
  const r = el.getBoundingClientRect();
  const parent = el.closest('xg-right-grid') || el.parentElement;
  const pcs = parent ? getComputedStyle(parent) : null;
  const bar = document.querySelector('xg-controls');
  const bcs = bar ? getComputedStyle(bar) : null;
  return {
    found: true,
    rect: [Math.round(r.left), Math.round(r.top), Math.round(r.width), Math.round(r.height)],
    display: cs.display,
    visibility: cs.visibility,
    opacity: cs.opacity,
    pointerEvents: cs.pointerEvents,
    offsetParentNull: el.offsetParent === null,
    parentClass: parent ? parent.className : null,
    parentDisplay: pcs ? pcs.display : null,
    parentOpacity: pcs ? pcs.opacity : null,
    barClass: bar ? bar.className : null,
    barDisplay: bcs ? bcs.display : null,
    barOpacity: bcs ? bcs.opacity : null,
    barVisibility: bcs ? bcs.visibility : null,
    rootClass: document.getElementById('player').className
  };
}
