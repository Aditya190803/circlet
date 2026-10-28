// Runs only the official RTDB emulator, without installing the full deployment CLI.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const net = require('node:net');
const { spawn } = require('node:child_process');
const { once } = require('node:events');
const { setTimeout: delay } = require('node:timers/promises');
const version = '4.11.2';
const sha256 = 'b70d99344caf17c98b6f910fa8f6edf32a7c016cb1035e8915f70d38901eb97f';

async function main() {
  const cache = path.join(__dirname, '.cache');
  fs.mkdirSync(cache, { recursive: true });
  const jar = path.join(cache, `firebase-database-emulator-v${version}.jar`);
  if (!fs.existsSync(jar)) {
    const response = await fetch(`https://storage.googleapis.com/firebase-preview-drop/emulator/firebase-database-emulator-v${version}.jar`, { signal: AbortSignal.timeout(120000) });
    if (!response.ok) throw new Error(`Emulator download failed: ${response.status}`);
    const bytes = Buffer.from(await response.arrayBuffer());
    if (crypto.createHash('sha256').update(bytes).digest('hex') !== sha256) throw new Error('Emulator checksum mismatch');
    fs.writeFileSync(jar, bytes);
  }
  if (crypto.createHash('sha256').update(fs.readFileSync(jar)).digest('hex') !== sha256) throw new Error('Cached emulator checksum mismatch');
  const probe = net.createServer();
  probe.listen(0, '127.0.0.1');
  await once(probe, 'listening');
  const port = probe.address().port;
  await new Promise((resolve) => probe.close(resolve));
  const log = fs.openSync(path.join(cache, 'emulator.log'), 'w');
  const emulator = spawn('java', ['-Xmx256m', '-jar', jar, '--host', '127.0.0.1', '--port', String(port)], { windowsHide: true, stdio: ['ignore', log, log] });
  let launchError;
  emulator.on('error', (error) => { launchError = error; });
  try {
    let ready = false;
    for (let attempt = 0; attempt < 80; attempt++) {
      if (launchError) throw launchError;
      if (emulator.exitCode !== null) throw new Error('Emulator exited before startup; see .cache/emulator.log');
      try {
        const response = await fetch(`http://127.0.0.1:${port}/.json?ns=demo-circlet-cloud`, { signal: AbortSignal.timeout(1000) });
        if (response.status === 200 || response.status === 401) { ready = true; break; }
      } catch {}
      await delay(250);
    }
    if (!ready) throw new Error('Emulator startup timed out; see .cache/emulator.log');
    const tests = spawn(process.execPath, ['--test', 'rules.test.cjs'], {
      cwd: __dirname, windowsHide: true, stdio: 'inherit',
      env: { ...process.env, FIREBASE_DATABASE_EMULATOR_HOST: `127.0.0.1:${port}` },
    });
    const [code] = await once(tests, 'exit');
    if (code !== 0) throw new Error('Firebase access-rule tests failed');
  } finally {
    emulator.kill();
    fs.closeSync(log);
  }
}
main().catch((error) => { console.error(error.message); process.exitCode = 1; });
