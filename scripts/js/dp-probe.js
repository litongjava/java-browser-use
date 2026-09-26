() => {
  const root = document.getElementById('player');
  const full = root.querySelector('.dplayer-full');
  const fullIn = root.querySelector('.dplayer-full-in-icon');
  const v = document.querySelector('video');
  const notice = root.querySelector('.dplayer-notice');
  const fr = full ? full.getBoundingClientRect() : null;
  const ir = fullIn ? fullIn.getBoundingClientRect() : null;
  const ics = fullIn ? getComputedStyle(fullIn) : null;
  return {
    hlsGlobal: typeof window.Hls,
    hlsSupported: window.Hls ? window.Hls.isSupported() : null,
    notice: notice ? notice.textContent.trim().slice(0, 120) : null,
    videoSrc: v ? String(v.src).slice(0, 60) : null,
    currentTime: v ? +v.currentTime.toFixed(2) : null,
    plugins: window.__p && window.__p.plugins ? Object.keys(window.__p.plugins) : null,
    fullRect: fr ? [Math.round(fr.left), Math.round(fr.top), Math.round(fr.width), Math.round(fr.height)] : null,
    fullInRect: ir ? [Math.round(ir.left), Math.round(ir.top), Math.round(ir.width), Math.round(ir.height)] : null,
    fullInDisplay: ics ? ics.display : null,
    fullInPosition: ics ? ics.position : null,
    fullInTop: ics ? ics.top : null,
    rootClass: root.className
  };
}
