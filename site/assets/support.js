/*
 * Chromite - Database Workbench
 * Copyright (C) 2026 Chromite contributors
 * Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 *
 * The filter box of the support page (/ko|en/support.html, docs/chromite/distribution.md "기능과 지원 범위 페이지").
 *
 * Design:
 *   - Progressive enhancement: the box is hidden in the HTML and only this script shows it, so a browser without
 *     script sees the whole table and no box that does nothing.
 *   - A row matches when its data-terms (the name plus other names people search for, lower case) contains the
 *     typed text. A group whose rows are all hidden is hidden too, so no empty group heading is left.
 *   - The count line reads its wording from data-count of the box (one template per page language), so the
 *     script holds no user-visible text.
 *   - A separate file, not site.js: the site's CSP allows same-origin scripts only, and only this page needs it.
 *
 * Change history:
 * - 2026-11-08 Created with the support page.
 */
(function () {
  'use strict';
  var box = document.querySelector('.filter');
  var input = document.getElementById('db-filter');
  var table = document.querySelector('.matrix table');
  if (!box || !input || !table) {
    return;
  }
  var rows = Array.prototype.slice.call(table.querySelectorAll('tbody tr[data-terms]'));
  var groups = Array.prototype.slice.call(table.tBodies);
  var count = box.querySelector('.filter-count');
  var none = document.querySelector('.filter-none');
  var template = box.getAttribute('data-count') || '{shown} / {total}';

  function apply() {
    var q = input.value.toLowerCase().replace(/\s+/g, ' ').trim();
    var shown = 0;
    rows.forEach(function (row) {
      var hit = !q || row.getAttribute('data-terms').indexOf(q) !== -1;
      row.hidden = !hit;
      if (hit) {
        shown++;
      }
    });
    groups.forEach(function (group) {
      group.hidden = !group.querySelector('tr[data-terms]:not([hidden])');
    });
    count.textContent = template.replace('{shown}', String(shown)).replace('{total}', String(rows.length));
    if (none) {
      none.hidden = shown !== 0;
    }
  }

  input.addEventListener('input', apply);
  box.hidden = false;
  apply();
})();
