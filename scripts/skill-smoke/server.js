const http = require('http');
const fs = require('fs');
const path = require('path');

const root = process.argv[2] || __dirname;
const port = Number(process.argv[3] || 10054);

http.createServer((req, res) => {
  const url = req.url.split('?')[0];
  // 供网络拦截用例使用:被 mock / abort 的目标
  if (url === '/api/ping') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end('{"pong":true}');
    return;
  }
  const file = path.join(root, url === '/' ? 'test.html' : url);
  fs.readFile(file, (err, data) => {
    if (err) {
      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('not found');
      return;
    }
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    res.end(data);
  });
}).listen(port, () => console.log('skill-smoke 测试页服务: http://127.0.0.1:' + port + '/test.html'));
