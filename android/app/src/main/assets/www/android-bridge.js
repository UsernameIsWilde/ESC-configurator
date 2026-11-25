/*
 * Android bridge for ESC Configurator.
 *
 * Loaded before the web app's own bundle. It gives the unmodified web app
 * what an Android WebView lacks:
 *
 *  - navigator.serial (Web Serial API), implemented on top of the native USB
 *    host bridge exposed as window.EscAndroid
 *  - file downloads (blob: links) through the native "save as" dialog
 *
 * It also switches off the web level caches and the service worker: the
 * native layer serves bundled firmware and keeps its own disk cache, so it is
 * the single source of truth for what is available offline.
 */
(function () {
  'use strict';

  var native = window.EscAndroid;
  if (!native) {
    return;
  }

  /* ---------------------------------------------------------------------
   * Helpers
   * ------------------------------------------------------------------- */

  function toBytes(chunk) {
    if (chunk instanceof ArrayBuffer) {
      return new Uint8Array(chunk);
    }

    if (ArrayBuffer.isView(chunk)) {
      return new Uint8Array(chunk.buffer, chunk.byteOffset, chunk.byteLength);
    }

    throw new TypeError('Expected an ArrayBuffer or an ArrayBufferView');
  }

  function toBase64(bytes) {
    var CHUNK = 0x8000;
    var binary = '';
    for (var i = 0; i < bytes.length; i += CHUNK) {
      binary += String.fromCharCode.apply(null, bytes.subarray(i, i + CHUNK));
    }

    return btoa(binary);
  }

  function fromBase64(base64) {
    var binary = atob(base64);
    var bytes = new Uint8Array(binary.length);
    for (var i = 0; i < binary.length; i += 1) {
      bytes[i] = binary.charCodeAt(i);
    }

    return bytes;
  }

  function domError(message, name) {
    try {
      return new DOMException(message, name);
    } catch (e) {
      var error = new Error(message);
      error.name = name;

      return error;
    }
  }

  /* ---------------------------------------------------------------------
   * Asynchronous calls into native code
   *
   * Every call gets an id, native code answers through
   * window.__escAndroid.result(id, error, value).
   * ------------------------------------------------------------------- */

  var pendingCalls = {};
  var nextCallId = 1;

  function nativeCall(invoke) {
    return new Promise(function (resolve, reject) {
      var callId = nextCallId;
      nextCallId += 1;

      pendingCalls[callId] = {
        resolve: resolve,
        reject: reject,
      };

      try {
        invoke(callId);
      } catch (e) {
        delete pendingCalls[callId];
        reject(e);
      }
    });
  }

  /* ---------------------------------------------------------------------
   * SerialPort
   * ------------------------------------------------------------------- */

  var ports = {};

  class AndroidSerialPort extends EventTarget {
    constructor(info) {
      super();

      this._id = info.id;
      this._vendorId = info.vendorId;
      this._productId = info.productId;
      this._state = 'closed';
      this._readable = null;
      this._writable = null;
      this._readController = null;

      this.onconnect = null;
      this.ondisconnect = null;
    }

    getInfo() {
      return {
        usbVendorId: this._vendorId,
        usbProductId: this._productId,
      };
    }

    get readable() {
      if (this._state !== 'open') {
        return null;
      }

      if (!this._readable) {
        var port = this;
        this._readable = new ReadableStream({
          start: function (controller) {
            port._readController = controller;
          },
          cancel: function () {
            // Like the real thing: a cancelled stream is replaced by a fresh
            // one the next time port.readable is accessed.
            port._readController = null;
            port._readable = null;
          },
        });
      }

      return this._readable;
    }

    get writable() {
      if (this._state !== 'open') {
        return null;
      }

      if (!this._writable) {
        var port = this;
        var forget = function () {
          port._writable = null;
        };

        this._writable = new WritableStream({
          write: function (chunk) {
            var data = toBase64(toBytes(chunk));

            return nativeCall(function (callId) {
              native.write(callId, port._id, data);
            }).catch(function (e) {
              throw domError(String(e && e.message || e), 'NetworkError');
            });
          },
          close: forget,
          abort: forget,
        });
      }

      return this._writable;
    }

    async open(options) {
      var baudRate = options ? Number(options.baudRate) : NaN;
      if (!baudRate || baudRate < 0) {
        throw new TypeError("Failed to execute 'open' on 'SerialPort': required member baudRate is undefined.");
      }

      if (this._state !== 'closed') {
        throw domError('The port is already open.', 'InvalidStateError');
      }

      var port = this;
      var dataBits = Number(options.dataBits) || 8;
      var stopBits = Number(options.stopBits) || 1;
      var parity = options.parity || 'none';

      this._state = 'opening';
      try {
        await nativeCall(function (callId) {
          native.open(callId, port._id, baudRate, dataBits, stopBits, parity);
        });
      } catch (e) {
        this._state = 'closed';
        throw domError(String(e && e.message || e), 'NetworkError');
      }

      this._state = 'open';
    }

    async close() {
      if (this._state !== 'open') {
        throw domError('The port is already closed.', 'InvalidStateError');
      }

      var port = this;
      this._releaseStreams(null);
      this._state = 'closed';

      await nativeCall(function (callId) {
        native.close(callId, port._id);
      });
    }

    async forget() {
      if (this._state === 'open') {
        await this.close();
      }
    }

    getSignals() {
      return Promise.resolve({
        dataCarrierDetect: false,
        clearToSend: false,
        ringIndicator: false,
        dataSetReady: false,
      });
    }

    setSignals() {
      return Promise.resolve();
    }

    _releaseStreams(error) {
      var controller = this._readController;

      this._readController = null;
      this._readable = null;
      this._writable = null;

      if (controller) {
        try {
          if (error) {
            controller.error(error);
          } else {
            controller.close();
          }
        } catch (e) {
          // Stream was already closed or errored
        }
      }
    }

    _onData(bytes) {
      if (this._state !== 'open') {
        return;
      }

      // Touch the getter: makes sure there is a stream to queue into, even
      // if nobody asked for a reader yet.
      if (this.readable && this._readController) {
        try {
          this._readController.enqueue(bytes);
        } catch (e) {
          // Stream is closing, drop the data
        }
      }
    }

    _onLost(message) {
      this._releaseStreams(domError(message || 'The device has been lost.', 'NetworkError'));
      this._state = 'closed';
    }
  }

  function portFor(info) {
    var port = ports[info.id];
    if (!port) {
      port = new AndroidSerialPort(info);
      ports[info.id] = port;
    }

    return port;
  }

  /* ---------------------------------------------------------------------
   * navigator.serial
   * ------------------------------------------------------------------- */

  class AndroidSerial extends EventTarget {
    constructor() {
      super();

      this.onconnect = null;
      this.ondisconnect = null;
    }

    /**
     * All attached USB serial devices the user already granted access to.
     */
    async getPorts() {
      var list = JSON.parse(native.listPorts());

      return list
        .filter(function (info) {
          return info.hasPermission;
        })
        .map(portFor);
    }

    /**
     * Lets the user pick a device (if there is more than one) and asks for
     * the Android USB permission.
     */
    async requestPort() {
      var info;
      try {
        info = await nativeCall(function (callId) {
          native.requestPort(callId);
        });
      } catch (e) {
        throw domError(String(e && e.message || e), 'NotFoundError');
      }

      return portFor(info);
    }

    _emit(type) {
      var event = new Event(type);
      var handler = this['on' + type];

      this.dispatchEvent(event);
      if (typeof handler === 'function') {
        handler.call(this, event);
      }
    }
  }

  var serial = new AndroidSerial();

  Object.defineProperty(navigator, 'serial', {
    configurable: true,
    enumerable: true,
    value: serial,
  });

  /* ---------------------------------------------------------------------
   * Messages from native code
   * ------------------------------------------------------------------- */

  window.__escAndroid = {
    result: function (callId, error, value) {
      var pending = pendingCalls[callId];
      if (!pending) {
        return;
      }

      delete pendingCalls[callId];
      if (error) {
        pending.reject(new Error(error));
      } else {
        pending.resolve(value);
      }
    },

    data: function (id, base64) {
      var port = ports[id];
      if (port) {
        port._onData(fromBase64(base64));
      }
    },

    // A device with granted permission became available
    attached: function (info) {
      portFor(info);
      serial._emit('connect');
    },

    // A device was unplugged
    detached: function (id) {
      var port = ports[id];
      if (!port) {
        return;
      }

      port._onLost('The device has been lost.');
      delete ports[id];
      serial._emit('disconnect');
    },

    // Reading from the device failed, native code already closed it
    ioError: function (id, message) {
      var port = ports[id];
      if (port) {
        port._onLost(message);
      }
    },
  };

  /* ---------------------------------------------------------------------
   * Caching
   *
   * The web app caches release lists and firmware files in CacheStorage.
   * Hand it a cache that never stores anything, every request then reaches
   * the native layer which knows what is bundled, cached or downloadable.
   * ------------------------------------------------------------------- */

  var nullCache = {
    add: function () {
      return Promise.resolve();
    },
    addAll: function () {
      return Promise.resolve();
    },
    delete: function () {
      return Promise.resolve(false);
    },
    keys: function () {
      return Promise.resolve([]);
    },
    match: function () {
      return Promise.resolve(undefined);
    },
    matchAll: function () {
      return Promise.resolve([]);
    },
    put: function () {
      return Promise.resolve();
    },
  };

  var nullCacheStorage = {
    delete: function () {
      return Promise.resolve(false);
    },
    has: function () {
      return Promise.resolve(false);
    },
    keys: function () {
      return Promise.resolve([]);
    },
    match: function () {
      return Promise.resolve(undefined);
    },
    open: function () {
      return Promise.resolve(nullCache);
    },
  };

  try {
    Object.defineProperty(window, 'caches', {
      configurable: true,
      enumerable: true,
      value: nullCacheStorage,
    });
  } catch (e) {
    // Not replaceable, fall through to the check below
  }

  if (window.caches !== nullCacheStorage && window.CacheStorage) {
    window.CacheStorage.prototype.open = nullCacheStorage.open;
  }

  // No service worker: assets are served straight from the APK.
  if (window.ServiceWorkerContainer) {
    window.ServiceWorkerContainer.prototype.register = function () {
      return new Promise(function () {});
    };
  }

  /* ---------------------------------------------------------------------
   * Downloads
   *
   * The web app saves files (log, firmware dump) by clicking a temporary
   * <a download href="blob:..."> element, which a WebView can not handle.
   * ------------------------------------------------------------------- */

  function isLocalData(href) {
    return /^(blob|data):/i.test(href || '');
  }

  function saveLocalData(href, name) {
    fetch(href)
      .then(function (response) {
        return response.arrayBuffer();
      })
      .then(function (buffer) {
        native.saveFile(name || 'download', toBase64(new Uint8Array(buffer)));
      })
      .catch(function (e) {
        console.error('Saving file failed', e);
      });
  }

  var anchorClick = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    if (this.hasAttribute('download') && isLocalData(this.href)) {
      saveLocalData(this.href, this.download);

      return undefined;
    }

    return anchorClick.apply(this, arguments);
  };

  document.addEventListener('click', function (e) {
    var anchor = e.target && e.target.closest ? e.target.closest('a[download]') : null;
    if (anchor && isLocalData(anchor.href)) {
      e.preventDefault();
      saveLocalData(anchor.href, anchor.download);
    }
  }, true);

  // Anything opened in a new window belongs into the system browser
  window.open = function (url) {
    if (url) {
      native.openExternal(String(new URL(url, window.location.href)));
    }

    return null;
  };

  /* ---------------------------------------------------------------------
   * Cookie banner
   *
   * The app has no analytics (no tag manager id is built in and the native
   * layer blocks every host that is not a firmware source), so there is
   * nothing to consent to. Mark the banner as answered.
   * ------------------------------------------------------------------- */

  try {
    if (!/(^|;\s*)rcl_consent_given=/.test(document.cookie)) {
      var tenYears = 10 * 365 * 24 * 60 * 60;
      document.cookie = 'rcl_consent_given=true; path=/; max-age=' + tenYears;
    }
  } catch (e) {
    // Cookies unavailable, the banner can still be dismissed by hand
  }

  /* ---------------------------------------------------------------------
   * Layout
   *
   * Fixes for the web app's phone layout (portrait, up to 600px wide):
   *
   *  - the settings row wraps below the fixed height header bar and covers
   *    the log line: let the bar grow instead
   *  - the latest log line shows through the "Show Log" button
   *  - "Restore Default Settings" and "Open Melody Editor" exist twice in
   *    the button bar, the desktop copies are not hidden
   *  - two line button labels are needlessly tall
   *  - long ESC titles are cut off by the fixed height title bar
   *  - the end of the page hides behind the fixed button bar: reserve as
   *    much room as the bar really takes (it is measured, see below)
   * ------------------------------------------------------------------- */

  var BAR_HEIGHT = '--esc-button-bar-height';

  if (document.head) {
    var style = document.createElement('style');
    style.textContent =
      '@media only screen and (max-width: 600px) {' +
      '.main .main__bar { height: auto !important; display: flow-root; }' +
      '#log .logswitch { right: 0; height: 27px; overflow: hidden; padding: 0 12px 0 10px; background: #242424; }' +
      '.button-bar .buttons-right .generic-button:nth-child(n+4) { display: none; }' +
      '.button-bar .generic-button button { line-height: 1.3 !important; height: auto !important;' +
      ' min-height: 38px; padding-top: 6px; padding-bottom: 6px; }' +
      '.gui-box .gui-box-titlebar { height: auto !important; min-height: 27px; }' +
      '.tab.toolbar_fixed_bottom .content_wrapper { margin-bottom: calc(var(' + BAR_HEIGHT + ', 160px) + 30px); }' +
      '}';
    document.head.appendChild(style);
  }

  // The button bar comes and goes with the tabs and changes height with its
  // content, keep the CSS variable above in sync with it.
  if (window.MutationObserver && window.ResizeObserver && document.documentElement) {
    var observedBar = null;

    var updateBarHeight = function () {
      var height = observedBar && observedBar.isConnected ? observedBar.offsetHeight : 0;
      document.documentElement.style.setProperty(BAR_HEIGHT, height + 'px');
    };

    var barResize = new ResizeObserver(updateBarHeight);

    new MutationObserver(function () {
      var bar = document.querySelector('.button-bar');
      if (bar === observedBar) {
        return;
      }

      if (observedBar) {
        barResize.unobserve(observedBar);
      }

      observedBar = bar;
      if (bar) {
        barResize.observe(bar);
      }

      updateBarHeight();
    }).observe(document.documentElement, {
      childList: true,
      subtree: true,
    });
  }

  /* ---------------------------------------------------------------------
   * AM32 theme
   *
   * While the connected ESCs run AM32 the app looks like the AM32
   * configurator (am32-theme.css, everything in there is scoped to the
   * "am32" class on <html>).
   *
   * The web app does not tell which firmware it found, but it shows it: in
   * the titles of the ESC boxes and of the firmware selection
   * ("ESC 1: <name> - AM32, 2.21, ...").
   * ------------------------------------------------------------------- */

  var AM32_CLASS = 'am32';
  var AM32_BACKGROUND = '#030712';
  var AM32_TITLE = / - AM32, /;
  var FIRMWARE_TITLES = '.flash__individual .esc .gui-box-titlebar, #firmware-selector .gui-box-titlebar';

  function wantsAm32Theme(current) {
    // Not connected (any more): the web app's own look
    if (!document.querySelector('#connect-button-wrapper button.active')) {
      return false;
    }

    var titles = document.querySelectorAll(FIRMWARE_TITLES);

    // Nothing to go by while ESCs are being read or flashed: no flicker
    if (!titles.length) {
      return current;
    }

    for (var i = 0; i < titles.length; i += 1) {
      if (AM32_TITLE.test(titles[i].textContent)) {
        return true;
      }
    }

    return false;
  }

  function updateTheme() {
    var root = document.documentElement;
    var current = root.classList.contains(AM32_CLASS);
    var wanted = wantsAm32Theme(current);

    if (wanted !== current) {
      root.classList.toggle(AM32_CLASS, wanted);

      // Area behind the system bars, an empty string restores the default
      native.setChromeColor(wanted ? AM32_BACKGROUND : '');
    }
  }

  if (window.MutationObserver && document.documentElement) {
    // After the web app's own styles, so that equally specific rules win
    document.addEventListener('DOMContentLoaded', function () {
      var theme = document.createElement('link');
      theme.rel = 'stylesheet';
      theme.href = '/am32-theme.css';
      document.head.appendChild(theme);
    });

    new MutationObserver(updateTheme).observe(document.documentElement, {
      attributeFilter: ['class'],
      attributes: true,
      childList: true,
      subtree: true,
    });
  }

  window.isEscConfiguratorAndroid = true;
})();
