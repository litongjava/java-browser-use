async () => {
  const targets = [
    'https://cdn.jsdelivr.net/npm/xgplayer@3.0.19/browser/index.js',
    'https://cdn.jsdelivr.net/npm/xgplayer-hls@3.0.3/dist/index.min.js',
    'https://cdn.jsdelivr.net/npm/hls.js@1.5.17/dist/hls.min.js',
    'https://cdn.jsdelivr.net/npm/artplayer@5.2.2/dist/artplayer.js',
    'https://cdn.jsdelivr.net/npm/dplayer@1.27.1/dist/DPlayer.min.js',
    'https://unpkg.com/nplayer@1.6.3/dist/index.js',
    'https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8',
    'https://test-streams.mux.dev/pts_shift/master.m3u8',
    'https://media.w3.org/2010/05/sintel/trailer.mp4'
  ];
  const out = [];
  for (const u of targets) {
    try {
      const r = await fetch(u, { method: 'GET' });
      out.push({ url: u, status: r.status, ok: r.ok, type: r.headers.get('content-type') });
    } catch (e) {
      out.push({ url: u, error: String(e).slice(0, 120) });
    }
  }
  return out;
}
