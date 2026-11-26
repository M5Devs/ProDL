/* ProDL SPA — no framework, no build step, no external requests. */
(function () {
  'use strict';

  var api = window.ProDL.createApi();
  var config = window.ProDLConfig;

  var state = {
    health: null,
    info: null,
    selectedFormat: null,
    audioOnly: false,
    convertFile: null,
    convertSupported: true,
    tasks: [],
    searchAbort: null,
    view: 'explore'
  };

  function $(id) {
    return document.getElementById(id);
  }

  function native() {
    return window.ProDLNative || null;
  }

  // ── helpers ──────────────────────────────────────────────────────────────
  function toast(message, kind) {
    var node = document.createElement('div');
    node.className = 'toast' + (kind ? ' ' + kind : '');
    node.textContent = message;
    $('toasts').appendChild(node);
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
    $('loaderText').textContent = text || 'Working…';
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
    return String(Math.floor(total / 60)).padStart(2, '0') + ':' + String(total % 60).padStart(2, '0');
  }

  function heightOf(format) {
    if (format.height) return format.height;
    var match = /(\d{2,4})x(\d{2,4})/.exec(format.resolution || '');
    return match ? Number(match[2]) : 0;
  }

  // "ffmpeg version 7.0.2-static Copyright …" -> "7.0.2"
  function shortVersion(value) {
    if (!value) return '';
    var match = /(\d+\.\d+(?:\.\d+)?)/.exec(String(value));
    return match ? match[1] : String(value).slice(0, 28);
  }

  function isUrl(value) {
    if (!value) return false;
    if (/^[a-z][a-z0-9+.-]*:\/\//i.test(value)) return true;
    return /^(www\.)?[\w-]+(\.[\w-]+)+\//.test(value);
  }

  // ── views ────────────────────────────────────────────────────────────────
  var NAV = { explore: 'navExplore', downloads: 'navDownloads', convert: 'navConvert', settings: 'navSettings' };

  function switchView(name) {
    state.view = name;
    Object.keys(NAV).forEach(function (key) {
      var button = $(NAV[key]);
      var active = key === name;
      button.classList.toggle('active', active);
      if (active) button.setAttribute('aria-current', 'page');
      else button.removeAttribute('aria-current');
      $('view-' + key).classList.toggle('active', active);
    });
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  // ── health ───────────────────────────────────────────────────────────────
  function setEngineState(node, kind) {
    node.classList.remove('ok', 'warn', 'bad');
    if (kind) node.classList.add(kind);
  }

  function refreshHealth() {
    var pill = $('healthPill');
    return api.health().then(function (health) {
      state.health = health;
      var yt = health.yt_dlp || {};
      var ff = health.ffmpeg || {};
      var cookies = health.cookies || {};
      var features = health.features || {};
      var sponsorOk = features.sponsorblock !== false;
      var ok = health.status === 'ok' && yt.available && ff.available;

      pill.className = 'pill ' + (ok ? 'ok' : (yt.available ? 'warn' : 'bad'));
      $('healthText').textContent = ok ? 'Ready' : yt.available ? 'No FFmpeg' : 'yt-dlp failed';
      $('healthExtra').textContent = ' · yt-dlp ' + (yt.available ? '✓' : '✕') +
        ' · FFmpeg ' + (ff.available ? '✓' : '✕');
      pill.title = [
        'ProDL ' + health.version + ' · ' + health.platform,
        'yt-dlp: ' + (yt.available ? (yt.version || 'ready') : 'unavailable'),
        'FFmpeg: ' + (ff.available ? (ff.version || 'ready') : 'unavailable'),
        'SponsorBlock: ' + (sponsorOk ? 'available' : 'unavailable'),
        'Cookies: ' + (cookies.configured ? cookies.cookie_count + ' stored' : 'none')
      ].join('\n');

      $('engineStatus').textContent = ok ? 'all engines ready' : 'degraded';

      $('ytdlpState').textContent = yt.available ? 'ready' : 'failed';
      setEngineState($('engineYtdlp'), yt.available ? 'ok' : 'bad');
      $('ytdlpValue').textContent = yt.available ? (shortVersion(yt.version) || 'bundled') : 'unavailable';
      $('ytdlpValue').title = yt.version || '';
      $('ytdlpNote').textContent = yt.note || (yt.path ? 'path: ' + yt.path : 'no details reported');

      $('ffmpegState').textContent = ff.available ? 'ready' : 'missing';
      setEngineState($('engineFfmpeg'), ff.available ? 'ok' : 'bad');
      $('ffmpegValue').textContent = ff.available ? (shortVersion(ff.version) || 'bundled') : 'unavailable';
      $('ffmpegValue').title = ff.version || '';
      $('ffmpegNote').textContent = ff.note || (ff.path ? 'path: ' + ff.path : 'conversion disabled without FFmpeg');

      $('sponsorState').textContent = sponsorOk ? 'ready' : 'off';
      setEngineState($('engineSponsor'), sponsorOk ? 'ok' : 'warn');
      $('sponsorValue').textContent = sponsorOk ? 'segment skipping' : 'disabled';
      $('sponsorNote').textContent = sponsorOk
        ? 'segments fetched per video from sponsor.ajay.app'
        : 'this server cannot reach sponsor.ajay.app';

      var cookieOk = cookies.configured === true;
      $('cookiesState').textContent = cookieOk ? 'linked' : 'none';
      setEngineState($('engineCookies'), cookieOk ? 'ok' : 'warn');
      $('cookiesValue').textContent = cookieOk ? cookies.cookie_count + ' cookies' : 'not signed in';
      $('cookiesNote').textContent = cookieOk
        ? 'updated ' + (cookies.updated_at || 'earlier')
        : 'age-restricted videos need a session';

      applyConvertCapability(features.convert !== false);
      $('healthJson').textContent = JSON.stringify(health, null, 2);
      return health;
    }).catch(function (err) {
      state.health = null;
      pill.className = 'pill bad';
      $('healthText').textContent = 'offline';
      $('healthExtra').textContent = '';
      pill.title = err.message;
      $('engineStatus').textContent = 'engine unreachable';
      ['engineYtdlp', 'engineFfmpeg', 'engineSponsor', 'engineCookies'].forEach(function (id) {
        setEngineState($(id), 'bad');
      });
      $('ytdlpValue').textContent = '—';
      $('ffmpegValue').textContent = '—';
      $('sponsorValue').textContent = '—';
      $('cookiesValue').textContent = '—';
      $('ytdlpNote').textContent = err.message;
      $('healthJson').textContent = err.message;
      return null;
    });
  }

  // ── explore: unified search / URL bar ────────────────────────────────────
  function updateSmartMode() {
    var url = isUrl($('smartInput').value.trim());
    $('smartBtn').textContent = url ? 'Fetch' : 'Search';
    $('smartHint').textContent = url
      ? 'Link detected · opens the format inspector'
      : 'No API key needed · paste any supported link to skip search';
    return url;
  }

  function submitSmart(event) {
    if (event) event.preventDefault();
    var value = $('smartInput').value.trim();
    if (!value) {
      $('smartInput').focus();
      return;
    }
    clearError();
    if (isUrl(value)) openInfo(value);
    else runSearch(value);
  }

  function pasteIntoBar() {
    var clip = navigator.clipboard;
    if (!clip || typeof clip.readText !== 'function') {
      $('smartInput').focus();
      toast('Clipboard access is blocked here', 'error');
      return;
    }
    clip.readText().then(function (text) {
      if (!text) {
        toast('Clipboard is empty', 'error');
        return;
      }
      $('smartInput').value = text.trim();
      updateSmartMode();
      $('smartInput').focus();
    }).catch(function () {
      $('smartInput').focus();
      toast('Clipboard access was denied', 'error');
    });
  }

  function runSearch(query) {
    if (state.searchAbort) state.searchAbort.abort();
    state.searchAbort = typeof AbortController === 'function' ? new AbortController() : null;

    $('smartBtn').disabled = true;
    setLoader(true, 'Searching…');
    $('emptyState').hidden = true;
    $('searchResults').hidden = true;
    $('searchResults').textContent = '';
    $('resultsHead').hidden = true;

    api.search(query, 12, state.searchAbort ? state.searchAbort.signal : undefined)
      .then(function (payload) {
        if (!payload.results.length) {
          $('emptyState').hidden = false;
          showError('No results for “' + query + '”.');
          return;
        }
        renderSearchResults(payload.results, query);
      })
      .catch(function (err) {
        if (err.name === 'AbortError') return;
        $('emptyState').hidden = false;
        showError(err.message);
      })
      .then(function () {
        $('smartBtn').disabled = false;
        setLoader(false);
      });
  }

  function renderSearchResults(results, query) {
    var container = $('searchResults');
    container.textContent = '';
    $('resultsTitle').textContent = 'Results for “' + query + '”';
    $('resultsCount').textContent = results.length + ' found';
    $('resultsHead').hidden = false;

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

      var main = document.createElement('div');
      main.className = 'row-main';

      var title = document.createElement('strong');
      title.textContent = item.title || 'Untitled';
      if (item.is_live) {
        var live = document.createElement('span');
        live.className = 'live';
        live.textContent = 'LIVE';
        title.appendChild(live);
      }
      main.appendChild(title);

      var meta = document.createElement('small');
      var bits = [];
      if (item.duration) bits.push(fmtDuration(item.duration));
      if (item.uploader) bits.push(item.uploader);
      if (item.view_count) bits.push(item.view_count.toLocaleString() + ' views');
      meta.textContent = bits.join(' · ') || item.url;
      main.appendChild(meta);

      button.appendChild(main);
      button.addEventListener('click', function () { openInfo(item.url); });
      container.appendChild(button);
    });

    container.hidden = false;
  }

  // ── inspector ────────────────────────────────────────────────────────────
  function openInfo(url) {
    clearError();
    setLoader(true, 'Fetching metadata…');
    state.info = null;
    state.selectedFormat = null;

    return api.info(url, { sponsorblock: true })
      .then(function (info) {
        state.info = info;
        $('smartInput').value = info.webpage_url || url;
        updateSmartMode();
        renderInfo(info);
        if (!$('inspector').open) $('inspector').showModal();
      })
      .catch(function (err) { showError(err.message); })
      .then(function () { setLoader(false); });
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

    var badges = $('videoBadges');
    badges.textContent = '';
    [
      info.extractor,
      info.duration ? fmtDuration(info.duration) : null,
      info.uploader || info.channel,
      info.view_count ? info.view_count.toLocaleString() + ' views' : null,
      info.is_live ? 'live' : null,
      info.age_limit ? 'age ' + info.age_limit + '+' : null,
      info.cookies_used ? 'cookies' : null
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
      note.textContent = sponsor.segments.length + ' SponsorBlock segments (~' +
        Math.round(seconds / 60) + ' min) · cut on download, skipped on playback.';
      note.hidden = false;
    } else if (sponsor && sponsor.error) {
      note.textContent = 'SponsorBlock lookup failed: ' + sponsor.error;
      note.hidden = false;
    } else {
      note.hidden = true;
    }

    renderFormats(info.formats || []);
    updateAudioHint();
    $('downloadBtn').disabled = false;
    $('playInfoBtn').hidden = !playableStream(info);
  }

  function renderFormats(formats) {
    var grid = $('formatGrid');
    grid.textContent = '';
    var seen = {};

    var usable = formats
      .filter(function (format) { return format.has_video && format.resolution !== 'audio only'; })
      .filter(function (format) {
        var key = (format.resolution || '') + '|' + (format.fps || '');
        if (seen[key]) return false;
        seen[key] = true;
        return true;
      })
      .sort(function (a, b) { return heightOf(a) - heightOf(b); });

    if (!usable.length) {
      var note = document.createElement('p');
      note.className = 'hint';
      note.textContent = 'This source only offers a single stream — use Extract audio or Download.';
      grid.appendChild(note);
      selectAudio();
      return;
    }

    usable.forEach(function (format) {
      var button = document.createElement('button');
      button.type = 'button';
      button.className = 'fmt-btn';

      var res = document.createElement('span');
      res.className = 'res';
      res.textContent = format.note || format.resolution || 'video';
      button.appendChild(res);

      var sub = document.createElement('span');
      sub.className = 'sub';
      sub.textContent = [
        format.ext,
        format.fps ? format.fps + ' fps' : null,
        format.filesize ? fmtBytes(format.filesize) : null
      ].filter(Boolean).join(' · ');
      button.appendChild(sub);

      button.addEventListener('click', function () { selectFormat(format, button); });
      grid.appendChild(button);
    });

    var best = grid.lastElementChild;
    if (best && best.classList.contains('fmt-btn')) best.click();
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
    var labels = { mp3: 'MP3', m4a: 'M4A (AAC)', opus: 'Opus', flac: 'FLAC', wav: 'WAV' };
    $('audioHint').textContent = (labels[$('audioFormat').value] || 'MP3') + ' · audio only';
  }

  // ── playback: native bridge first, HTML5 fallback ────────────────────────
  function playableStream(info) {
    if (!info) return '';
    var formats = info.formats || [];
    var muxed = formats.find(function (format) {
      return format.has_video && format.has_audio && format.direct_url;
    });
    return (muxed && muxed.direct_url) || info.best_video_url || info.best_audio_url || '';
  }

  function playMedia(url, title, thumbnail, audioOnly, segments) {
    if (!url) {
      toast('No stream available for playback', 'error');
      return;
    }
    var bridge = native();
    if (bridge && typeof bridge.playMedia === 'function') {
      bridge.playMedia(
        url,
        title || 'ProDL',
        thumbnail || null,
        !!audioOnly,
        segments && segments.length ? JSON.stringify(segments) : null
      );
      return;
    }
    openWebPlayer(url, title, audioOnly, segments);
  }

  var playerSegments = [];

  function openWebPlayer(url, title, audioOnly, segments) {
    var dialog = $('playerOverlay');
    var video = $('playerVideo');
    var audio = $('playerAudio');

    $('playerTitle').textContent = title || 'Playback';
    video.pause();
    audio.pause();
    video.hidden = !!audioOnly;
    audio.hidden = !audioOnly;

    var target = audioOnly ? audio : video;
    target.src = url;
    playerSegments = segments || [];
    $('playerHint').textContent = playerSegments.length
      ? 'SponsorBlock segments are skipped automatically.'
      : 'Direct CDN stream: no ads, no player wrapper.';
    if (!dialog.open) dialog.showModal();

    var playing = target.play();
    if (playing && typeof playing.catch === 'function') {
      playing.catch(function () {
        $('playerHint').textContent = 'Tap play to start — autoplay was blocked.';
      });
    }
  }

  function wireSponsorSkip() {
    ['playerVideo', 'playerAudio'].forEach(function (id) {
      $(id).addEventListener('timeupdate', function () {
        if (!playerSegments.length) return;
        var time = this.currentTime;
        for (var i = 0; i < playerSegments.length; i += 1) {
          var segment = playerSegments[i];
          if (time >= segment.start && time < segment.end) {
            this.currentTime = Math.min(segment.end + 0.1, this.duration || segment.end);
            break;
          }
        }
      });
    });
  }

  function closePlayer() {
    $('playerVideo').pause();
    $('playerAudio').pause();
    if ($('playerOverlay').open) $('playerOverlay').close();
  }

  // ── tasks: downloads and conversions share one live list ─────────────────
  function createTaskCard(task, meta) {
    var card = document.createElement('article');
    card.className = 'card progress task-card';

    var header = document.createElement('header');
    var title = document.createElement('h3');
    title.textContent = meta.title || 'Working…';
    var kind = document.createElement('span');
    kind.className = 'task-kind';
    kind.textContent = meta.kind === 'convert' ? 'convert' : 'download';
    header.appendChild(title);
    header.appendChild(kind);
    card.appendChild(header);

    var head = document.createElement('div');
    head.className = 'progress-head';
    var statusLabel = document.createElement('strong');
    statusLabel.textContent = 'Queued…';
    var percent = document.createElement('span');
    percent.className = 'progress-percent';
    percent.textContent = '0%';
    head.appendChild(statusLabel);
    head.appendChild(percent);
    card.appendChild(head);

    var track = document.createElement('div');
    track.className = 'progress-track';
    var fill = document.createElement('div');
    fill.className = 'progress-fill indeterminate';
    track.appendChild(fill);
    card.appendChild(track);

    var metaLine = document.createElement('div');
    metaLine.className = 'progress-meta';
    metaLine.textContent = 'Queued…';
    card.appendChild(metaLine);

    var actions = document.createElement('div');
    actions.className = 'progress-actions';
    actions.hidden = true;
    card.appendChild(actions);

    $('taskList').prepend(card);

    var entry = {
      task_id: task.task_id,
      kind: meta.kind || 'download',
      title: meta.title || 'Untitled',
      status: task.status || 'queued',
      file: null,
      task: task,
      channel: null,
      refs: {
        card: card,
        title: title,
        status: statusLabel,
        percent: percent,
        fill: fill,
        meta: metaLine,
        actions: actions
      }
    };
    state.tasks.push(entry);
    refreshTaskUi();
    mountTask(entry);
    return entry;
  }

  function mountTask(entry) {
    entry.channel = api.watchTask(entry.task, {
      onEvent: function (event) { renderTaskProgress(entry, event); },
      onDone: function (event) { finishTask(entry, event); },
      onError: function (err) {
        entry.refs.meta.textContent = err.message;
        entry.refs.status.textContent = 'Stream lost';
        entry.refs.fill.classList.remove('indeterminate');
        toast(err.message, 'error');
      }
    });
  }

  var STATUS_LABELS = {
    queued: 'Queued',
    downloading: 'Downloading',
    postprocessing: 'Processing',
    converting: 'Converting',
    finished: 'Finished',
    error: 'Failed',
    canceled: 'Canceled'
  };

  function renderTaskProgress(entry, event) {
    var refs = entry.refs;
    entry.status = event.status || entry.status;

    if (typeof event.percent === 'number') {
      refs.fill.classList.remove('indeterminate');
      refs.fill.style.width = Math.max(0, Math.min(100, event.percent)) + '%';
      refs.percent.textContent = (event.percent < 10 ? event.percent.toFixed(1) : Math.round(event.percent)) + '%';
    }

    refs.status.textContent = STATUS_LABELS[event.status] || event.message || 'Working';
    if (event.message) refs.title.textContent = event.message;

    var bits = [];
    if (event.speed_str) bits.push(event.speed_str);
    else if (event.speed) bits.push(fmtBytes(event.speed) + '/s');
    if (typeof event.eta === 'number') bits.push('ETA ' + fmtEta(event.eta));
    if (event.downloaded_bytes != null && event.total_bytes) {
      bits.push(fmtBytes(event.downloaded_bytes) + ' / ' + fmtBytes(event.total_bytes));
    }
    if (event.filename) bits.push(event.filename);
    refs.meta.textContent = bits.join(' · ') || event.message || '';
    refreshTaskUi();
  }

  function finishTask(entry, event) {
    var refs = entry.refs;
    entry.status = event.status;
    entry.file = event.file || null;
    refs.fill.classList.remove('indeterminate');

    if (event.status === 'finished') {
      refs.fill.style.width = '100%';
      refs.percent.textContent = '100%';
      refs.status.textContent = entry.kind === 'convert' ? 'Converted' : 'Saved';
      refs.card.classList.add('is-done');
      entry.title = (event.file && event.file.filename) || entry.title;
      refs.title.textContent = entry.title;
      refs.meta.textContent = [
        event.file && event.file.filesize ? fmtBytes(event.file.filesize) : null,
        event.file && event.file.media_type ? event.file.media_type : null
      ].filter(Boolean).join(' · ') || 'ready';
      buildTaskActions(entry, event);
      refreshTaskOptions();
      toast(entry.kind === 'convert' ? 'Conversion finished' : 'Download finished', 'success');
    } else if (event.status === 'canceled') {
      refs.status.textContent = 'Canceled';
      refs.percent.textContent = '—';
      refs.meta.textContent = 'Stopped by you';
      buildTaskActions(entry, event);
    } else {
      refs.status.textContent = 'Failed';
      refs.percent.textContent = '—';
      refs.meta.textContent = event.error || 'Unknown error';
      refs.card.classList.add('is-error');
      buildTaskActions(entry, event);
      toast(event.error || 'Task failed', 'error');
    }
    refreshTaskUi();
  }

  function buildTaskActions(entry, event) {
    var actions = entry.refs.actions;
    actions.textContent = '';
    actions.hidden = false;
    var finished = event.status === 'finished';
    var fileUrl = event.file && event.file.download_url
      ? config.absolute(event.file.download_url)
      : api.fileUrl(entry.task);

    if (finished && fileUrl) {
      var playBtn = document.createElement('button');
      playBtn.type = 'button';
      playBtn.className = 'btn small ghost';
      playBtn.textContent = 'Play';
      playBtn.addEventListener('click', function () {
        var isAudio = /\.(mp3|m4a|opus|oga|flac|wav)$/i.test(entry.title || '');
        var segments = state.info && state.info.sponsorblock ? state.info.sponsorblock.segments : [];
        playMedia(fileUrl, entry.title, state.info && state.info.thumbnail, isAudio, segments);
      });
      actions.appendChild(playBtn);

      var bridge = native();
      if (bridge && typeof bridge.saveToDownloads === 'function') {
        var saveNative = document.createElement('button');
        saveNative.type = 'button';
        saveNative.className = 'btn small primary';
        saveNative.textContent = 'Save to Downloads';
        saveNative.addEventListener('click', function () { bridge.saveToDownloads(entry.task_id); });
        actions.appendChild(saveNative);
      }

      var link = document.createElement('a');
      link.className = 'btn small' + (bridge && typeof bridge.saveToDownloads === 'function' ? ' ghost' : ' primary');
      link.href = fileUrl;
      link.setAttribute('download', (event.file && event.file.filename) || '');
      link.textContent = 'Download file';
      actions.appendChild(link);

      var convertBtn = document.createElement('button');
      convertBtn.type = 'button';
      convertBtn.className = 'btn small ghost';
      convertBtn.textContent = 'Convert';
      convertBtn.addEventListener('click', function () {
        $('convertSource').value = 'task';
        refreshConvertSource(entry.task_id);
        switchView('convert');
      });
      actions.appendChild(convertBtn);

      var removeBtn = document.createElement('button');
      removeBtn.type = 'button';
      removeBtn.className = 'btn small ghost';
      removeBtn.textContent = 'Remove';
      removeBtn.addEventListener('click', function () { removeTask(entry); });
      actions.appendChild(removeBtn);
      return;
    }

    if (event.status === 'canceled' || event.status === 'error') {
      var dismiss = document.createElement('button');
      dismiss.type = 'button';
      dismiss.className = 'btn small ghost';
      dismiss.textContent = 'Dismiss';
      dismiss.addEventListener('click', function () { removeTask(entry); });
      actions.appendChild(dismiss);
      return;
    }

    var cancelBtn = document.createElement('button');
    cancelBtn.type = 'button';
    cancelBtn.className = 'btn small danger';
    cancelBtn.textContent = 'Cancel';
    cancelBtn.addEventListener('click', function () {
      api.cancel(entry.task_id)
        .then(function () { entry.refs.meta.textContent = 'Cancelling…'; })
        .catch(function (err) { toast(err.message, 'error'); });
    });
    actions.appendChild(cancelBtn);
  }

  function removeTask(entry) {
    if (entry.channel && typeof entry.channel.close === 'function') entry.channel.close();
    var index = state.tasks.indexOf(entry);
    if (index !== -1) state.tasks.splice(index, 1);
    entry.refs.card.remove();
    refreshTaskUi();
    refreshTaskOptions();
  }

  function activeTasks() {
    return state.tasks.filter(function (task) {
      return ['queued', 'downloading', 'postprocessing', 'converting'].indexOf(task.status) !== -1;
    });
  }

  function refreshTaskUi() {
    var active = activeTasks().length;
    var done = state.tasks.length - active;
    $('downloadsEmpty').hidden = state.tasks.length > 0;
    $('downloadsCount').textContent = state.tasks.length
      ? active + ' active' + (done ? ' · ' + done + ' finished' : '')
      : 'idle';
    var badge = $('navBadge');
    badge.hidden = active === 0;
    badge.textContent = String(active);
  }

  // ── download ─────────────────────────────────────────────────────────────
  function startDownload() {
    if (!state.info) return;
    var button = $('downloadBtn');
    button.disabled = true;

    var payload = {
      url: state.info.webpage_url || $('smartInput').value.trim(),
      format_id: state.audioOnly ? null : state.selectedFormat,
      audio_only: state.audioOnly,
      audio_format: $('audioFormat').value,
      subtitles: $('optSubtitles').checked,
      embed_subtitles: $('optEmbedSubs').checked,
      sponsorblock: $('optSponsorBlock').checked,
      sponsorblock_action: 'cut',
      thumbnail: $('optThumbnail').checked,
      embed_thumbnail: $('optThumbnail').checked,
      use_cookies: $('optCookies').checked,
      concurrent_fragments: Number($('optFragments').value) || 1
    };

    api.download(payload)
      .then(function (task) {
        $('inspector').close();
        switchView('downloads');
        createTaskCard(task, { kind: 'download', title: state.info.title || 'Download' });
      })
      .catch(function (err) {
        toast(err.message, 'error');
        showError(err.message);
      })
      .then(function () { button.disabled = false; });
  }

  // ── convert ──────────────────────────────────────────────────────────────
  var CONVERT_PROGRESS = {
    panel: 'convertProgress',
    title: 'convertProgressTitle',
    percent: 'convertProgressPercent',
    fill: 'convertProgressFill',
    meta: 'convertProgressMeta',
    actions: 'convertProgressActions',
    saveLink: 'convertSaveLink',
    playLink: 'convertPlayLink',
    closeBtn: 'convertProgressClose'
  };

  function progressRefs(ids) {
    var refs = {};
    Object.keys(ids).forEach(function (key) { refs[key] = $(ids[key]); });
    return refs;
  }

  function finishedTasks() {
    return state.tasks.filter(function (task) { return task.status === 'finished'; });
  }

  function refreshTaskOptions(selectedId) {
    var select = $('convertTaskSelect');
    var previous = selectedId || select.value;
    select.textContent = '';
    var tasks = finishedTasks();
    if (!tasks.length) {
      var option = document.createElement('option');
      option.value = '';
      option.textContent = 'No finished downloads yet';
      select.appendChild(option);
    } else {
      tasks.forEach(function (task) {
        var option = document.createElement('option');
        option.value = task.task_id;
        option.textContent = task.title || task.task_id;
        select.appendChild(option);
      });
      if (previous) select.value = previous;
    }
    updateConvertState();
  }

  function refreshConvertSource(selectedId) {
    var source = $('convertSource').value;
    $('convertTaskField').hidden = source !== 'task';
    $('dropzone').hidden = source === 'task';
    refreshTaskOptions(selectedId);
  }

  function applyConvertCapability(supported) {
    state.convertSupported = supported;
    $('convertHint').hidden = supported;
    $('convertHint').textContent = supported
      ? ''
      : 'This server reports FFmpeg as unavailable, so conversion is disabled. Downloads still work.';
    updateConvertState();
  }

  function updateConvertState() {
    var source = $('convertSource').value;
    var ready = source === 'task' ? !!$('convertTaskSelect').value : !!state.convertFile;
    $('convertBtn').disabled = !ready || state.convertSupported === false;
    $('dropzoneTitle').textContent = state.convertFile ? state.convertFile.name : 'Choose a media file';
  }

  function onFileChosen(file) {
    state.convertFile = file || null;
    if (file) $('convertSource').value = 'file';
    refreshConvertSource();
  }

  function startConvert(event) {
    if (event) event.preventDefault();
    var source = $('convertSource').value;
    var target = $('convertFormat').value;
    var bitrate = $('convertBitrate').value;
    var startValue = $('trimStart').value;
    var endValue = $('trimEnd').value;
    var request;

    if (source === 'task') {
      var taskId = $('convertTaskSelect').value;
      if (!taskId) {
        toast('Pick a finished download first', 'error');
        return;
      }
      request = api.convert({
        task_id: taskId,
        target_format: target,
        audio_bitrate: bitrate,
        start: startValue === '' ? null : Number(startValue),
        end: endValue === '' ? null : Number(endValue)
      });
    } else {
      if (!state.convertFile) {
        toast('Choose a media file first', 'error');
        return;
      }
      var form = new FormData();
      form.append('file', state.convertFile);
      form.append('target_format', target);
      form.append('audio_bitrate', bitrate);
      if (startValue !== '') form.append('start', startValue);
      if (endValue !== '') form.append('end', endValue);
      request = api.convertUpload(form);
    }

    $('convertBtn').disabled = true;
    request
      .then(function (task) {
        mountConvertProgress(progressRefs(CONVERT_PROGRESS), task);
        createTaskCard(task, { kind: 'convert', title: 'Converting to ' + target.toUpperCase() });
        switchView('downloads');
      })
      .catch(function (err) { toast(err.message, 'error'); })
      .then(function () { updateConvertState(); });
  }

  function mountConvertProgress(refs, task) {
    refs.panel.hidden = false;
    refs.actions.hidden = true;
    refs.title.textContent = 'Converting…';
    refs.percent.textContent = '0%';
    refs.fill.style.width = '0%';
    refs.fill.classList.add('indeterminate');
    refs.meta.textContent = 'Queued…';

    api.watchTask(task, {
      onEvent: function (event) {
        if (typeof event.percent === 'number') {
          refs.fill.classList.remove('indeterminate');
          refs.fill.style.width = Math.max(0, Math.min(100, event.percent)) + '%';
          refs.percent.textContent = Math.round(event.percent) + '%';
        }
        var bits = [];
        if (event.speed_str) bits.push(event.speed_str);
        if (typeof event.eta === 'number') bits.push('ETA ' + fmtEta(event.eta));
        if (event.filename) bits.push(event.filename);
        refs.meta.textContent = bits.join(' · ') || event.message || '';
        if (event.message) refs.title.textContent = event.message;
      },
      onDone: function (event) {
        refs.fill.classList.remove('indeterminate');
        if (event.status === 'finished') {
          refs.fill.style.width = '100%';
          refs.percent.textContent = '100%';
          refs.title.textContent = 'Conversion complete';
          if (event.file) {
            refs.saveLink.href = config.absolute(event.file.download_url || task.file_url);
            refs.saveLink.setAttribute('download', event.file.filename || '');
            refs.saveLink.textContent = 'Save ' + (event.file.filename || 'file');
            var isAudio = /\.(mp3|m4a|opus|oga|flac|wav)$/i.test(event.file.filename || '');
            var bridge = native();
            refs.playLink.hidden = !(bridge && typeof bridge.playMedia === 'function');
            refs.playLink.onclick = function () {
              playMedia(refs.saveLink.href, event.file.filename, null, isAudio, null);
            };
            refs.actions.hidden = false;
          }
        } else {
          refs.title.textContent = event.status === 'canceled' ? 'Canceled' : 'Failed';
          refs.meta.textContent = event.error || '';
        }
      },
      onError: function (err) {
        refs.meta.textContent = err.message;
        toast(err.message, 'error');
      }
    });
  }

  // ── settings ─────────────────────────────────────────────────────────────
  function updateApiLabel() {
    $('apiLabel').textContent = config.apiBaseUrl || 'same origin';
    $('apiBaseInput').value = config.apiBaseUrl;
  }

  function refreshCookieStatus() {
    api.cookies()
      .then(function (status) {
        $('cookiesStatus').textContent = status.configured
          ? status.cookie_count + ' cookies · ' + (status.updated_at || '')
          : 'no cookies stored';
      })
      .catch(function (err) { $('cookiesStatus').textContent = err.message; });
  }

  function wireNativeBridge() {
    var bridge = native();
    var button = $('linkYouTubeBtn');
    if (bridge && typeof bridge.openYouTubeLogin === 'function') {
      $('youTubeHint').textContent = 'Opens a private in-app browser. Cookies never leave this device.';
      button.addEventListener('click', function () { bridge.openYouTubeLogin(); });
    } else {
      button.disabled = true;
      $('youTubeHint').textContent =
        'The in-app sign-in ships only with the Android app. On web or desktop, paste a Netscape cookie file below.';
    }
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
        $('cookiesStatus').textContent = status.cookie_count + ' cookies · ' + (status.updated_at || '');
        toast('Cookies saved on this device', 'success');
        refreshHealth();
      })
      .catch(function (err) { toast(err.message, 'error'); });
  }

  function clearCookies() {
    api.clearCookies()
      .then(function () {
        $('cookiesStatus').textContent = 'no cookies stored';
        toast('Cookies deleted', 'success');
        refreshHealth();
      })
      .catch(function (err) { toast(err.message, 'error'); });
  }

  // ── wiring ───────────────────────────────────────────────────────────────
  function init() {
    switchView('explore');
    updateApiLabel();
    refreshHealth();
    setInterval(refreshHealth, 60000);

    Object.keys(NAV).forEach(function (key) {
      $(NAV[key]).addEventListener('click', function () { switchView(key); });
    });
    $('healthPill').addEventListener('click', function () { switchView('settings'); });

    $('smartForm').addEventListener('submit', submitSmart);
    $('smartInput').addEventListener('input', updateSmartMode);
    $('pasteBtn').addEventListener('click', pasteIntoBar);
    updateSmartMode();

    $('audioBtn').addEventListener('click', selectAudio);
    $('audioFormat').addEventListener('change', function () {
      updateAudioHint();
      if (state.info) selectAudio();
    });
    $('downloadBtn').addEventListener('click', startDownload);
    $('playInfoBtn').addEventListener('click', function () {
      var info = state.info;
      if (!info) return;
      var segments = info.sponsorblock && info.sponsorblock.segments ? info.sponsorblock.segments : [];
      playMedia(playableStream(info), info.title, info.thumbnail, false, segments);
    });

    $('convertForm').addEventListener('submit', startConvert);
    $('convertSource').addEventListener('change', function () { refreshConvertSource(); });
    $('convertTaskSelect').addEventListener('change', updateConvertState);
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

    $('convertProgressClose').addEventListener('click', function () {
      $('convertProgress').hidden = true;
    });

    $('saveApiBtn').addEventListener('click', function () {
      config.setApiBase($('apiBaseInput').value);
      updateApiLabel();
      toast('Backend URL saved', 'success');
      refreshHealth();
    });
    $('resetApiBtn').addEventListener('click', function () {
      config.resetApiBase();
      updateApiLabel();
      toast('Backend URL reset', 'success');
      refreshHealth();
    });
    $('refreshHealthBtn').addEventListener('click', refreshHealth);
    $('saveCookiesBtn').addEventListener('click', saveCookies);
    $('clearCookiesBtn').addEventListener('click', clearCookies);

    $('playerClose').addEventListener('click', closePlayer);
    wireSponsorSkip();
    wireNativeBridge();
    refreshCookieStatus();
    refreshConvertSource();
    refreshTaskUi();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
