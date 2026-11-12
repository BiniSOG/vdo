// Runs VDO's injected browser scripts in real Chromium against the local test pages and reports
// what VDO would detect: script reports (VDO.found) plus requests the native side would intercept.
// Usage: node detect_check.js <dir with SNIFF_JS.js and HOOK_JS.js> <base url>
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const dir = process.argv[2];
const base = process.argv[3];
const sniff = fs.readFileSync(path.join(dir, 'SNIFF_JS.js'), 'utf8');
const hook = fs.readFileSync(path.join(dir, 'HOOK_JS.js'), 'utf8');
// Same pattern as MEDIA in BrowserScreen.kt (the native request interceptor).
const MEDIA = /\.(m3u8|mpd|mp4|m4v|webm|mkv|mov|mp3|m4a|aac|ogg|opus|wav|flac)(?:[?#]|$)/i;

const cases = [
  ['share page with only og:video', '/page-meta.html', 'sample-clip.mp4'],
  ['script player (blob video, HLS stream)', '/page-hls.html', 'index.m3u8'],
  ['video fetched by script, no file extension', '/page-fetch.html', '/clip'],
  ['page with a plain video element', '/page-video.html', '/clip'],
];

(async () => {
  const browser = await chromium.launch();
  let failures = 0;
  for (const [name, page, expect] of cases) {
    const ctx = await browser.newContext();
    const found = [];
    await ctx.exposeFunction('__vdoFound', (u, k) => found.push(k + ' ' + u));
    await ctx.addInitScript('window.VDO = { found: function(u, k){ window.__vdoFound(u, k); }, protectedContent: function(){} };');
    await ctx.addInitScript(hook);
    const p = await ctx.newPage();
    p.on('request', r => { if (MEDIA.test(r.url().split('#')[0])) found.push('INTERCEPT ' + r.url()); });
    await p.goto(base + page, { waitUntil: 'load' });
    await p.evaluate(sniff);
    await p.waitForTimeout(3000);
    await p.evaluate(sniff); // the app also rescans while the page loads
    await p.waitForTimeout(500);
    const ok = found.some(f => f.includes(expect));
    if (!ok) failures++;
    console.log((ok ? 'FOUND   ' : 'MISSING ') + name + ' -> ' + JSON.stringify([...new Set(found)]));
    await ctx.close();
  }
  await browser.close();
  process.exit(failures ? 1 : 0);
})();
