() => {
  const lines = document.body.innerText.split('\n');
  const names = lines.filter(l => / \/ /.test(l) && l.length < 60);
  const head = names[0] || '';
  const players = names.filter(n => /播|视|影|弹|幕|媒|流|Player|Video|Media|Live|TV|Bili|Ijk/i.test(n));
  const descHits = [];
  lines.forEach((l, i) => {
    if (/bilibili|哔哩|B站|ijkplayer|ijk ?player|弹幕|播放器/i.test(l) && l.length > 8) {
      descHits.push(lines[i - 1] + ' :: ' + l.slice(0, 150));
    }
  });
  return {
    url: location.href,
    totalLine: head,
    projectCount: names.length - 1,
    playerNames: players,
    descHits: descHits.slice(0, 30)
  };
}
