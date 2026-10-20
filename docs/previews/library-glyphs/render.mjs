// Render review-only native-TV proposals with an existing Chromium installation.
// Usage: CHROME=<path> PREVIEW_TEMP=<E: temporary directory> node render.mjs
import { spawn } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
const here = dirname(fileURLToPath(import.meta.url));
const temp = process.env.PREVIEW_TEMP;
if (!temp) throw new Error('Set PREVIEW_TEMP to a temporary directory on E:.');
const profile = resolve(temp, `library-glyphs-chromium-${Date.now()}`);
if (!/^E:[\\/]/i.test(profile)) throw new Error('Browser profile must remain on E:.');
mkdirSync(profile, { recursive: true });
const chrome = spawn(process.env.CHROME || 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  ['--headless=new', '--remote-debugging-port=9432', `--user-data-dir=${profile}`, '--window-size=1920,1080', '--hide-scrollbars', '--force-device-scale-factor=1', '--no-first-run', 'about:blank'],
  { stdio: 'ignore' });
const pause = ms => new Promise(r => setTimeout(r, ms));
let ws;
try {
  let target;
  for (let i = 0; i < 50 && !target; i++) {
    await pause(100);
    try { target = (await (await fetch('http://127.0.0.1:9432/json')).json()).find(t => t.type === 'page'); } catch {}
  }
  if (!target) throw new Error('Chromium did not start');
  ws = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((r,j) => { ws.onopen=r; ws.onerror=j; });
  let sequence=0;
  const pending = new Map();
  ws.onmessage = event => {
    const data=JSON.parse(event.data), entry=pending.get(data.id);
    if (entry) { pending.delete(data.id); data.error ? entry.reject(new Error(data.error.message)) : entry.resolve(data.result); }
  };
  const send = (method,params={}) => new Promise((resolve,reject) => {
    const id=++sequence; pending.set(id,{resolve,reject}); ws.send(JSON.stringify({id,method,params}));
  });
  await send('Page.enable');
  await send('Emulation.setDeviceMetricsOverride', {width:1920,height:1080,deviceScaleFactor:1,mobile:false});
  for (const [screen,file] of [['comparison','comparison.png'],['library','library.png']]) {
    await send('Page.navigate', {url:pathToFileURL(join(here,'index.html')).href+'?screen='+screen});
    await pause(300);
    const loaded = await send('Runtime.evaluate', {expression:'Promise.all(Array.from(document.images, image => image.decode().catch(() => null))).then(() => ({width:innerWidth,height:innerHeight,missing:Array.from(document.images).filter(i => i.offsetParent && !i.naturalWidth).map(i => i.src)}))',awaitPromise:true,returnByValue:true});
    if (loaded.result.value?.missing?.length) throw new Error('Missing images: '+JSON.stringify(loaded.result.value.missing));
    await send('Runtime.evaluate', {expression:'new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))',awaitPromise:true});
    await pause(300);
    const shot=await send('Page.captureScreenshot',{format:'png'});
    writeFileSync(join(here,file),Buffer.from(shot.data,'base64'));
    console.log(`${file}: ${JSON.stringify(loaded.result.value)}`);
  }
} finally {
  if (ws) ws.close();
  chrome.kill();
  console.log(`Temporary browser profile retained on E: ${profile}`);
}
