// Records a scripted clip of the study page frame by frame from headless Chrome, with no dependencies (Node 22+).
// The page runs on a virtual clock (?capture), so every frame is exact however long a screenshot takes.
//   node record.mjs [--cut 11|12|13|14] [--seconds N] [--fps 60] [--out frames]
// Cuts: 11 tour, 12 themes, 13 labelled comparisons, 14 the three combinations. 1920x1080. Sound events are rendered to <out>/audio.wav.
// Needs Google Chrome on macOS; set CHROME to another Chromium binary elsewhere.
import { spawn } from 'node:child_process';
import { mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const arg = (n, d) => { const i = process.argv.indexOf('--' + n); return i > 0 ? process.argv[i + 1] : d; };
const W = +arg('w', 1920), H = +arg('h', 1080), CUT = +arg('cut', 11);
const FPS = +arg('fps', 60), MAX = +arg('seconds', 120), OUT = join(here, arg('out', 'frames')), PORT = 9333 + Math.floor(Math.random() * 500);
rmSync(OUT, { recursive:true, force:true }); mkdirSync(OUT, { recursive:true });
const profile = join(tmpdir(), 'rivi-reel-' + Date.now());
const chrome = spawn(process.env.CHROME || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', ['--headless=new', `--remote-debugging-port=${PORT}`, `--user-data-dir=${profile}`,
  `--window-size=${W},${H}`, '--hide-scrollbars', '--force-device-scale-factor=1', '--autoplay-policy=no-user-gesture-required', '--no-first-run', 'about:blank'], { stdio:'ignore' });
const sleep = ms => new Promise(r => setTimeout(r, ms));
try {
  let target; for (let i = 0; i < 50 && !target; i++) { await sleep(200); try { target = (await (await fetch(`http://127.0.0.1:${PORT}/json`)).json()).find(t => t.type === 'page'); } catch {} }
  if (!target) throw new Error('Chrome did not start');
  const ws = new WebSocket(target.webSocketDebuggerUrl); await new Promise((r, j) => { ws.onopen = r; ws.onerror = j; });
  let seq = 0; const pending = new Map();
  ws.onmessage = e => { const m = JSON.parse(e.data); if (m.id && pending.has(m.id)) { const { res, rej } = pending.get(m.id); pending.delete(m.id); m.error ? rej(new Error(m.error.message)) : res(m.result); } };
  const send = (method, params = {}) => new Promise((res, rej) => { const id = ++seq; pending.set(id, { res, rej }); ws.send(JSON.stringify({ id, method, params })); });
  const evaluate = async expr => { const r = await send('Runtime.evaluate', { expression:expr, awaitPromise:true, returnByValue:true }); if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description || r.exceptionDetails.text); return r.result.value; };
  await send('Page.enable'); await send('Runtime.enable');
  await send('Emulation.setDeviceMetricsOverride', { width:W, height:H, deviceScaleFactor:1, mobile:false });
  await send('Page.navigate', { url:'file://' + join(here, 'index.html') + '?capture' });
  await sleep(2500);                                   // real time: images decode, app colours are averaged
  await evaluate(`window.__reel.start(${CUT})`);
  let f = 0, skipped = 0; const t0 = Date.now();
  for (; f < MAX * FPS; f++) {
    const t = f * 1000 / FPS; await evaluate(`window.__reel.step(${t.toFixed(3)})`);
    if (t < await evaluate('window.__reel.t0()')) { skipped++; continue; }           // pre-roll: the page is still settling
    const shot = await send('Page.captureScreenshot', { format:'jpeg', quality:93 });
    writeFileSync(join(OUT, `f${String(f - skipped).padStart(5, '0')}.jpg`), Buffer.from(shot.data, 'base64'));
    if (f % 150 === 0) console.log(`frame ${f}  (${(f / FPS).toFixed(1)} s of video, ${((Date.now() - t0) / 1000).toFixed(0)} s elapsed)`);
    if (await evaluate('window.__reel.done()')) { f++; break; }
  }
  console.log(`captured ${f - skipped} frames = ${((f - skipped) / FPS).toFixed(1)} s (skipped ${skipped} pre-roll frames)`);
  const wav = await evaluate('window.__reel.audio()'); writeFileSync(join(OUT, 'audio.wav'), Buffer.from(wav, 'base64')); console.log(`audio ${(wav.length * 0.75 / 1024).toFixed(0)} KB`);
  ws.close();
} finally { chrome.kill('SIGKILL'); rmSync(profile, { recursive:true, force:true }); }
