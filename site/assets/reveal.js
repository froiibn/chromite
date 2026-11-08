/*
 * Chromite - Database Workbench
 * Copyright (C) 2026 Chromite contributors
 * Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 *
 * The one-time reveal of the home page's stage scenes (docs/chromite/distribution.md "Site", "Home stage").
 *
 * Design:
 *   - The user found the scroll-linked stage (stage.js, motion as a function of the scroll position) janky
 *     and chose a reveal on enter instead: the page scrolls normally, and each scene plays a short CSS
 *     transition once when it first comes into view. This file only decides when; site.css has the motion
 *     (transform and opacity, time-based), so the script does nothing per scroll frame - no scroll
 *     listener, no requestAnimationFrame, no layout reads.
 *   - "In view" is the scene's top edge passing the line 20% above the bottom of the view (rootMargin
 *     -20% at the bottom, threshold 0), not a share of the scene: a tall scene on a phone can be several
 *     views high and would never reach 25% visible.
 *   - Each scene is unobserved once revealed and never hidden again; scrolling back shows it as it is.
 *   - The html element gets the class "reveal" right away (this file is loaded without defer in <head>, so
 *     the hidden state is in place before the first paint and nothing flashes). Only then does site.css
 *     hide anything, and only for screens with prefers-reduced-motion: no-preference. Without the class -
 *     no script, an old browser without IntersectionObserver, "reduce motion" - everything is shown at
 *     once. If setting up the observer fails, the class is removed again, so content never stays hidden.
 *   - The site's CSP allows scripts from this origin only (script-src 'self'), so this is a file, not an
 *     inline script; it changes classes only, no style attributes.
 *
 * Change history:
 * - 2026-11-08 Created; replaces stage.js (scroll-linked motion with sticky scenes).
 */
(function () {
  'use strict';

  var root = document.documentElement;
  var reduce = window.matchMedia ? window.matchMedia('(prefers-reduced-motion: reduce)') : null;
  if (!reduce || reduce.matches || !('IntersectionObserver' in window)) {
    return;
  }
  root.classList.add('reveal');

  function init() {
    try {
      var observer = new IntersectionObserver(function (entries) {
        entries.forEach(function (entry) {
          if (entry.isIntersecting) {
            entry.target.classList.add('is-in');
            observer.unobserve(entry.target);
          }
        });
      }, { rootMargin: '0px 0px -20% 0px' });
      Array.prototype.forEach.call(document.querySelectorAll('.scene'), function (scene) {
        observer.observe(scene);
      });
    } catch (e) {
      root.classList.remove('reveal');
    }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
}());
