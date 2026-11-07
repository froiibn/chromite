/*
 * Chromite - Database Workbench
 * Copyright (C) 2026 Chromite contributors
 * Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 *
 * The scroll-linked stage on the home pages (docs/chromite/distribution.md "Site", user request 2026-11-08:
 * "like an Apple product page", motion that follows the scroll continuously).
 *
 * Design:
 *   - Each .scene is taller than the view and holds a sticky .pin of one view's height. The scroll position
 *     inside the scene is its progress p (0 when the pin reaches the top, 1 when it is about to leave), and
 *     every animated element is a pure function of p, so scrolling back plays the motion backwards and a
 *     reload in the middle of the page shows the right state at once.
 *   - What moves is written in the page, not here: data-t="o 0 1 .1 .3; s 1 2 .2 .4" means opacity from 0 to
 *     1 while p goes from .1 to .3, then scale from 1 to 2 while p goes from .2 to .4. Properties are o
 *     (opacity), x and y (translate, with the unit written: px, %, vh, vw), s (scale) and r (rotateX, deg).
 *     Several segments of one property are a timeline: before the first one the value is its start value,
 *     after each one its end value holds. Each language page can then aim a zoom at the place where its own
 *     screenshot has the thing being described.
 *   - The items of a .slot (the short lines under a scene's heading) get their timeline here: the scene's
 *     progress (after a short lead-in, so the first line is not cut short while the scene arrives) is cut
 *     into equal parts and the items replace one another, the last one staying. At each border the old
 *     line leaves before the new one rises in: two half-faded lines in the same place read as garbage.
 *     The CSS stacks them in one grid cell only in motion mode.
 *   - Only transform and opacity change, from one requestAnimationFrame per scroll, and only for scenes that
 *     an IntersectionObserver reports in view (all scenes once at start and on resize).
 *   - The html element gets the class "motion" right away (this file is loaded without defer in <head>, so
 *     the tall scenes are in place before the first paint), and only when the browser has
 *     IntersectionObserver and the visitor does not ask for reduced motion. Without it - no script, an old
 *     browser, "reduce motion", or that setting turned on while the page is open - the CSS lays every scene
 *     out as a normal section with all of its text visible and nothing moving.
 *   - A line that is faded out takes no clicks (pointer-events), so the link in a later line under it is
 *     not covered; it stays in the accessibility tree, so a screen reader still reads every line.
 *   - The site's CSP allows scripts from this origin only (script-src 'self'), so this is a file, not an
 *     inline script. Styles are set through the CSSOM, which the CSP does not restrict.
 *
 * Change history:
 * - 2026-11-08 Created; replaces tour.js (reveal once per screen, scroll snapping).
 */
(function () {
  'use strict';

  var root = document.documentElement;
  var reduce = window.matchMedia ? window.matchMedia('(prefers-reduced-motion: reduce)') : null;
  if (!reduce || reduce.matches || !('IntersectionObserver' in window) || !window.requestAnimationFrame) {
    return;
  }
  root.classList.add('motion');

  var scenes = [];
  var active = [];
  var queued = false;
  var observer = null;

  function clamp(v) {
    return v < 0 ? 0 : v > 1 ? 1 : v;
  }

  /** Smoothstep: starts and ends at rest, like a hand moving the page. */
  function ease(k) {
    return k * k * (3 - 2 * k);
  }

  /** "o 0 1 .1 .3; y 40px 0px 0 .2" -> { o: [segment], y: [segment] }, each list in start order. */
  function parse(spec) {
    var tracks = {};
    spec.split(';').forEach(function (part) {
      var w = part.trim().split(/\s+/);
      if (w.length !== 5) {
        return;
      }
      var to = /^(-?[\d.]+)([a-z%]*)$/.exec(w[2]);
      var from = /^(-?[\d.]+)([a-z%]*)$/.exec(w[1]);
      if (!from || !to) {
        return;
      }
      (tracks[w[0]] = tracks[w[0]] || []).push({
        a: parseFloat(from[1]),
        b: parseFloat(to[1]),
        unit: to[2],
        t0: parseFloat(w[3]),
        t1: parseFloat(w[4])
      });
    });
    Object.keys(tracks).forEach(function (k) {
      tracks[k].sort(function (m, n) { return m.t0 - n.t0; });
    });
    return tracks;
  }

  function value(segments, p) {
    var v = segments[0].a;
    segments.forEach(function (g) {
      if (p >= g.t0) {
        v = g.a + (g.b - g.a) * ease(g.t1 > g.t0 ? clamp((p - g.t0) / (g.t1 - g.t0)) : 1);
      }
    });
    return v;
  }

  function unit(tracks, name, fallback) {
    return tracks[name] ? tracks[name][0].unit || fallback : fallback;
  }

  /** Timelines for the lines of a .slot: after the lead-in, n equal parts; out, then in, at each border. */
  function slotSpecs(items) {
    var n = items.length;
    var LEAD = 0.08;
    return items.map(function (item, i) {
      var spec = [];
      var start = LEAD + (1 - LEAD) * i / n;
      var end = LEAD + (1 - LEAD) * (i + 1) / n;
      if (i > 0) {
        spec.push('o 0 1 ' + (start + 0.004) + ' ' + (start + 0.035), 'y 24px 0px ' + (start + 0.004) + ' ' + (start + 0.05));
      }
      if (i < n - 1) {
        spec.push('o 1 0 ' + (end - 0.03) + ' ' + (end - 0.004));
      }
      return { el: item, tracks: parse(spec.join(';')) };
    });
  }

  function apply(item, p) {
    var t = item.tracks;
    var s = item.el.style;
    if (t.o) {
      var o = value(t.o, p);
      s.opacity = o.toFixed(3);
      s.pointerEvents = o < 0.5 ? 'none' : '';
    }
    if (t.x || t.y || t.s || t.r) {
      s.transform =
        'translate3d(' + (t.x ? value(t.x, p) + unit(t, 'x', 'px') : '0') + ',' +
        (t.y ? value(t.y, p) + unit(t, 'y', 'px') : '0') + ',0)' +
        (t.r ? ' rotateX(' + value(t.r, p) + 'deg)' : '') +
        (t.s ? ' scale(' + value(t.s, p) + ')' : '');
    }
  }

  function update(scene) {
    var rect = scene.el.getBoundingClientRect();
    var travel = scene.el.offsetHeight - window.innerHeight;
    var p = travel > 0 ? clamp(-rect.top / travel) : 0;
    scene.items.forEach(function (item) {
      apply(item, p);
    });
  }

  function frame() {
    queued = false;
    active.forEach(update);
  }

  function queue() {
    if (!queued) {
      queued = true;
      window.requestAnimationFrame(frame);
    }
  }

  /** "Reduce motion" turned on while the page is open: back to the still page. */
  function stop() {
    root.classList.remove('motion');
    window.removeEventListener('scroll', queue);
    window.removeEventListener('resize', updateAll);
    if (observer) {
      observer.disconnect();
    }
    scenes.forEach(function (scene) {
      scene.items.forEach(function (item) {
        item.el.style.opacity = '';
        item.el.style.transform = '';
        item.el.style.pointerEvents = '';
      });
    });
    active = [];
  }

  function updateAll() {
    scenes.forEach(update);
  }

  function init() {
    if (!root.classList.contains('motion')) {
      return;
    }
    Array.prototype.forEach.call(document.querySelectorAll('.scene'), function (el) {
      var items = Array.prototype.map.call(el.querySelectorAll('[data-t]'), function (node) {
        return { el: node, tracks: parse(node.getAttribute('data-t')) };
      });
      Array.prototype.forEach.call(el.querySelectorAll('.slot'), function (slot) {
        items = items.concat(slotSpecs(Array.prototype.slice.call(slot.children)));
      });
      scenes.push({ el: el, items: items });
    });
    observer = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        var scene = null;
        scenes.forEach(function (s) {
          if (s.el === entry.target) {
            scene = s;
          }
        });
        var at = active.indexOf(scene);
        if (entry.isIntersecting && at < 0) {
          active.push(scene);
        } else if (!entry.isIntersecting && at >= 0) {
          active.splice(at, 1);
          // leaving the view: settle at the end the visitor scrolled towards
          update(scene);
        }
      });
      queue();
    });
    scenes.forEach(function (scene) {
      observer.observe(scene.el);
    });
    updateAll();
    window.addEventListener('scroll', queue, { passive: true });
    window.addEventListener('resize', updateAll);
    if (reduce.addEventListener) {
      reduce.addEventListener('change', function (e) {
        if (e.matches) {
          stop();
        }
      });
    }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
}());
