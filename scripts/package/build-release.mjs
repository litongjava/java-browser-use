#!/usr/bin/env node
/**
 * 构建「单文件发行版」:把目标平台的 Chromium 内嵌进可执行 jar。
 *
 * 用户拿到一个 jar,`java -jar deepseek-browser-use-<版本>-<平台>.jar` 就能跑,
 * 不需要再下载任何浏览器文件 —— 首次启动时程序会把内嵌的 Chromium 解压到
 * ~/.cache/deepseek-browser-use 下,之后直接用它启动。
 *
 * 用法(在仓库根目录执行):
 *   node scripts/package/build-release.mjs                     # 当前平台
 *   node scripts/package/build-release.mjs --platform=all      # 全部五个平台
 *   node scripts/package/build-release.mjs --platform=linux-x64 --offline
 *   node scripts/package/build-release.mjs --platform=windows-x64 --revision=1179
 *
 * 产物:dist/deepseek-browser-use-<版本>-<平台>.jar
 *
 * 各平台的 Chromium 只在构建时从 Playwright 官方 CDN 下载一次,缓存在
 * playwright-server/build/browser-cache/ 下,重复构建不会重复下载。
 */
import { createWriteStream, existsSync } from 'node:fs';
import { mkdir, open, readFile, readdir, rm, stat, writeFile, copyFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { inflateRawSync } from 'node:zlib';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const SCRIPT_DIR = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(SCRIPT_DIR, '..', '..');
const SERVER_DIR = path.join(REPO_ROOT, 'playwright-server');
const BUILD_DIR = path.join(SERVER_DIR, 'build');
const CACHE_DIR = path.join(BUILD_DIR, 'browser-cache');
const STAGE_DIR = path.join(BUILD_DIR, 'browsers');
const DIST_DIR = path.join(REPO_ROOT, 'dist');

const CDN = 'https://cdn.playwright.dev/dbazure/download/playwright/builds/chromium';

/**
 * 发行平台表
 *
 * cdn: Playwright CDN 上的平台名(chromium-<cdn>.zip)
 * driver: Playwright driver-bundle 里的平台目录名,打包时只保留这一个平台的 node
 * build: 内嵌目录的构建标识,同时决定用户机器上的解压目录,换平台不会互相覆盖
 * executable: 解压后 Chromium 可执行文件相对路径
 */
const PLATFORMS = {
  'windows-x64': {
    cdn: 'win64',
    driver: 'win32_x64',
    build: 'chromium-{revision}-win64',
    executable: 'chrome-win/chrome.exe',
  },
  'linux-x64': {
    cdn: 'linux',
    driver: 'linux',
    build: 'chromium-{revision}-linux',
    executable: 'chrome-linux/chrome',
  },
  'linux-arm64': {
    cdn: 'linux-arm64',
    driver: 'linux-arm64',
    build: 'chromium-{revision}-linux-arm64',
    executable: 'chrome-linux/chrome',
  },
  'macos-x64': {
    cdn: 'mac',
    driver: 'mac',
    build: 'chromium-{revision}-mac',
    executable: 'chrome-mac/Chromium.app/Contents/MacOS/Chromium',
  },
  'macos-arm64': {
    cdn: 'mac-arm64',
    driver: 'mac-arm64',
    build: 'chromium-{revision}-mac-arm64',
    executable: 'chrome-mac/Chromium.app/Contents/MacOS/Chromium',
  },
};

/**
 * 平台别名
 *
 * 一个人说「打 mac 包」通常是指两个架构都要,所以 macos / linux 这种家族名展开成全部架构,
 * 不用记 -x64 / -arm64 这两个后缀。
 */
const PLATFORM_ALIASES = {
  macos: ['macos-x64', 'macos-arm64'],
  mac: ['macos-x64', 'macos-arm64'],
  darwin: ['macos-x64', 'macos-arm64'],
  linux: ['linux-x64', 'linux-arm64'],
  windows: ['windows-x64'],
  win: ['windows-x64'],
};

/** 把 --platform 的值展开成要构建的平台列表 */
function resolvePlatforms(value) {
  if (!value) return [currentPlatform()];
  if (value === 'all') return Object.keys(PLATFORMS);
  if (PLATFORM_ALIASES[value]) return PLATFORM_ALIASES[value];
  if (!PLATFORMS[value]) {
    throw new Error(`不支持的平台:${value}\n可选:all, ${Object.keys(PLATFORMS).join(', ')}`
      + `\n别名:${Object.keys(PLATFORM_ALIASES).join(', ')}`);
  }
  return [value];
}

function parseArgs(argv) {
  const args = { platform: null, revision: null, offline: false, skipTests: true, keepStaging: false };
  for (const raw of argv) {
    if (raw === '--offline') args.offline = true;
    else if (raw === '--keep-staging') args.keepStaging = true;
    else if (raw === '--with-tests') args.skipTests = false;
    else if (raw.startsWith('--platform=')) args.platform = raw.slice('--platform='.length);
    else if (raw.startsWith('--revision=')) args.revision = raw.slice('--revision='.length);
    else if (raw === '--help' || raw === '-h') {
      console.log('用法: node scripts/package/build-release.mjs [--platform=<平台>] [--revision=N] [--offline] [--with-tests]');
      console.log('  平台: all | ' + Object.keys(PLATFORMS).join(' | '));
      console.log('  别名: macos / linux 展开成两个架构,windows 等价于 windows-x64');
      console.log('  不带 --platform 时打当前构建机对应的平台');
      process.exit(0);
    } else {
      throw new Error('不认识的参数:' + raw);
    }
  }
  return args;
}

/** 当前构建机的默认平台 */
function currentPlatform() {
  const arch = process.arch === 'arm64' ? 'arm64' : 'x64';
  if (process.platform === 'win32') return 'windows-x64';
  if (process.platform === 'darwin') return 'macos-' + arch;
  return 'linux-' + arch;
}

/** 从 pom 里取项目版本,从 browser.properties 里取 Chromium 修订号 */
async function readBuildInputs(revisionOverride) {
  const pom = await readFile(path.join(REPO_ROOT, 'pom.xml'), 'utf8');
  const versionMatch = pom.match(/<version>([^<]+)<\/version>/);
  if (!versionMatch) throw new Error('读不到根 pom 的 version');
  const version = versionMatch[1].trim();

  const props = await readFile(path.join(SERVER_DIR, 'src', 'main', 'resources', 'browser.properties'), 'utf8');
  const revisionMatch = props.match(/^\s*chromium\.revision\s*=\s*(\S+)\s*$/m);
  if (!revisionMatch) throw new Error('browser.properties 里没有 chromium.revision');
  const revision = revisionOverride || revisionMatch[1].trim();

  return { version, revision };
}

function run(command, args, options = {}) {
  const result = spawnSync(command, args, { stdio: 'inherit', shell: false, ...options });
  if (result.error) throw result.error;
  if (result.status !== 0) {
    throw new Error(`${command} ${args.join(' ')} 退出码 ${result.status}`);
  }
}

/** 下载到缓存目录,已经下过就直接用;网络中断时自动重试 */
async function downloadChromium(revision, cdn) {
  await mkdir(CACHE_DIR, { recursive: true });
  const zipPath = path.join(CACHE_DIR, `chromium-${cdn}-${revision}.zip`);
  const marker = `${zipPath}.ok`;
  try {
    const info = await stat(zipPath);
    await stat(marker);
    console.log(`  使用缓存 ${path.relative(REPO_ROOT, zipPath)} (${mb(info.size)} MB)`);
    return zipPath;
  } catch {
    // 缓存不完整,重新下载
  }

  const url = `${CDN}/${revision}/chromium-${cdn}.zip`;
  const attempts = 3;
  let lastError;
  for (let attempt = 1; attempt <= attempts; attempt++) {
    console.log(`  下载 ${url}${attempt > 1 ? ` (第 ${attempt}/${attempts} 次尝试)` : ''}`);
    try {
      await fetchToFile(url, zipPath);
      return zipPath;
    } catch (error) {
      lastError = error;
      // 半截的 zip 一定要删掉,否则下次会拿它当缓存
      await rm(zipPath, { force: true });
      await rm(marker, { force: true });
      if (attempt < attempts) {
        const wait = attempt * 5;
        console.log(`  下载中断(${error.message}),${wait}s 后重试`);
        await new Promise(resolve => setTimeout(resolve, wait * 1000));
      }
    }
  }
  throw new Error(`下载失败(${attempts} 次都没成功):${url} -- ${lastError && lastError.message}`);
}

/** 单次下载:边下边算 sha256,带背压控制,避免大文件把内存吃爆 */
async function fetchToFile(url, zipPath) {
  const response = await fetch(url);
  if (!response.ok) {
    throw new Error(`HTTP ${response.status} ${response.statusText}`);
  }
  const total = Number(response.headers.get('content-length') || 0);
  const hash = createHash('sha256');
  let received = 0;
  let lastLogged = 0;
  const out = createWriteStream(zipPath);
  const done = new Promise((resolve, reject) => {
    out.on('error', reject);
    out.on('finish', resolve);
  });
  for await (const chunk of response.body) {
    hash.update(chunk);
    received += chunk.length;
    if (!out.write(chunk)) {
      await new Promise(resolve => out.once('drain', resolve));
    }
    if (received - lastLogged > 16 * 1024 * 1024) {
      lastLogged = received;
      const percent = total ? ` (${Math.round((received / total) * 100)}%)` : '';
      console.log(`    ${mb(received)} MB${percent}`);
    }
  }
  out.end();
  await done;
  if (total && received !== total) {
    throw new Error(`下载不完整:期望 ${mb(total)} MB,实际 ${mb(received)} MB`);
  }
  await writeFile(`${zipPath}.ok`, hash.digest('hex'));
  console.log(`  下载完成 ${mb(received)} MB`);
}

/**
 * 解压 zip
 *
 * 这里**不把退出码当成功标准**。macOS 的 zip 里带符号链接(Chromium.app 的 framework 结构:
 * {@code Versions/Current -> 138.0.7204.23} 之类),而 Windows 上创建符号链接需要管理员特权,
 * tar 会跳过这几条并以 1 退出。调用方会在解压后按中央目录清单逐个校验文件是否落地,
 * 符号链接则记进 symlinks.txt,由 BundledBrowser 在目标机器上重建。
 */
function extractZip(zipPath, targetDir) {
  if (process.platform === 'win32') {
    spawnSync('tar', ['-xf', zipPath, '-C', targetDir], { stdio: 'inherit' });
    return;
  }
  const unzip = spawnSync('unzip', ['-q', '-o', zipPath, '-d', targetDir], { stdio: 'inherit' });
  if (!unzip.error && unzip.status === 0) return;
  spawnSync('tar', ['-xf', zipPath, '-C', targetDir], { stdio: 'inherit' });
}

const S_IFMT = 0xf000;
const S_IFLNK = 0xa000;

/**
 * 读 zip 的中央目录
 *
 * 需要的信息(java.util.zip 与 jdk.zipfs 都不暴露):每个条目在 Unix 权限位里的文件类型,
 * 用来认出符号链接。zip64 的 zip 这里不支持 —— Chromium 的包远不到 4GB / 65535 个条目,
 * 真遇到了会明确报错而不是静默读错。
 */
async function readZipEntries(zipPath) {
  const fd = await open(zipPath, 'r');
  try {
    const { size } = await fd.stat();
    const tailLength = Math.min(size, 66 * 1024);
    const tail = Buffer.alloc(tailLength);
    await fd.read(tail, 0, tailLength, size - tailLength);
    let eocd = -1;
    for (let i = tail.length - 22; i >= 0; i--) {
      if (tail.readUInt32LE(i) === 0x06054b50) {
        eocd = i;
        break;
      }
    }
    if (eocd < 0) {
      throw new Error(`不是合法的 zip:${zipPath}`);
    }
    const count = tail.readUInt16LE(eocd + 10);
    if (count === 0xffff) {
      throw new Error(`zip64 暂不支持:${zipPath}`);
    }
    const entries = [];
    const header = Buffer.alloc(46);
    let pos = tail.readUInt32LE(eocd + 16);
    for (let i = 0; i < count; i++) {
      await fd.read(header, 0, 46, pos);
      if (header.readUInt32LE(0) !== 0x02014b50) {
        throw new Error(`中央目录记录签名不对(第 ${i} 条):${zipPath}`);
      }
      const nameLength = header.readUInt16LE(28);
      const name = Buffer.alloc(nameLength);
      await fd.read(name, 0, nameLength, pos + 46);
      entries.push({
        name: name.toString('utf8'),
        mode: header.readUInt32LE(38) >>> 16,
        method: header.readUInt16LE(10),
        compressedSize: header.readUInt32LE(20),
        localOffset: header.readUInt32LE(42),
      });
      pos += 46 + nameLength + header.readUInt16LE(30) + header.readUInt16LE(32);
    }
    return entries;
  } finally {
    await fd.close();
  }
}

/** 读单个条目的内容(符号链接的内容就是链接目标) */
async function readZipEntryData(zipPath, entry) {
  const fd = await open(zipPath, 'r');
  try {
    const local = Buffer.alloc(30);
    await fd.read(local, 0, 30, entry.localOffset);
    const dataStart = entry.localOffset + 30 + local.readUInt16LE(26) + local.readUInt16LE(28);
    const raw = Buffer.alloc(entry.compressedSize);
    await fd.read(raw, 0, entry.compressedSize, dataStart);
    return entry.method === 0 ? raw : inflateRawSync(raw);
  } finally {
    await fd.close();
  }
}

/** 递归列出目录下的文件,返回相对路径(正斜杠) */
async function listFiles(dir, prefix = '') {
  const entries = await readdir(dir, { withFileTypes: true });
  const files = [];
  for (const entry of entries) {
    const relative = prefix ? `${prefix}/${entry.name}` : entry.name;
    if (entry.isDirectory()) {
      files.push(...(await listFiles(path.join(dir, entry.name), relative)));
    } else if (entry.isFile()) {
      files.push(relative);
    }
  }
  return files;
}

/**
 * 准备内嵌目录
 *
 * 生成 index.txt(文件清单,BundledBrowser 靠它枚举 jar 里的资源)、meta.properties
 * (构建标识 + 可执行文件相对路径),以及 symlinks.txt(符号链接清单,只有 macOS 的包才有)。
 */
async function stageBrowser(platform, revision) {
  const config = PLATFORMS[platform];
  const build = config.build.replace('{revision}', revision);

  await rm(STAGE_DIR, { recursive: true, force: true });
  await mkdir(STAGE_DIR, { recursive: true });

  const zipPath = await downloadChromium(revision, config.cdn);
  const entries = await readZipEntries(zipPath);
  const files = entries.filter(entry => (entry.mode & S_IFMT) !== S_IFLNK && !entry.name.endsWith('/'));
  const links = entries.filter(entry => (entry.mode & S_IFMT) === S_IFLNK);
  if (links.length > 0) {
    console.log(`  zip 里有 ${links.length} 个符号链接,记进 symlinks.txt 由目标机器重建`);
  }

  console.log(`  解压到 ${path.relative(REPO_ROOT, STAGE_DIR)}`);
  extractZip(zipPath, STAGE_DIR);

  // 不信任解压工具的退出码,按中央目录清单核对每个文件是不是真的落地了
  const missing = files.filter(entry => !existsSync(path.join(STAGE_DIR, entry.name)));
  if (missing.length > 0) {
    throw new Error(`解压后缺少 ${missing.length} 个文件,例如:${missing.slice(0, 3).map(m => m.name).join(', ')}`);
  }

  if (links.length > 0) {
    const lines = [];
    for (const link of links) {
      const target = (await readZipEntryData(zipPath, link)).toString('utf8');
      lines.push(`${link.name}\t${target}`);
    }
    await writeFile(path.join(STAGE_DIR, 'symlinks.txt'), lines.join('\n') + '\n', 'utf8');
  }

  const staged = await listFiles(STAGE_DIR);
  const executable = staged.find(file => file === config.executable);
  if (!executable) {
    throw new Error(`解压结果里没有 ${config.executable},实际顶层目录:${[...new Set(staged.map(f => f.split('/')[0]))].join(', ')}`);
  }

  staged.sort();
  await writeFile(path.join(STAGE_DIR, 'index.txt'), staged.join('\n') + '\n', 'utf8');
  await writeFile(path.join(STAGE_DIR, 'meta.properties'),
    `build=${build}\nexecutable=${executable}\nplatform=${platform}\nrevision=${revision}\n`, 'utf8');
  console.log(`  内嵌 ${staged.length} 个文件,可执行文件 ${executable}`);
  return build;
}

/** 打包并产出 dist 下的成品 jar */
async function packageJar(platform, version, build, args) {
  const config = PLATFORMS[platform];
  const mavenArgs = [
    '-Pproduction',
    `-Ddriver.platform=${config.driver}`,
    `-DskipTests=${args.skipTests}`,
    'clean',
    'package',
  ];
  if (args.offline) mavenArgs.unshift('-o');
  console.log(`  mvn ${mavenArgs.join(' ')}`);
  run(process.platform === 'win32' ? 'mvn.cmd' : 'mvn', mavenArgs, { cwd: SERVER_DIR, shell: process.platform === 'win32' });

  const targetDir = path.join(SERVER_DIR, 'target');
  // shade 会把打之前的那份留成 original-<name>.jar,只取成品
  const jars = (await readdir(targetDir)).filter(name => name.endsWith('.jar')
    && !name.startsWith('original-') && !name.endsWith('.original'));
  if (jars.length !== 1) {
    throw new Error(`target 下期望恰好一个成品 jar,实际:${jars.join(', ')}`);
  }
  await mkdir(DIST_DIR, { recursive: true });
  const outputName = `deepseek-browser-use-${version}-${platform}.jar`;
  const outputPath = path.join(DIST_DIR, outputName);
  await copyFile(path.join(targetDir, jars[0]), outputPath);
  await verifyJar(outputPath, platform);
  const info = await stat(outputPath);
  console.log(`  产物 dist/${outputName} (${mb(info.size)} MB, 内嵌 ${build}, driver ${config.driver})`);
  return outputPath;
}

/**
 * 校验成品 jar 里该有的东西都在
 *
 * 这一步挡的是打包配置改坏的情况:shade 的过滤器一旦把 Playwright 的 driver 类或目标平台的
 * node 滤掉,jar 照样能构建成功,但要等到用户 java -jar 起浏览器时才会报
 * ClassNotFoundException / Failed to create driver。这里在构建阶段就拦住。
 */
async function verifyJar(jarPath, platform) {
  const config = PLATFORMS[platform];
  const required = [
    'nexus/io/ai/browser/PlaywrightApp.class',
    'com/microsoft/playwright/impl/driver/jar/DriverJar.class',
    `driver/${config.driver}/package/package.json`,
    'browsers/index.txt',
    'browsers/meta.properties',
  ];
  // macOS 的 Chromium.app 靠 framework 里的符号链接才起得来,而构建机(比如 Windows)
  // 可能没权限创建它们,所以改成打包 symlinks.txt、运行时重建。缺了它 mac 包一定是坏的。
  if (platform.startsWith('macos-')) {
    required.push('browsers/symlinks.txt');
  }
  const { execFileSync } = await import('node:child_process');
  const listing = execFileSync(process.platform === 'win32' ? 'tar.exe' : 'tar',
    ['-tf', jarPath], { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
  const entries = new Set(listing.split('\n').map(line => line.trim()));
  const missing = required.filter(entry => !entries.has(entry));
  if (missing.length > 0) {
    throw new Error(`成品 jar 里缺少必需条目:${missing.join(', ')}`);
  }
  // 内嵌浏览器必须真的有可执行文件,不只是清单
  const hasExecutable = [...entries].some(entry => entry === 'browsers/' + config.executable);
  if (!hasExecutable) {
    throw new Error(`成品 jar 里没有内嵌浏览器可执行文件 browsers/${config.executable}`);
  }
  // 另外 4 个平台的 driver 不该被打进来
  const foreign = [...entries].filter(entry => /^driver\/[^/]+\/node(\.exe)?$/.test(entry)
    && entry !== `driver/${config.driver}/node` && entry !== `driver/${config.driver}/node.exe`);
  if (foreign.length > 0) {
    throw new Error(`成品 jar 里混进了其它平台的 driver:${foreign.join(', ')}`);
  }
}

function mb(bytes) {
  return (bytes / 1024 / 1024).toFixed(1);
}

async function buildOne(platform, version, revision, args) {
  console.log(`\n=== ${platform} ===`);
  const build = await stageBrowser(platform, revision);
  const jar = await packageJar(platform, version, build, args);
  if (!args.keepStaging) {
    await rm(STAGE_DIR, { recursive: true, force: true });
  }
  return jar;
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const { version, revision } = await readBuildInputs(args.revision);
  const platforms = resolvePlatforms(args.platform);

  console.log(`deepseek-browser-use ${version}  平台:${platforms.join(', ')}  Chromium 修订号:${revision}`);
  const jars = [];
  for (const platform of platforms) {
    jars.push(await buildOne(platform, version, revision, args));
  }
  console.log('\n完成:');
  for (const jar of jars) {
    console.log('  ' + path.relative(REPO_ROOT, jar));
  }
}

main().catch(error => {
  console.error('\n构建失败:' + error.message);
  process.exit(1);
});
