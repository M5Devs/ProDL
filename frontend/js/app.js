/* ProDL SPA logic — no framework, no build step, no external requests. */
(function () {
  'use strict';

  var api = window.ProDL.createApi();
  var config = window.ProDLConfig;

  var state = {
    info: null,
    selectedFormat: null,
    audioOnly: false,
    convertFile: null,
    lastFinishedTask: null,
    channels: [],
    searchAbort: null
  };

  function $(id) {
    return document.getElementById(id);
  }

  // ── small helpers ────────────────────────────────────────────────────────
  function toast(message, kind) {
    var host = $('toasts');
    var node = document.createElement('div');
    node.className = 'toast' + (kind ? ' ' + kind : '');
    node.textContent = message;
    host.appendChild(node);
    setTimeout(function () {
      node.style.opacity = '0';
      setTimeout(function () { node.remove(); }, 250);
    }, kind === 'error' ? 6000 : 3500);
  }

  function showError(message) {
    var box = $('errorBox');
    box.textContent = message;
    box.hidden = false;
  }

  function clearError() {
    $('errorBox').hidden = true;
    $('errorBox').textContent = '';
  }

  function setLoader(active, text) {
    $('loader').hidden = !active;
    $('loaderText').textContent = text || 'Fetching info…';
    $('fetchBtn').disabled = active;
  }

  function fmtDuration(seconds) {
    if (!seconds && seconds !== 0) return '';
    var total = Math.round(seconds);
    var h = Math.floor(total / 3600);
    var m = Math.floor((total % 3600) / 60);
    var s = total % 60;
    if (h) return h + ':' + String(m).padStart(2, '0') + ':' + String(s).padStart(2, '0');
    return m + ':' + String(s).padStart(2, '0');
  }

  function fmtBytes(bytes) {
    if (!bytes && bytes !== 0) return '';
    var units = ['B', 'KB', 'MB', 'GB'];
    var value = Number(bytes);
    var index = 0;
    while (value >= 1024 && index < units.length - 1) {
      value /= 1024;
      index += 1;
    }
    return (value >= 10 || index === 0 ? value.toFixed(0) : value.toFixed(1)) + ' ' + units[index];
  }

  function fmtEta(seconds) {
    if (seconds === null || seconds === undefined) return '';
    var total = Math.max(0, Math.round(seconds));
    var m = Math.floor(total / 60);
    var s = total % 60;
    return String(m).padStart(2, '0') + ':' + String(s).padStart(2, '0');
  }

  function heightOf(format) {
    if (format.height) return format.height;
    var match = /(\d{2,4})x(\d{2,4})/.exec(format.resolution || '');
    return match ? Number(match[2]) : 0;
  }

  // ── health ───────────────────────────────────────────────────────────────
  function refreshHealth() {
    var pill = $('healthPill');
    return api.health().then(function (health) {
      var parts = ['yt-dlp ' + (health.yt_dlp.version || '?').split(' ')[0]];
      parts.push(health.ffmpeg.available ? 'ffmpeg' : 'no ffmpeg');
      pill.className = 'pill ' + (health.status === 'ok' ? 'ok' : 'warn');
      pill.title = 'ProDL ' + health.version + ' · ' + health.platform;
      $('healthText').textContent = parts.join(' · ');
      $('healthJson').textContent = JSON.stringify(health, null, 2);
      return health;
    }).catch(function (err) {
      pill.className = 'pill bad';
      $('healthText').textContent = 'offline';
      pill.title = err.message;
      $('healthJson').textContent = err.message;
      return null;
    });
  }

  // ── tabs ─────────────────────────────────────────────────────────────────
  function initTabs() {
    var tabs = Array.prototype.slice.call(document.querySelectorAll('.tab'));
    tabs.forEach(function (tab) {
      tab.addEventListener('click', function () {
        tabs.forEach(function (other) {
          var active = other === tab;
          other.classList.toggle('active', active);
          other.setAttribute('aria-selected', String(active));
          $(other.getAttribute('aria-controls')).classList.toggle('active', active);
        });
      });
    });
  }

  // ── search ───────────────────────────────────────────────────────────────
  function runSearch(event) {
    if (event) event.preventDefault();
    var query = $('searchInput').value.trim();
    if (!query) return;

    if (state.searchAbort) state.searchAbort.abort();
    state.searchAbort = typeof AbortController === 'function' ? new AbortController() : null;

    $('searchBtn').disabled = true;
    setLoader(true, 'Searching…');
    clearError();
    var container = $('searchResults');
    container.hidden = true;
    container.textContent = '';

    api.search(query, 12, state.searchAbort ? state.searchAbort.signal : undefined)
      .then(function (payload) {
        if (!payload.results.length) {
          showError('No results for “' + query + '”.');
          return;
        }
        renderSearchResults(payload.results);
      })
      .catch(function (err) {
        if (err.name === 'AbortError') return;
        showError(err.message);
      })
      .then(function () {
        $('searchBtn').disabled = false;
        setLoader(false);
      });
  }

  function renderSearchResults(results) {
    var container = $('searchResults');
    container.textContent = '';
    results.forEach(function (item) {
      var button = document.createElement('button');
      button.type = 'button';
      button.className = 'result-item';

      if (item.thumbnail) {
        var img = document.createElement('img');
        img.src = item.thumbnail;
        img.alt = '';
        img.loading = 'lazy';
        img.referrerPolicy = 'no-referrer';
        button.appendChild(img);
      }

      var title = document.createElement('strong');
      title.textContent = item.title;
      button.appendChild(title);

      var meta = document.createElement('small');
      var bits = [];
      if (item.duration) bits.push(fmtDuration(item.duration));
      if (item.uploader) bits.push(item.uploader);
      if (item.is_live) bits.push('live');
      meta.textContent = bits.join(' · ') || item.url;
      button.appendChild(meta);

      button.addEventListener('click', function () {
        $('urlInput').value = item.url;
        fetchInfo(item.url);
      });
      container.appendChild(button);
    });
    container.hidden = false;
  }

  // ── info ─────────────────────────────────────────────────────────────────
  function fetchInfo(url) {
    var target = (url || $('urlInput').value || '').trim();
    if (!target) return;

    clearError();
    setLoader(true, 'Fetching info…');
    $('resultCard').hidden = true;
    state.info = null;
    state.selectedFormat = null;

    api.info(target, { sponsorblock: true })
      .then(function (info) {
        state.info = info;
        renderInfo(info);
      })
      .catch(function (err) {
        showError(err.message);
      })
      .then(function () {
        setLoader(false);
      });
  }

  function renderInfo(info) {
    var thumb = $('thumb');
    if (info.thumbnail) {
      thumb.src = info.thumbnail;
      thumb.hidden = false;
    } else {
      thumb.removeAttribute('src');
      thumb.hidden = true;
    }

    $('videoTitle').textContent = info.title || 'Untitled';
    $('urlInput').value = info.webpage_url || $('urlInput').value;

    var badges = $('videoBadges');
    badges.textContent = '';
    [
      info.extractor,
      info.duration ? fmtDuration(info.duration) : null,
      info.uploader || info.channel,
      info.view_count ? info.view_count.toLocaleString() + ' views' : null,
      info.is_live ? 'live' : null
    ].forEach(function (label, index) {
      if (!label) return;
      var badge = document.createElement('span');
      badge.className = 'badge' + (index === 0 ? ' accent' : '');
      badge.textContent = label;
      badges.appendChild(badge);
    });

    var note = $('sponsorNote');
    var sponsor = info.sponsorblock;
    if (sponsor && sponsor.segments && sponsor.segments.length) {
      var seconds = sponsor.segments.reduce(function (sum, segment) {
        return sum + Math.max(0, segment.end - segment.start);
      }, 0);
      note.textContent =
        sponsor.segments.length + ' SponsorBlock segments found (~' +
        Math.round(seconds / 60) + ' min will be cut).';
      note.hidden = false;
    } else {
      note.hidden = true;
    }

    renderFormats(info.formats || []);
    updateAudioHint();
    $('resultCard').hidden = false;
    $('downloadBtn').disabled = false;
  }

  function renderFormats(formats) {
    var grid = $('formatGrid');
    grid.textContent = '';

    var seen = {};
    var usable = formats
      .filter(function (format) {
        return format.has_video && format.resolution !== 'audio only';
      })
      .filter(function (format) {
        var key = format.resolution + '|' + (format.fps || '');
        if (seen[key]) return false;
        seen[key] = true;
        return true;
      })
      .sort(function (a, b) { return heightOf(a) - heightOf(b); });

    usable.forEach(function (format) {
      var button = document.createElement('button');
      button.type = 'button';
      button.className = 'fmt-btn';
      button.dataset.formatId = format.format_id;

      var res = document.createElement('span');
      res.className = 'res';
      res.textContent = format.note || format.resolution;
      button.appendChild(res);

      var sub = document.createElement('span');
      sub.className = 'sub';
      sub.textContent = [format.ext, format.fps ? format.fps + ' fps' : null, format.filesize ? fmtBytes(format.filesize) : null]
        .filter(Boolean)
        .join(' · ');
      button.appendChild(sub);

      button.addEventListener('click', function () { selectFormat(format, button); });
      grid.appendChild(button);
    });

    var best = grid.lastElementChild;
    if (best) best.click();
    else selectAudio();
  }

  function selectFormat(format, button) {
    state.audioOnly = false;
    state.selectedFormat = format.format_id;
    $('audioBtn').classList.remove('selected');
    Array.prototype.forEach.call(document.querySelectorAll('.fmt-btn'), function (node) {
      node.classList.toggle('selected', node === button);
    });
    $('downloadBtn').disabled = false;
  }

  function selectAudio() {
    state.audioOnly = true;
    state.selectedFormat = null;
    Array.prototype.forEach.call(document.querySelectorAll('.fmt-btn'), function (node) {
      node.classList.remove('selected');
    });
    $('audioBtn').classList.add('selected');
    $('downloadBtn').disabled = false;
  }

  function updateAudioHint() {
    var labels = {
      mp3: 'MP3', m4a: 'M4A (AAC)', opus: 'Opus', flac: 'FLAC', wav: 'WAV'
    };
    $('audioHint').textContent = (labels[$('audioFormat').value] || 'MP3') + ' · audio only';
  }

  // ── progress rendering ───────────────────────────────────────────────────
  var DOWNLOAD_PROGRESS = {
    panel: 'progressPanel',
    title: 'progressTitle',
    percent: 'progressPercent',
    fill: 'progressFill',
    meta: 'progressMeta',
    actions: 'progressActions',
    saveLink: 'saveLink',
    closeBtn: 'progressClose'
  };

  var CONVERT_PROGRESS = {
    panel: 'convertProgress',
    title: 'convertProgressTitle',
    percent: 'convertProgressPercent',
    fill: 'convertProgressFill',
    meta: 'convertProgressMeta',
    actions: 'convertProgressActions',
    saveLink: 'convertSaveLink',
    closeBtn: 'convertProgressClose'
  };

  function progressRefs(ids) {
    var refs = {};
    Object.keys(ids).forEach(function (key) {
      refs[key] = $(ids[key]);
    });
    return refs;
  }

  function renderProgress(refs, event) {
    if (typeof event.percent === 'number') {
      refs.fill.classList.remove('indeterminate');
      refs.fill.style.width = Math.max(0, Math.min(100, event.percent)) + '%';
      refs.percent.textContent = (event.percent < 10 ? event.percent.toFixed(1) : Math.round(event.percent)) + '%';
    }

    var bits = [];
    if (event.status === 'downloading') bits.push('downloading');
    if (event.status === 'postprocessing') bits.push('processing');
    if (event.status === 'converting') bits.push('converting');
    if (event.speed_str) bits.push(event.speed_str);
    else if (event.speed) bits.push(fmtBytes(event.speed) + '/s');
    if (typeof event.eta === 'number') bits.push('ETA ' + fmtEta(event.eta));
    if (event.downloaded_bytes != null && event.total_bytes) {
      bits.push(fmtBytes(event.downloaded_bytes) + ' / ' + fmtBytes(event.total_bytes));
    }
    if (event.filename) bits.push(event.filename);
    refs.meta.textContent = bits.join(' · ') || event.message || '';
    if (event.message) refs.title.textContent = event.message;
  }

  function mountProgress(refs, task, label, kind) {
    refs.panel.hidden = false;
    refs.actions.hidden = true;
    refs.title.textContent = label;
    refs.percent.textContent = '0%';
    refs.fill.style.width = '0%';
    refs.fill.classList.add('indeterminate');
    refs.meta.textContent = 'Queued…';

    var channel = api.watchTask(task, {
      onEvent: function (event) { renderProgress(refs, event); },
      onDone: function (event) {
        if (event.status === 'finished') {
          refs.fill.classList.remove('indeterminate');
          refs.fill.style.width = '100%';
          refs.percent.textContent = '100%';
          refs.title.textContent = kind === 'convert' ? 'Conversion complete' : 'Download complete';
          if (event.file) {
            refs.saveLink.href = config.absolute(event.file.download_url || task.file_url);
            refs.saveLink.setAttribute('download', event.file.filename || '');
            refs.saveLink.textContent = 'Save ' + (event.file.filename || 'file');
            refs.actions.hidden = false;
          }
          toast(kind === 'convert' ? 'Conversion finished' : 'Download finished', 'success');
          if (kind === 'download') {
            state.lastFinishedTask = { task_id: task.task_id, filename: event.file && event.file.filename };
            updateConvertHint();
          }
        } else if (event.status === 'canceled') {
          refs.title.textContent = 'Canceled';
          refs.fill.classList.remove('indeterminate');
        } else {
          refs.title.textContent = 'Failed';
          refs.fill.classList.remove('indeterminate');
          refs.meta.textContent = event.error || 'Unknown error';
          toast(event.error || 'Task failed', 'error');
        }
      },
      onError: function (err) {
        refs.meta.textContent = err.message;
        toast(err.message, 'error');
      }
    });
    state.channels.push(channel);
  }

  // ── download ─────────────────────────────────────────────────────────────
  function startDownload() {
    if (!state.info) return;
    $('downloadBtn').disabled = true;

    var payload = {
      url: state.info.webpage_url || $('urlInput').value.trim(),
      format_id: state.audioOnly ? null : state.selectedFormat,
      audio_only: state.audioOnly,
      audio_format: $('audioFormat').value,
      subtitles: $('optSubtitles').checked,
      embed_subtitles: $('optEmbedSubs').checked,
      sponsorblock: $('optSponsorBlock').checked,
      sponsorblock_action: 'cut',
      thumbnail: $('optThumbnail').checked,
      use_cookies: $('optCookies').checked
    };

    api.download(payload)
      .then(function (task) {
        mountProgress(progressRefs(DOWNLOAD_PROGRESS), task, 'Starting download…', 'download');
        $('downloadBtn').disabled = false;
      })
      .catch(function (err) {
        $('downloadBtn').disabled = false;
        toast(err.message, 'error');
        showError(err.message);
      });
  }

  // ── convert ──────────────────────────────────────────────────────────────
  function updateConvertHint() {
    var title = $('dropzoneTitle');
    if (state.convertFile) {
      title.textContent = state.convertFile.name;
    } else if (state.lastFinishedTask) {
      title.textContent = 'Convert last download: ' + (state.lastFinishedTask.filename || 'file');
    } else {
      title.textContent = 'Choose a media file';
    }
    $('convertBtn').disabled = !(state.convertFile || state.lastFinishedTask);
  }

  function onFileChosen(file) {
    state.convertFile = file || null;
    updateConvertHint();
  }

  function startConvert() {
    var target = $('convertFormat').value;
    var bitrate = $('convertBitrate').value;
    var start = $('trimStart').value;
    var end = $('trimEnd').value;
    var request;

    if (state.convertFile) {
      var form = new FormData();
      form.append('file', state.convertFile);
      form.append('target_format', target);
      form.append('audio_bitrate', bitrate);
      if (start !== '') form.append('start', start);
      if (end !== '') form.append('end', end);
      request = api.convertUpload(form);
    } else if (state.lastFinishedTask) {
      request = api.convert({
        task_id: state.lastFinishedTask.task_id,
        target_format: target,
        audio_bitrate: bitrate,
        start: start === '' ? null : Number(start),
        end: end === '' ? null : Number(end)
      });
    } else {
      toast('Choose a file or download something first', 'error');
      return;
    }

    $('convertBtn').disabled = true;
    request
      .then(function (task) {
        mountProgress(progressRefs(CONVERT_PROGRESS), task, 'Starting conversion…', 'convert');
      })
      .catch(function (err) { toast(err.message, 'error'); })
      .then(function () { $('convertBtn').disabled = false; });
  }

  // ── settings ─────────────────────────────────────────────────────────────
  function updateApiLabel() {
    $('apiLabel').textContent = 'API: ' + (config.apiBaseUrl || 'same origin');
  }

  function openSettings() {
    $('apiBaseInput').value = config.apiBaseUrl;
    $('settingsDialog').showModal();
    refreshHealth();
    refreshCookieStatus();
  }

  function refreshCookieStatus() {
    api.cookies()
      .then(function (status) {
        $('cookiesStatus').textContent = status.configured
          ? status.cookie_count + ' cookies stored · ' + (status.updated_at || '')
          : 'no cookies stored';
      })
      .catch(function (err) {
        $('cookiesStatus').textContent = err.message;
      });
  }

  function saveCookies() {
    var content = $('cookiesInput').value;
    if (!content.trim()) {
      toast('Paste a Netscape cookie file first', 'error');
      return;
    }
    api.saveCookies(content)
      .then(function (status) {
        $('cookiesInput').value = '';
        $('cookiesStatus').textContent = status.cookie_count + ' cookies stored · ' + (status.updated_at || '');
        toast('Cookies saved on this device', 'success');
      })
      .catch(function (err) { toast(err.message, 'error'); });
  }

  function clearCookies() {
    api.clearCookies()
      .then(function () {
        $('cookiesStatus').textContent = 'no cookies stored';
        toast('Cookies deleted', 'success');
      })
      .catch(function (err) { toast(err.message, 'error'); });
  }

  // ── wiring ───────────────────────────────────────────────────────────────
  function init() {
    initTabs();
    updateApiLabel();
    refreshHealth();
    setInterval(refreshHealth, 60000);

    $('searchForm').addEventListener('submit', runSearch);
    $('urlForm').addEventListener('submit', function (event) {
      event.preventDefault();
      fetchInfo();
    });
    $('audioBtn').addEventListener('click', selectAudio);
    $('audioFormat').addEventListener('change', function () {
      updateAudioHint();
      if (state.info) selectAudio();
    });
    $('downloadBtn').addEventListener('click', startDownload);

    $('convertFile').addEventListener('change', function (event) {
      onFileChosen(event.target.files && event.target.files[0]);
    });
    var dropzone = $('dropzone');
    ['dragenter', 'dragover'].forEach(function (name) {
      dropzone.addEventListener(name, function (event) {
        event.preventDefault();
        dropzone.classList.add('dragging');
      });
    });
    ['dragleave', 'drop'].forEach(function (name) {
      dropzone.addEventListener(name, function (event) {
        event.preventDefault();
        dropzone.classList.remove('dragging');
      });
    });
    dropzone.addEventListener('drop', function (event) {
      var file = event.dataTransfer && event.dataTransfer.files && event.dataTransfer.files[0];
      if (file) onFileChosen(file);
    });
    $('convertBtn').addEventListener('click', startConvert);

    $('progressClose').addEventListener('click', function () {
      $('progressPanel').hidden = true;
    });
    $('convertProgressClose').addEventListener('click', function () {
      $('convertProgress').hidden = true;
    });

    $('settingsBtn').addEventListener('click', openSettings);
    $('healthPill').addEventListener('click', openSettings);
    $('refreshHealthBtn').addEventListener('click', refreshHealth);
    $('saveApiBtn').addEventListener('click', function () {
      var value = config.setApiBase($('apiBaseInput').value);
      $('apiBaseInput').value = value;
      updateApiLabel();
      toast('Backend URL saved', 'success');
      refreshHealth();
    });
    $('resetApiBtn').addEventListener('click', function () {
      var value = config.resetApiBase();
      $('apiBaseInput').value = value;
      updateApiLabel();
      toast('Backend URL reset', 'success');
      refreshHealth();
    });
    $('saveCookiesBtn').addEventListener('click', saveCookies);
    $('clearCookiesBtn').addEventListener('click', clearCookies);

    updateConvertHint();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
