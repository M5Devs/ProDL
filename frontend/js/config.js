/*
 * ProDL frontend configuration.
 *
 * The SPA never hardcodes a backend address. Resolution order:
 *   1. ?api=https://host:port        (explicit override, handy for testing)
 *   2. localStorage["prodl.apiBase"] (saved from the Settings dialog)
 *   3. window.__PRODL_API_BASE__     (injected by a desktop/Android shell)
 *   4. window.PRODL_CONFIG.apiBaseUrl (edited at build/deploy time)
 *   5. "" when the page is served over http(s) -> same origin
 *   6. http://127.0.0.1:8000 when the page is loaded from file:// (WebView)
 *
 * Plain <script> files (no ES modules) so the page also works from file://.
 */
(function () {
  'use strict';

  var STORAGE_KEY = 'prodl.apiBase';
  var DEFAULT_LOCAL_API = 'http://127.0.0.1:8000';
  var config = window.PRODL_CONFIG || {};

  function normalize(value) {
    if (value === null || value === undefined) return '';
    var trimmed = String(value).trim();
    if (!trimmed) return '';
    trimmed = trimmed.replace(/\/+$/, '');
    if (!/^[a-z][a-z0-9+.-]*:\/\//i.test(trimmed)) {
      trimmed = 'http://' + trimmed;
    }
    return trimmed;
  }

  function readStored() {
    try {
      return window.localStorage.getItem(STORAGE_KEY);
    } catch (err) {
      return null;
    }
  }

  function writeStored(value) {
    try {
      if (value) window.localStorage.setItem(STORAGE_KEY, value);
      else window.localStorage.removeItem(STORAGE_KEY);
    } catch (err) {
      /* private mode / WebView without storage: ignore */
    }
  }

  function queryOverride() {
    try {
      return new URLSearchParams(window.location.search).get('api');
    } catch (err) {
      return null;
    }
  }

  function isFileOrigin() {
    var protocol = window.location.protocol;
    return protocol === 'file:' || protocol === 'about:' || !window.location.origin;
  }

  function resolve() {
    var candidates = [
      queryOverride(),
      readStored(),
      window.__PRODL_API_BASE__,
      window.ProDLShell && window.ProDLShell.apiBaseUrl,
      config.apiBaseUrl
    ];
    for (var i = 0; i < candidates.length; i += 1) {
      var normalized = normalize(candidates[i]);
      if (normalized) return normalized;
    }
    return isFileOrigin() ? DEFAULT_LOCAL_API : '';
  }

  window.ProDLConfig = {
    STORAGE_KEY: STORAGE_KEY,
    DEFAULT_LOCAL_API: DEFAULT_LOCAL_API,
    apiBaseUrl: resolve(),
    normalize: normalize,
    isFileOrigin: isFileOrigin,
    setApiBase: function (value) {
      var normalized = normalize(value);
      writeStored(normalized);
      window.ProDLConfig.apiBaseUrl = normalized || (isFileOrigin() ? DEFAULT_LOCAL_API : '');
      return window.ProDLConfig.apiBaseUrl;
    },
    resetApiBase: function () {
      writeStored('');
      window.ProDLConfig.apiBaseUrl = resolve();
      return window.ProDLConfig.apiBaseUrl;
    },
    absolute: function (path) {
      if (!path) return '';
      if (/^[a-z][a-z0-9+.-]*:\/\//i.test(path)) return path;
      var base = window.ProDLConfig.apiBaseUrl;
      return base ? base + (path.charAt(0) === '/' ? path : '/' + path) : path;
    }
  };
})();
