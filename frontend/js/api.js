/*
 * Thin API client + progress streaming with graceful degradation:
 * WebSocket -> Server-Sent Events -> HTTP polling.
 */
(function () {
  'use strict';

  var TERMINAL = { finished: true, error: true, canceled: true };

  function ApiError(message, status, payload) {
    this.name = 'ApiError';
    this.message = message;
    this.status = status;
    this.payload = payload;
  }
  ApiError.prototype = Object.create(Error.prototype);
  ApiError.prototype.constructor = ApiError;

  function base() {
    return window.ProDLConfig.apiBaseUrl;
  }

  function buildUrl(path, params) {
    var target = window.ProDLConfig.absolute(path);
    if (!params) return target;
    var query = [];
    Object.keys(params).forEach(function (key) {
      var value = params[key];
      if (value === undefined || value === null || value === '') return;
      query.push(encodeURIComponent(key) + '=' + encodeURIComponent(value));
    });
    if (!query.length) return target;
    return target + (target.indexOf('?') === -1 ? '?' : '&') + query.join('&');
  }

  function errorMessage(payload, fallback) {
    if (!payload) return fallback;
    if (typeof payload.detail === 'string') return payload.detail;
    if (Array.isArray(payload.detail) && payload.detail.length) {
      return payload.detail
        .map(function (item) {
          return item.msg || JSON.stringify(item);
        })
        .join('; ');
    }
    if (payload.message) return payload.message;
    return fallback;
  }

  function request(path, options) {
    var opts = options || {};
    var init = { method: opts.method || 'GET', headers: {} };
    if (opts.body !== undefined) {
      init.headers['Content-Type'] = 'application/json';
      init.body = JSON.stringify(opts.body);
    }
    if (opts.formData) {
      init.body = opts.formData;
    }
    if (opts.signal) init.signal = opts.signal;

    return fetch(buildUrl(path, opts.params), init).then(function (response) {
      var isJson = (response.headers.get('content-type') || '').indexOf('json') !== -1;
      return (isJson ? response.json().catch(function () { return null; }) : Promise.resolve(null)).then(
        function (payload) {
          if (!response.ok) {
            throw new ApiError(
              errorMessage(payload, 'Request failed (' + response.status + ')'),
              response.status,
              payload
            );
          }
          return payload;
        }
      );
    });
  }

  function wsUrl(path) {
    var configured = base();
    if (!configured) {
      var protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
      return protocol + '//' + window.location.host + path;
    }
    return configured.replace(/^http/i, 'ws') + path;
  }

  /**
   * Follow a task's progress until it reaches a terminal state.
   * Returns a handle with close().
   */
  function watchTask(task, handlers) {
    var onEvent = handlers.onEvent || function () {};
    var onDone = handlers.onDone || function () {};
    var onError = handlers.onError || function () {};
    var closed = false;
    var socket = null;
    var source = null;
    var timer = null;
    var finished = false;

    function cleanup() {
      if (socket) {
        try { socket.close(); } catch (err) { /* noop */ }
        socket = null;
      }
      if (source) {
        try { source.close(); } catch (err) { /* noop */ }
        source = null;
      }
      if (timer) {
        clearInterval(timer);
        timer = null;
      }
    }

    function close() {
      closed = true;
      cleanup();
    }

    function deliver(event) {
      if (closed || finished || !event) return;
      if (event.type === 'ping') return;
      if (event.type === 'error' && !event.status) {
        fail(new Error(event.error || 'Unknown task'));
        return;
      }
      onEvent(event);
      if (TERMINAL[event.status]) {
        finished = true;
        close();
        onDone(event);
      }
    }

    function fail(err) {
      if (closed || finished) return;
      finished = true;
      close();
      onError(err);
    }

    function poll() {
      if (closed) return;
      request(task.task_url)
        .then(function (snapshot) {
          if (snapshot && snapshot.last_event) deliver(snapshot.last_event);
          if (snapshot && TERMINAL[snapshot.status]) {
            deliver({ status: snapshot.status, file: snapshot.file, error: snapshot.error });
          }
        })
        .catch(function () { /* keep polling */ });
    }

    function usePolling() {
      if (closed) return;
      poll();
      timer = setInterval(poll, 1200);
    }

    function useSse() {
      if (closed) return;
      if (typeof window.EventSource !== 'function') {
        usePolling();
        return;
      }
      try {
        source = new window.EventSource(window.ProDLConfig.absolute(task.sse_url));
      } catch (err) {
        usePolling();
        return;
      }
      source.addEventListener('progress', function (message) {
        try {
          deliver(JSON.parse(message.data));
        } catch (err) { /* ignore malformed frame */ }
      });
      source.onerror = function () {
        if (closed || finished) return;
        cleanup();
        usePolling();
      };
    }

    function useWebSocket() {
      if (closed) return;
      if (typeof window.WebSocket !== 'function') {
        useSse();
        return;
      }
      try {
        socket = new window.WebSocket(wsUrl(task.websocket_url));
      } catch (err) {
        useSse();
        return;
      }
      var opened = false;
      socket.onopen = function () { opened = true; };
      socket.onmessage = function (message) {
        try {
          deliver(JSON.parse(message.data));
        } catch (err) { /* ignore malformed frame */ }
      };
      socket.onerror = function () {
        if (opened || closed || finished) return;
        cleanup();
        useSse();
      };
      socket.onclose = function () {
        if (opened || closed || finished) return;
        cleanup();
        useSse();
      };
    }

    useWebSocket();
    return { close: close };
  }

  function createApi() {
    return {
      config: window.ProDLConfig,
      health: function () { return request('/api/health'); },
      /** `type`: all | videos | channels | playlists (documented in API_SPEC). */
      search: function (query, limit, type, signal) {
        return request('/api/search', {
          params: {
            q: query,
            limit: limit,
            type: type && type !== 'all' ? type : undefined
          },
          signal: signal
        });
      },
      info: function (url, options) {
        var opts = options || {};
        return request('/api/info', {
          params: {
            url: url,
            sponsorblock: opts.sponsorblock ? 'true' : undefined,
            categories: opts.categories
          }
        });
      },
      download: function (payload) {
        return request('/api/download', { method: 'POST', body: payload });
      },
      convert: function (payload) {
        return request('/api/convert', { method: 'POST', body: payload });
      },
      convertUpload: function (formData) {
        return request('/api/convert/upload', { method: 'POST', formData: formData });
      },
      task: function (taskId) { return request('/api/tasks/' + encodeURIComponent(taskId)); },
      cancel: function (taskId) {
        return request('/api/tasks/' + encodeURIComponent(taskId) + '/cancel', { method: 'POST' });
      },
      cookies: function () { return request('/api/cookies'); },
      saveCookies: function (content) {
        return request('/api/cookies', { method: 'POST', body: { content: content } });
      },
      clearCookies: function () { return request('/api/cookies', { method: 'DELETE' }); },
      fileUrl: function (task) {
        return task && task.file_url ? window.ProDLConfig.absolute(task.file_url) : '';
      },
      watchTask: watchTask
    };
  }

  window.ProDL = window.ProDL || {};
  window.ProDL.ApiError = ApiError;
  window.ProDL.createApi = createApi;
})();
