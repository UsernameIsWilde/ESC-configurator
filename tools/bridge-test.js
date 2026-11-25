// Exercises android-bridge.js against a fake native side, the way the web
// app's Serial class uses the Web Serial API.
const fs = require('fs');
const assert = require('assert');

const log = [];
let devices = [{ id: 7, vendorId: 0x0483, productId: 0x5740, name: 'STM32', hasPermission: true }];
const b64 = (bytes) => Buffer.from(bytes).toString('base64');

const native = {
  listPorts: () => JSON.stringify(devices),
  requestPort: (callId) => setTimeout(() => window.__escAndroid.result(callId, null, devices[0]), 1),
  open: (callId, id, baud, dataBits, stopBits, parity) => {
    log.push(['open', id, baud, dataBits, stopBits, parity]);
    setTimeout(() => window.__escAndroid.result(callId, baud === 1200 ? 'Could not open the device' : null), 1);
  },
  write: (callId, id, data) => {
    log.push(['write', id, data]);
    setTimeout(() => {
      window.__escAndroid.result(callId, null);
      // Echo in two chunks, like USB packets
      const bytes = Buffer.from(data, 'base64');
      window.__escAndroid.data(id, b64(bytes.subarray(0, 2)));
      window.__escAndroid.data(id, b64(bytes.subarray(2)));
    }, 1);
  },
  close: (callId, id) => { log.push(['close', id]); setTimeout(() => window.__escAndroid.result(callId, null), 1); },
  saveFile: (name, data) => log.push(['saveFile', name, data]),
  openExternal: (url) => log.push(['openExternal', url]),
  setChromeColor: (color) => log.push(['setChromeColor', color]),
};

global.window = global;
global.EscAndroid = native;
global.navigator = {};
global.location = { href: 'https://appassets.androidplatform.net/' };
let cookie = '';
global.document = {
  addEventListener: () => {},
  get cookie() { return cookie; },
  set cookie(v) { cookie = v; },
};
global.HTMLAnchorElement = function () {};
global.HTMLAnchorElement.prototype.click = function () { log.push(['nativeClick']); };

(0, eval)(fs.readFileSync(process.argv[2], 'utf8'));

async function readBytes(reader, count) {
  const out = [];
  while (out.length < count) {
    const { value, done } = await reader.read();
    if (done) break;
    out.push(...value);
  }
  return out;
}

(async () => {
  const serial = navigator.serial;
  assert.ok(serial, 'navigator.serial defined');
  assert.ok(/rcl_consent_given=true/.test(cookie), 'cookie banner answered');

  // caches are neutralised
  const cache = await window.caches.open('v1');
  assert.strictEqual(await cache.match('x'), undefined);
  await cache.put('x', {});

  let ports = await serial.getPorts();
  assert.strictEqual(ports.length, 1);
  const port = ports[0];
  assert.deepStrictEqual(port.getInfo(), { usbVendorId: 0x0483, usbProductId: 0x5740 });
  assert.strictEqual((await serial.getPorts())[0], port, 'same object every time');
  assert.strictEqual(await serial.requestPort(), port);
  assert.strictEqual(port.readable, null);

  // baud rate arrives as a string from the <select>
  await port.open({ baudRate: '115200' });
  assert.deepStrictEqual(log.pop(), ['open', 7, 115200, 8, 1, 'none']);
  await assert.rejects(port.open({ baudRate: 115200 }), { name: 'InvalidStateError' });

  let writer = await port.writable.getWriter();
  let reader = await port.readable.getReader();

  await writer.write(new Uint8Array([36, 77, 60, 0, 1, 1]).buffer);   // ArrayBuffer
  assert.deepStrictEqual(await readBytes(reader, 6), [36, 77, 60, 0, 1, 1]);
  await writer.write(new Uint8Array([9, 8, 7, 6, 5]).subarray(1, 4)); // view with offset
  assert.deepStrictEqual(await readBytes(reader, 3), [8, 7, 6]);

  // Serial.close() of the web app
  reader.cancel();
  await reader.releaseLock();
  await writer.releaseLock();
  await port.close();
  assert.deepStrictEqual(log.pop(), ['close', 7]);
  assert.strictEqual(port.readable, null);
  await assert.rejects(port.close(), { name: 'InvalidStateError' });

  // Re-open, then the device is pulled while a read is pending
  await port.open({ baudRate: 57600 });
  writer = port.writable.getWriter();
  reader = port.readable.getReader();
  await writer.write(new Uint8Array([1, 2, 3]));
  assert.deepStrictEqual(await readBytes(reader, 3), [1, 2, 3]);

  const events = [];
  serial.addEventListener('disconnect', () => events.push('disconnect'));
  serial.addEventListener('connect', () => events.push('connect'));

  const pendingRead = reader.read();
  devices = [];
  window.__escAndroid.detached(7);
  await assert.rejects(pendingRead, { name: 'NetworkError' });
  assert.deepStrictEqual(events, ['disconnect']);
  assert.deepStrictEqual(await serial.getPorts(), []);
  window.__escAndroid.detached(7);                   // unknown device: no event
  assert.deepStrictEqual(events, ['disconnect']);

  // Plugged back in (new device id), permission granted
  devices = [{ id: 9, vendorId: 0x0483, productId: 0x5740, name: 'STM32', hasPermission: true }];
  window.__escAndroid.attached(devices[0]);
  assert.deepStrictEqual(events, ['disconnect', 'connect']);
  ports = await serial.getPorts();
  assert.strictEqual(ports.length, 1);
  assert.notStrictEqual(ports[0], port);

  // Failing open leaves the port closed and usable
  await assert.rejects(ports[0].open({ baudRate: 1200 }), { name: 'NetworkError' });
  await ports[0].open({ baudRate: 115200 });

  // Read error reported by native code
  const reader2 = ports[0].readable.getReader();
  const pending2 = reader2.read();
  window.__escAndroid.ioError(9, 'USB get_status request failed');
  await assert.rejects(pending2, /get_status/);
  assert.strictEqual(ports[0].readable, null);

  // Devices without permission are not listed
  devices = [{ id: 11, vendorId: 1, productId: 2, name: 'x', hasPermission: false }];
  assert.deepStrictEqual(await serial.getPorts(), []);

  // window.open goes to the system browser
  window.open('https://github.com/bird-sanctuary/bluejay');
  assert.deepStrictEqual(log.pop(), ['openExternal', 'https://github.com/bird-sanctuary/bluejay']);

  // blob download through a.click()
  global.fetch = async () => ({ arrayBuffer: async () => new Uint8Array([58, 49, 48]).buffer });
  const a = new HTMLAnchorElement();
  a.hasAttribute = (n) => n === 'download';
  a.href = 'blob:https://appassets.androidplatform.net/1234';
  a.download = 'dump.hex';
  a.click();
  await new Promise((r) => setTimeout(r, 5));
  assert.deepStrictEqual(log.pop(), ['saveFile', 'dump.hex', b64([58, 49, 48])]);

  console.log('bridge test: all assertions passed');
})().catch((e) => { console.error('FAILED', e); process.exit(1); });
