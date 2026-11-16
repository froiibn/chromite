/*
 * Chromite - Database Workbench
 * Copyright (C) 2026 Chromite contributors
 * Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 *
 * The one-time reveal of the home page's stage scenes and the switch for their loops
 * (docs/chromite/distribution.md "Site", "Home stage").
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
 *   - The loop (each screenshot zooming into its parts on a timer, like a GIF; keyframes in site.css) runs
 *     only while its scene is on screen: a second observer, with no margin, adds "is-playing" while any
 *     part of a scene is in view and removes it when the scene leaves, and site.css pauses every scene
 *     without it. When the tab is hidden every scene loses the class, and the ones still in view get it
 *     back when the tab is shown again. That is all the script does for the loop: two observers and one
 *     visibility listener, no per-frame work.
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
 * - 2026-11-08 "is-playing" for the looping screenshots that replace the highlight boxes (user: "like a
 *   GIF, the page should feel alive"): set while a scene is in view and the tab is visible.
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
      // the scenes now on screen, so a tab shown again restarts exactly those
      var inView = [];
      var playing = new IntersectionObserver(function (entries) {
        entries.forEach(function (entry) {
          var at = inView.indexOf(entry.target);
          if (entry.isIntersecting && at < 0) {
            inView.push(entry.target);
          } else if (!entry.isIntersecting && at >= 0) {
            inView.splice(at, 1);
          }
          entry.target.classList.toggle('is-playing', entry.isIntersecting && !document.hidden);
        });
      });
      Array.prototype.forEach.call(document.querySelectorAll('.scene'), function (scene) {
        observer.observe(scene);
        playing.observe(scene);
      });
      document.addEventListener('visibilitychange', function () {
        inView.forEach(function (scene) {
          scene.classList.toggle('is-playing', !document.hidden);
        });
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
