async () => {
  const wrap = document.getElementById('wrap');
  wrap.style.transform = 'translateZ(0)';
  const el = document.getElementById('player');
  const before = el.getBoundingClientRect();

  // 1) 先在带 transform 的祖先下直接进网页全屏（预期：失效）
  window.__p.fullScreen.request('web');
  await new Promise(r => setTimeout(r, 400));
  const broken = el.getBoundingClientRect();
  const brokenCls = el.className;
  window.__p.fullScreen.cancel('web');
  await new Promise(r => setTimeout(r, 400));

  // 2) 把播放器 DOM 搬到 body 下（脱离被 transform 捕获的祖先），再进网页全屏
  document.body.appendChild(el);
  window.__p.fullScreen.request('web');
  await new Promise(r => setTimeout(r, 400));
  const fixed = el.getBoundingClientRect();
  const fixedCls = el.className;

  return {
    viewport: [window.innerWidth, window.innerHeight],
    before: [Math.round(before.left), Math.round(before.top), Math.round(before.width), Math.round(before.height)],
    underTransform: { cls: brokenCls, rect: [Math.round(broken.left), Math.round(broken.top), Math.round(broken.width), Math.round(broken.height)] },
    afterReparentToBody: { cls: fixedCls, rect: [Math.round(fixed.left), Math.round(fixed.top), Math.round(fixed.width), Math.round(fixed.height)], parent: el.parentElement.tagName },
    docFullscreen: document.fullscreenElement ? String(document.fullscreenElement.className) : null
  };
}
