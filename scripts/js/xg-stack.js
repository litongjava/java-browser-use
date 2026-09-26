() => {
  const el = document.querySelector('.xgplayer-cssfullscreen');
  const r = el.getBoundingClientRect();
  const x = r.left + r.width / 2;
  const y = r.top + r.height / 2;
  const stack = document.elementsFromPoint(x, y).slice(0, 8).map(e => ({
    tag: e.tagName,
    cls: String(e.className).slice(0, 50),
    pe: getComputedStyle(e).pointerEvents,
    z: getComputedStyle(e).zIndex
  }));
  // 再看一眼 controls 栏当前状态
  const bar = document.querySelector('xg-controls');
  const inner = document.querySelector('.xg-inner-controls');
  const rightGrid = document.querySelector('.xg-right-grid');
  const ic = document.querySelector('.xgplayer-cssfullscreen');
  return {
    point: [Math.round(x), Math.round(y)],
    stack: stack,
    rootClass: document.getElementById('player').className,
    barClass: bar.className,
    barPE: getComputedStyle(bar).pointerEvents,
    innerPE: inner ? getComputedStyle(inner).pointerEvents : null,
    rightGridPE: rightGrid ? getComputedStyle(rightGrid).pointerEvents : null,
    iconPE: ic ? getComputedStyle(ic).pointerEvents : null,
    iconTransform: ic ? getComputedStyle(ic).transform : null,
    barTransform: getComputedStyle(bar).transform
  };
}
