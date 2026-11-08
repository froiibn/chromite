/*
 * Chromite - Database Workbench
 * Copyright (C) 2026 Chromite contributors
 * Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 *
 * The download page's script (docs/chromite/distribution.md "Site"): fills the Windows and macOS cards with
 * the newest setup and disk image of the feed: download link, version, size and date.
 *
 * Design:
 *   - The site's CSP has no connect-src (default-src 'none'), so the page cannot fetch
 *     /downloads/releases.json. releases.sh writes the same JSON as /downloads/releases.js
 *     (window.chromiteReleases), which the page loads as a same-origin script before this one, with
 *     each file's upload time (window.chromiteReleaseDates; the feed itself has no date).
 *   - The feed is checked as strictly as the updater does: format 1, file names and versions by
 *     pattern, a 64-hex sha256; an entry that does not pass is ignored. The checksum is not shown on the
 *     page (user request 2026-11-08), but a malformed one still keeps that entry off the download buttons.
 *   - Text comes from the page (data-* attributes, so each language page carries its own words);
 *     values are set with textContent only.
 *   - The page starts in data-state "static", which is what a browser without script keeps: both cards
 *     with their requirements and a note on where the files are. This script always replaces it with
 *     ready, error or empty.
 *   - The visitor's own platform comes first (user request 2026-11-07): the HTML shows both cards, and
 *     only when the browser clearly says Windows or macOS and that card has a file does the script move
 *     it to the top, hide the other card and add a button ("download for the other OS", aria-expanded)
 *     that shows it again. Unknown systems (Linux, phones, tablets, bots) and pages without script keep
 *     both cards, so nothing is reachable only by script.
 *   - The platform is read from navigator.userAgentData.platform where the browser has it, otherwise
 *     from navigator.platform, otherwise from the user agent. An iPad reports "MacIntel" like a Mac, so a
 *     "Mac" with a touch screen counts as unknown. An Intel Mac cannot be told from an Apple silicon one
 *     in the browser, which is why the macOS card keeps its "Apple silicon" requirement in view.
 *
 * Change history:
 * - 2026-11-07 Created.
 * - 2026-11-07 Visitor's platform first, the other behind a button.
 * - 2026-11-08 The SHA-256 row and its copy button are gone from the cards: no checksum output, no copy handler.
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
    var seconds = dates && typeof dates[f.file] === 'number' ? dates[f.file] : null;
    field(card, 'date').textContent = seconds === null ? '-' :
      new Intl.DateTimeFormat(lang, { dateStyle: 'long' }).format(new Date(seconds * 1000));
    card.setAttribute('data-ready', 'true');
  }

  /** 'windows', 'macos' or null when the browser does not clearly say either. */
  function visitorPlatform() {
    var data = navigator.userAgentData;
    var name = data && typeof data.platform === 'string' && data.platform ? data.platform : navigator.platform || '';
    if (!name) {
      var ua = navigator.userAgent || '';
      name = /Windows NT/.test(ua) ? 'Windows' : /Macintosh/.test(ua) ? 'Mac' : '';
    }
    if (/^win/i.test(name)) {
      return 'windows';
    }
    if (/^mac/i.test(name) && !(navigator.maxTouchPoints > 1)) {
      return 'macos';
    }
    return null;
  }

  /** Puts the visitor's card first and the other card behind a button; leaves both when unsure. */
  function preferVisitorPlatform() {
    var platform = visitorPlatform();
    var mine = platform && area.querySelector('.card[data-platform="' + platform + '"]');
    var other = platform && area.querySelector('.card[data-platform]:not([data-platform="' + platform + '"])');
    if (!mine || !other || mine.getAttribute('data-ready') !== 'true') {
      return;
    }
    var list = mine.parentNode;
    list.insertBefore(mine, list.firstChild);
    var row = document.createElement('div');
    row.className = 'other-os';
    var button = document.createElement('button');
    button.type = 'button';
    button.textContent = other.getAttribute('data-other-label');
    button.setAttribute('aria-expanded', 'false');
    button.setAttribute('aria-controls', other.id);
    button.addEventListener('click', function () {
      var open = button.getAttribute('aria-expanded') !== 'true';
      button.setAttribute('aria-expanded', open ? 'true' : 'false');
      other.hidden = !open;
    });
    row.appendChild(button);
    list.insertBefore(row, mine.nextSibling);
    list.appendChild(other);
    other.hidden = true;
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
    if (any) {
      preferVisitorPlatform();
    }
  }
}());
