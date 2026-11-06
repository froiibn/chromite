/*
 * Chromite - Database Workbench
 * Copyright (C) 2026 Chromite contributors
 * Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 *
 * The download page's script (docs/chromite/distribution.md "Site"): fills the Windows and macOS cards with
 * the newest setup and disk image of the feed: version, size, date and SHA-256.
 *
 * Design:
 *   - The site's CSP has no connect-src (default-src 'none'), so the page cannot fetch
 *     /downloads/releases.json. releases.sh writes the same JSON as /downloads/releases.js
 *     (window.chromiteReleases), which the page loads as a same-origin script before this one, with
 *     each file's upload time (window.chromiteReleaseDates; the feed itself has no date).
 *   - The feed is checked as strictly as the updater does: format 1, file names and versions by
 *     pattern, a 64-hex sha256; an entry that does not pass is ignored.
 *   - Text comes from the page (data-* attributes, so each language page carries its own words);
 *     values are set with textContent only.
 *
 * Change history:
 * - 2026-11-07 Created.
 */
(function () {
  'use strict';

  var area = document.querySelector('.downloads-area');
  if (!area) {
    return;
  }
  var lang = document.documentElement.lang || 'en';
  var NAME = /^Chromite-(\d+)\.(\d+)\.(\d+)-(x64-setup\.exe|arm64\.zip|arm64\.dmg)$/;
  var VERSION = /^\d+\.\d+\.\d+$/;
  var SHA256 = /^[0-9a-f]{64}$/;

  function compare(a, b) {
    var x = a.split('.'), y = b.split('.');
    for (var i = 0; i < 3; i++) {
      var d = Number(x[i]) - Number(y[i]);
      if (d !== 0) {
        return d;
      }
    }
    return 0;
  }

  function valid(f) {
    if (!f || typeof f.file !== 'string' || typeof f.version !== 'string') {
      return false;
    }
    var m = NAME.exec(f.file);
    return !!m && VERSION.test(f.version) && f.version === m[1] + '.' + m[2] + '.' + m[3] &&
      typeof f.sha256 === 'string' && SHA256.test(f.sha256) &&
      typeof f.size === 'number' && f.size > 0 && Math.floor(f.size) === f.size;
  }

  function newest(files, platform, arch, kind) {
    var best = null;
    files.forEach(function (f) {
      if (f.platform === platform && f.arch === arch && f.kind === kind && (!best || compare(f.version, best.version) > 0)) {
        best = f;
      }
    });
    return best;
  }

  function field(card, name) {
    return card.querySelector('[data-field="' + name + '"]');
  }

  function megabytes(bytes) {
    return new Intl.NumberFormat(lang, { maximumFractionDigits: 1 }).format(bytes / 1048576) + ' MB';
  }

  function fill(card, f, dates) {
    field(card, 'link').href = '/downloads/' + encodeURIComponent(f.file);
    field(card, 'version').textContent = f.version;
    field(card, 'size').textContent = megabytes(f.size);
    field(card, 'sha256').textContent = f.sha256;
    var seconds = dates && typeof dates[f.file] === 'number' ? dates[f.file] : null;
    field(card, 'date').textContent = seconds === null ? '-' :
      new Intl.DateTimeFormat(lang, { dateStyle: 'long' }).format(new Date(seconds * 1000));
    card.setAttribute('data-ready', 'true');
  }

  var feed = window.chromiteReleases;
  if (!feed || feed.format !== 1 || !Array.isArray(feed.files)) {
    area.setAttribute('data-state', 'error');
  } else {
    var files = feed.files.filter(valid);
    var any = false;
    Array.prototype.forEach.call(area.querySelectorAll('.card[data-platform]'), function (card) {
      var f = newest(files, card.getAttribute('data-platform'), card.getAttribute('data-arch'), card.getAttribute('data-kind'));
      if (f) {
        fill(card, f, window.chromiteReleaseDates);
        any = true;
      } else {
        card.setAttribute('data-ready', 'false');
      }
    });
    area.setAttribute('data-state', any ? 'ready' : 'empty');
  }

  area.addEventListener('click', function (e) {
    var button = e.target.closest('button.copy');
    if (!button || !navigator.clipboard) {
      return;
    }
    var code = button.parentNode.querySelector('code');
    navigator.clipboard.writeText(code.textContent).then(function () {
      var label = button.textContent;
      button.textContent = button.getAttribute('data-done');
      setTimeout(function () { button.textContent = label; }, 1500);
    });
  });
}());
