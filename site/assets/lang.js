/*
 * Chromite - Database Workbench
 * Copyright (C) 2026 Chromite contributors
 * Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 *
 * "/" (index.html) is a language chooser. The server does not pick a language (static files only), so
 * this script sends a browser that prefers Korean to /ko/ and any other to /en/. Without script the two
 * links on the page do the same.
 *
 * Change history:
 * - 2026-11-07 Created.
 */
(function () {
  'use strict';
  var first = (navigator.languages && navigator.languages[0]) || navigator.language || '';
  location.replace(/^ko\b/i.test(first) ? '/ko/' : '/en/');
}());
