/*
 * Chromite - Database Workbench
 * Copyright (C) 2026 Chromite contributors
 * Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 *
 * The feature tour on the home pages (docs/chromite/distribution.md "Site", user request 2026-11-07: one
 * strength per screen, shown as the visitor scrolls).
 *
 * Design:
 *   - Progressive enhancement only. The HTML and CSS show every screen without this file; scroll snapping is
 *     CSS. This script adds two things: each screen's text and screenshot fade in once when the screen
 *     enters the view, and the index next to the tour marks the screen in the middle of the view
 *     (aria-current="true", like the selected row of a tool window).
 *   - Content is hidden only after this script has run and only while the class "is-animated" is on the tour.
 *     The class is set only when IntersectionObserver exists and the visitor does not ask for reduced motion,
 *     so a browser without script, an old browser or "reduce motion" keeps everything visible and still.
 *   - The site's CSP allows scripts from this origin only (script-src 'self'), so this is a file, not an
 *     inline script.
 *
 * Change history:
 * - 2026-11-07 Created.
 */
(function () {
  'use strict';

  var tour = document.querySelector('.tour');
  if (!tour || !('IntersectionObserver' in window)) {
    return;
  }
  var screens = Array.prototype.slice.call(tour.querySelectorAll('.feature'));
  var links = {};
  Array.prototype.forEach.call(tour.querySelectorAll('.tour-index a'), function (a) {
    links[a.getAttribute('href').slice(1)] = a;
  });

  // The index: the screen that crosses the middle line of the view is the current one.
  var current = null;
  var middle = new IntersectionObserver(function (entries) {
    entries.forEach(function (entry) {
      if (!entry.isIntersecting) {
        return;
      }
      var link = links[entry.target.id];
      if (!link || link === current) {
        return;
      }
      if (current) {
        current.removeAttribute('aria-current');
      }
      link.setAttribute('aria-current', 'true');
      current = link;
    });
  }, { rootMargin: '-50% 0px -50% 0px' });
  screens.forEach(function (s) {
    middle.observe(s);
  });

  // The reveal: once per screen, and not at all with reduced motion.
  var reduce = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  if (reduce) {
    return;
  }
  var reveal = new IntersectionObserver(function (entries) {
    entries.forEach(function (entry) {
      if (entry.isIntersecting) {
        entry.target.classList.add('is-in');
        reveal.unobserve(entry.target);
      }
    });
  }, { threshold: 0.15 });
  screens.forEach(function (s) {
    // A screen already above the view (a reload in the middle of the page) is not hidden again.
    if (s.getBoundingClientRect().bottom < 0) {
      s.classList.add('is-in');
    } else {
      reveal.observe(s);
    }
  });
  tour.classList.add('is-animated');
})();
