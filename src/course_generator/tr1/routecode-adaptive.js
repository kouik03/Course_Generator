/* TrailRace+ PRO route code TR1 - encoder and decoder, JS port of tools/routecode.py.
   Must produce the SAME bytes as the Python reference (checked by
   tools/test_routecode.py through node). Used inline by
   docs/trailrace-generator.html; works in node (module.exports) too.
   Format and algorithm: see the docstring of tools/routecode.py. */
(function (G) {
  'use strict';
  var SERVICES = 'WICGFSBTDR';
  var TOLS = [15.0, 20.0, 25.0, 30.0], MAX_FIELDS = 3, UNIT_DM = 40, ZTOL_M = 4.0, PSTEP = 25.0, PART = 2000, R_LAT = 111195.0;

  function rnd(x) { return Math.floor(x + 0.5); }
  function hyp(dx, dy) { return Math.sqrt(dx * dx + dy * dy); }
  function clamp(v, a, b) { return Math.max(a, Math.min(b, v)); }

  function crc16(bytes, len) {
    var c = 0xFFFF;
    for (var i = 0; i < len; i++) {
      c ^= bytes[i] << 8;
      for (var k = 0; k < 8; k++) c = (c & 0x8000) ? ((c << 1) ^ 0x1021) & 0xFFFF : (c << 1) & 0xFFFF;
    }
    return c;
  }
  function segD(p, a, b) {
    var dx = b[0] - a[0], dy = b[1] - a[1], l2 = dx * dx + dy * dy;
    var t = l2 === 0 ? 0.0 : clamp(((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / l2, 0.0, 1.0);
    return hyp(a[0] + t * dx - p[0], a[1] + t * dy - p[1]);
  }
  function dp(n, distFn, tol) {
    var keep = new Array(n).fill(false);
    keep[0] = keep[n - 1] = true;
    var stack = [[0, n - 1]];
    while (stack.length) {
      var ij = stack.pop(), i = ij[0], j = ij[1], best = -1.0, bi = -1;
      for (var k = i + 1; k < j; k++) { var d = distFn(k, i, j); if (d > best) { best = d; bi = k; } }
      if (best > tol) { keep[bi] = true; stack.push([i, bi]); stack.push([bi, j]); }
    }
    var out = [];
    for (var q = 0; q < n; q++) if (keep[q]) out.push(q);
    return out;
  }
  function smooth(a, n) {
    var out = [];
    for (var i = 0; i < a.length; i++) {
      var lo = Math.max(0, i - n), hi = Math.min(a.length, i + n + 1), s = 0;
      for (var j = lo; j < hi; j++) s += a[j];
      out.push(s / (hi - lo));
    }
    return out;
  }
  function utf8(str) {
    var out = [];
    for (var i = 0; i < str.length; i++) {
      var c = str.codePointAt(i);
      if (c > 0xFFFF) i++;
      if (c < 0x80) out.push([c]);
      else if (c < 0x800) out.push([0xC0 | (c >> 6), 0x80 | (c & 63)]);
      else if (c < 0x10000) out.push([0xE0 | (c >> 12), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63)]);
      else out.push([0xF0 | (c >> 18), 0x80 | ((c >> 12) & 63), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63)]);
    }
    // whole characters only, at most 24 bytes
    var bytes = [];
    for (var k = 0; k < out.length; k++) { if (bytes.length + out[k].length > 24) break; bytes = bytes.concat(out[k]); }
    return bytes;
  }
  function b64(bytes) {
    if (typeof Buffer !== 'undefined') return Buffer.from(bytes).toString('base64');
    var s = '';
    for (var i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]);
    return btoa(s);
  }

  /* piecewise-linear map of v from the fa anchors to the ta anchors */
  function remap(v, fa, ta) {
    if (v <= fa[0]) return ta[0];
    for (var j = 1; j < fa.length; j++) {
      if (v <= fa[j]) {
        if (fa[j] <= fa[j - 1]) return ta[j];
        return ta[j - 1] + (ta[j] - ta[j - 1]) * ((v - fa[j - 1]) / (fa[j] - fa[j - 1]));
      }
    }
    return ta[ta.length - 1];
  }
  /* checkpoint km re-measured along the decoded line (routecode.py
     _plan_on_line); the finish keeps its km */
  function planOnLine(plan, aDec, aSrc, total, srcTotal) {
    if (!plan.length || total <= 0) return plan;
    var fin = plan[plan.length - 1].km;
    return plan.map(function (c, i) {
      var o = {}; for (var key in c) o[key] = c[key];
      if (i < plan.length - 1 && fin > 0) o.km = remap(c.km / fin * srcTotal, aSrc, aDec) / total * fin;
      return o;
    });
  }

  /* plan: [{km, name, cut (minutes, -1 none), rel (minutes after the start,
     -1 none; wins over cut), srv 'WF', dp (m, -1 unknown)}]
     -> bytes of the checkpoint block (tools/routecode.py plan_bytes) */
  var REL_MAX = 199 * 60 + 59;
  function hasRel(plan) { return (plan || []).some(function (c) { return c.rel !== null && c.rel !== undefined && c.rel >= 0; }); }
  function cut16(c) {
    if (c.rel !== null && c.rel !== undefined && c.rel >= 0) return 0x8000 | Math.min(REL_MAX, Math.trunc(c.rel));
    return (c.cut === null || c.cut === undefined || c.cut < 0) ? 0xFFFF : Math.min(2879, Math.trunc(c.cut));
  }
  function planBytes(plan) {
    var b = [plan.length];
    plan.forEach(function (c) {
      var mask = 0;
      (c.srv || '').split('').forEach(function (ch) { var i = SERVICES.indexOf(ch); if (i >= 0) mask |= 1 << i; });
      var dist = clamp(rnd(c.km * 100), 0, 65535);
      var cut = cut16(c);
      var dp = (c.dp === null || c.dp === undefined || c.dp < 0) ? 0xFFFF : Math.min(65534, Math.trunc(c.dp));
      var nb = utf8(c.name || '');
      b.push((dist >> 8) & 255, dist & 255, (cut >> 8) & 255, cut & 255, (mask >> 8) & 255, mask & 255, (dp >> 8) & 255, dp & 255, nb.length);
      b = b.concat(nb);
    });
    return b;
  }

  /* track: [[lat, lon, ele|null], ...] -> {code, vertices, profile, lengthM, climbM} */
  /* tolerance 15 m first, coarser (20/25/30 m) while the code does not fit
     MAX_FIELDS settings fields - same order as routecode.py */
  function encode(track, name, plan) {
    var r;
    for (var i = 0; i < TOLS.length; i++) {
      r = encodeTol(track, name, plan, TOLS[i]);
      if (r.code.length <= PART * MAX_FIELDS) break;
    }
    return r;
  }
  function encodeTol(track, name, plan, tol) {
    var lat0 = rnd(track[0][0] * 1e6) / 1e6, lon0 = rnd(track[0][1] * 1e6) / 1e6;
    var k = Math.cos(lat0 * Math.PI / 180);
    var xy = track.map(function (p) { return [(p[1] - lon0) * R_LAT * k, (p[0] - lat0) * R_LAT]; });
    var keep = dp(xy.length, function (q, i, j) { return segD(xy[q], xy[i], xy[j]); }, tol);
    var simp = keep.map(function (i) { return xy[i]; });
    var unit = UNIT_DM / 10.0, maxseg = 127 * unit * 0.95;
    var verts = [simp[0]], vi = [0];   // vi: index in verts of each kept vertex
    for (var s = 0; s + 1 < simp.length; s++) {
      var a = simp[s], b = simp[s + 1];
      var n = Math.max(1, Math.ceil(hyp(b[0] - a[0], b[1] - a[1]) / maxseg));
      for (var i = 1; i <= n; i++) verts.push([a[0] + (b[0] - a[0]) * i / n, a[1] + (b[1] - a[1]) * i / n]);
      vi.push(verts.length - 1);
    }
    var body = [], qx = 0, qy = 0, line = [[0, 0]];
    for (var v = 1; v < verts.length; v++) {
      var dx = clamp(rnd(verts[v][0] / unit - qx), -127, 127);
      var dy = clamp(rnd(verts[v][1] / unit - qy), -127, 127);
      body.push(dy & 0xFF, dx & 0xFF);
      qx += dx; qy += dy;
      line.push([qx, qy]);
    }
    var dl = line.map(function (p) { return [p[0] * unit, p[1] * unit]; });
    var total = 0.0, cum = [0.0];
    for (var c = 1; c < dl.length; c++) { total += hyp(dl[c][0] - dl[c - 1][0], dl[c][1] - dl[c - 1][1]); cum.push(total); }
    var hasEle = track.some(function (p) { return p[2] !== null && p[2] !== undefined; });
    var srcCum = [0.0];
    for (var sc = 1; sc < xy.length; sc++) srcCum.push(srcCum[sc - 1] + hyp(xy[sc][0] - xy[sc - 1][0], xy[sc][1] - xy[sc - 1][1]));
    var srcTotal = srcCum[srcCum.length - 1] || 1.0;
    // decoded metres <-> GPX metres piecewise between the kept vertices, not
    // by one ratio (routecode.py: CPs passed 40-70 m late on a twisty descent)
    var aDec = vi.map(function (i) { return cum[i]; }), aSrc = keep.map(function (i) { return srcCum[i]; });
    var srcEle = track.map(function (p) { return (p[2] === undefined ? null : p[2]); });
    if (hasEle) {
      var last = null;
      for (var f0 = 0; f0 < srcEle.length; f0++) if (srcEle[f0] !== null) { last = srcEle[f0]; break; }
      for (var e = 0; e < srcEle.length; e++) { if (srcEle[e] === null) srcEle[e] = last; else last = srcEle[e]; }
    }
    var nprof = Math.floor(total / PSTEP) + 1, xs = [], zs = [], jj = 0;
    for (var pi = 0; pi < nprof; pi++) xs.push(pi * PSTEP);
    xs.forEach(function (x) {
      var f = remap(x, aDec, aSrc);
      while (jj < srcCum.length - 2 && srcCum[jj + 1] < f) jj++;
      if (!hasEle) { zs.push(0.0); return; }
      var A = srcCum[jj], B = srcCum[Math.min(jj + 1, srcCum.length - 1)];
      var t = B <= A ? 0.0 : clamp((f - A) / (B - A), 0.0, 1.0);
      zs.push(srcEle[jj] + (srcEle[Math.min(jj + 1, srcEle.length - 1)] - srcEle[jj]) * t);
    });
    zs = smooth(zs, 2);
    var ks = xs.length > 1 ? dp(xs.length, function (q, i, j) {
      var z = zs[i] + (zs[j] - zs[i]) * (xs[q] - xs[i]) / (xs[j] - xs[i]);
      return Math.abs(zs[q] - z);
    }, ZTOL_M) : [0];
    var prof = [], m = 1, pz = clamp(rnd(zs[0]), 0, 65535), climb = 0;
    for (var kk = 0; kk + 1 < ks.length; kk++) {
      var steps = ks[kk + 1] - ks[kk], dz = rnd(zs[ks[kk + 1]]) - pz;
      var nn = Math.max(1, Math.ceil(steps / 255), Math.ceil(Math.abs(dz) / 127)), ds = 0, dzs = 0;
      for (var q = 1; q <= nn; q++) {
        var st = rnd(steps * q / nn) - ds, zz = rnd(dz * q / nn) - dzs;
        prof.push(st, zz & 0xFF);
        ds += st; dzs += zz; m++;
        if (zz > 0) climb += zz;
      }
      pz += dz;
    }
    var nb = utf8(name || '');
    /* version 2 only when a cutoff is relative - older watches reject it
       instead of misreading bit 15 as a time of day */
    var head = [0x54, hasRel(plan) ? 2 : 1];
    var il = rnd(lat0 * 1e6), io = rnd(lon0 * 1e6), z0 = clamp(rnd(zs[0]), 0, 65535);
    [il, io].forEach(function (x) { head.push((x >>> 24) & 255, (x >>> 16) & 255, (x >>> 8) & 255, x & 255); });
    head.push(UNIT_DM, (line.length >> 8) & 255, line.length & 255, (m >> 8) & 255, m & 255, (z0 >> 8) & 255, z0 & 255, nb.length);
    var bytes = head.concat(nb, body, prof, planBytes(planOnLine(plan || [], aDec, aSrc, total, srcTotal)));
    var crc = crc16(bytes, bytes.length);
    bytes.push((crc >> 8) & 255, crc & 255);
    return {code: 'TR1' + b64(bytes), vertices: line.length, profile: m, lengthM: total, climbM: climb, tolM: tol};
  }
  function split(code) { var out = []; for (var i = 0; i < code.length; i += PART) out.push(code.slice(i, i + PART)); return out; }

  function unb64(s) {
    if (typeof Buffer !== 'undefined') return Array.prototype.slice.call(Buffer.from(s, 'base64'));
    var bin = atob(s), out = [];
    for (var i = 0; i < bin.length; i++) out.push(bin.charCodeAt(i));
    return out;
  }
  function unutf8(bytes) {
    try { return decodeURIComponent(bytes.map(function (b) { return '%' + (b < 16 ? '0' : '') + b.toString(16); }).join('')); }
    catch (x) { return ''; }
  }

  /* TR1 text (all fields concatenated, whitespace ignored) -> {name, lat0,
     lon0, unit, xy [[x, y] units], prof [[dist m, alt m]], plan [{km, name,
     cut, srv, dp}]} or null when the text is incomplete or damaged (CRC) -
     routecode.py decode(). */
  function decode(text) {
    var s = String(text || '').replace(/\s+/g, '');
    if (s.slice(0, 3) !== 'TR1') return null;
    var b;
    try { b = unb64(s.slice(3)); } catch (x) { return null; }
    var len = b.length;
    if (len < 22 || b[0] !== 0x54 || b[1] < 1 || b[1] > 2) return null;
    if (crc16(b, len - 2) !== ((b[len - 2] << 8) | b[len - 1])) return null;
    function u16(o) { return (b[o] << 8) | b[o + 1]; }
    function s8(v) { return v > 127 ? v - 256 : v; }
    var ilat = (b[2] << 24) | (b[3] << 16) | (b[4] << 8) | b[5];
    var ilon = (b[6] << 24) | (b[7] << 16) | (b[8] << 8) | b[9];
    var unit = b[10] / 10, n = u16(11), m = u16(13), z0 = u16(15), ln = b[17];
    var o = 18 + ln;
    if (n < 2 || m < 1 || len < o + 2 * (n - 1) + 2 * (m - 1) + 3) return null;
    var name = unutf8(b.slice(18, o));
    var x = 0, y = 0, xy = [[0, 0]];
    for (var i = 1; i < n; i++) { y += s8(b[o]); x += s8(b[o + 1]); o += 2; xy.push([x, y]); }
    var prof = [[0, z0]];
    for (var j = 1; j < m; j++) {
      var p = prof[j - 1];
      prof.push([p[0] + b[o] * PSTEP, p[1] + s8(b[o + 1])]);
      o += 2;
    }
    var k = b[o++], plan = [];
    for (var c = 0; c < k; c++) {
      if (o + 9 > len - 2) return null;
      var cut = u16(o + 2), mask = u16(o + 4), dpv = u16(o + 6), l2 = b[o + 8], srv = '';
      for (var q = 0; q < SERVICES.length; q++) if (mask & (1 << q)) srv += SERVICES[q];
      var rel = (cut !== 0xFFFF && (cut & 0x8000)) ? cut & 0x7FFF : -1;
      plan.push({km: u16(o) / 100, cut: (cut === 0xFFFF || rel >= 0) ? -1 : cut, rel: rel, srv: srv, dp: dpv === 0xFFFF ? -1 : dpv,
                 name: unutf8(b.slice(o + 9, o + 9 + l2))});
      o += 9 + l2;
    }
    if (o !== len - 2) return null;
    return {name: name, lat0: (ilat | 0) / 1e6, lon0: (ilon | 0) / 1e6, unit: unit, xy: xy, prof: prof, plan: plan};
  }

  /* Decoded code -> track [[lat, lon, ele]] along the decoded line, the
     altitude interpolated from the profile (which is measured along it). */
  function toTrack(d) {
    var k = Math.cos(d.lat0 * Math.PI / 180), cum = 0, j = 0, out = [];
    for (var i = 0; i < d.xy.length; i++) {
      if (i > 0) cum += hyp(d.xy[i][0] - d.xy[i - 1][0], d.xy[i][1] - d.xy[i - 1][1]) * d.unit;
      while (j < d.prof.length - 2 && d.prof[j + 1][0] < cum) j++;
      var a = d.prof[j], b = d.prof[Math.min(j + 1, d.prof.length - 1)];
      var z = b[0] > a[0] ? a[1] + (b[1] - a[1]) * clamp((cum - a[0]) / (b[0] - a[0]), 0, 1) : a[1];
      out.push([d.lat0 + d.xy[i][1] * d.unit / R_LAT, d.lon0 + d.xy[i][0] * d.unit / (R_LAT * k), z]);
    }
    return out;
  }


  /* Adaptive TR1 encoder: shareable drop-in addition to routecode.js.
     The original decode(), planBytes(), remap(), utf8(), b64() and crc16()
     functions remain unchanged. */
  function trHeapPush(h, v) {
    var i = h.length; h.push(v);
    while (i) {
      var p = (i - 1) >>> 1;
      if (h[p][0] <= v[0]) break;
      h[i] = h[p]; i = p;
    }
    h[i] = v;
  }
  function trHeapPop(h) {
    var top = h[0], last = h.pop();
    if (h.length) {
      var i = 0;
      while (true) {
        var c = i * 2 + 1;
        if (c >= h.length) break;
        if (c + 1 < h.length && h[c + 1][0] < h[c][0]) c++;
        if (last[0] <= h[c][0]) break;
        h[i] = h[c]; i = c;
      }
      h[i] = last;
    }
    return top;
  }
  function trCumulative(points) {
    var out = [0];
    for (var i = 1; i < points.length; i++)
      out.push(out[i - 1] + hyp(points[i][0] - points[i - 1][0], points[i][1] - points[i - 1][1]));
    return out;
  }
  function trNearest(a, x) {
    var lo = 0, hi = a.length - 1;
    while (lo < hi) {
      var mid = (lo + hi) >>> 1;
      if (a[mid] < x) lo = mid + 1; else hi = mid;
    }
    return lo > 0 && Math.abs(a[lo - 1] - x) < Math.abs(a[lo] - x) ? lo - 1 : lo;
  }

  // Remove points losing the least path length. Explicit checkpoint indices
  // are pinned and are never removed.
  function trGeometryKeep(xy, desired, pins) {
    var n = xy.length, prev = [], next = [], live = [], gen = [], h = [];
    for (var i = 0; i < n; i++) { prev[i] = i - 1; next[i] = i + 1; live[i] = true; gen[i] = 0; }
    next[n - 1] = -1;
    function cost(i) {
      if (pins[i] || prev[i] < 0 || next[i] < 0) return 1e30;
      var a = xy[prev[i]], b = xy[i], c = xy[next[i]];
      return hyp(b[0]-a[0], b[1]-a[1]) + hyp(c[0]-b[0], c[1]-b[1]) -
             hyp(c[0]-a[0], c[1]-a[1]) + 0.0000005 * segD(b,a,c);
    }
    for (var q = 1; q < n - 1; q++) if (!pins[q]) trHeapPush(h,[cost(q),q,0]);
    var alive = n;
    while (alive > Math.max(desired, Object.keys(pins).length) && h.length) {
      var x = trHeapPop(h), j = x[1];
      if (!live[j] || gen[j] !== x[2]) continue;
      if (x[0] >= 1e29) break;
      var l = prev[j], r = next[j]; live[j] = false; next[l] = r; prev[r] = l; alive--;
      [l,r].forEach(function (z) {
        if (z > 0 && z < n - 1 && !pins[z]) {
          gen[z]++; trHeapPush(h,[cost(z),z,gen[z]]);
        }
      });
    }
    var keep = [];
    for (var t = 0; t < n; t++) if (live[t]) keep.push(t);
    return keep;
  }

  // Dynamic programming on nearby 4m grid positions. A single lambda rewards
  // distance while penalizing squared displacement. Binary search lambda
  // makes the decoded length match the unsimplified path, not its chord sum.
  function trQuantize(xy, target, unit) {
    var opts = [], maxDeviation = Math.min(3.25,unit*0.82);
    for (var i = 0; i < xy.length; i++) {
      var a = xy[i], x = Math.floor(a[0] / unit), y = Math.floor(a[1] / unit), options = [];
      for (var xx = x - 1; xx <= x + 2; xx++)
        for (var yy = y - 1; yy <= y + 2; yy++) {
          var sq = Math.pow(xx*unit-a[0],2) + Math.pow(yy*unit-a[1],2);
          if (sq <= maxDeviation*maxDeviation) options.push([xx,yy,sq]);
        }
      if (!options.length) options.push([rnd(a[0]/unit),rnd(a[1]/unit),0]);
      if (i === 0 || i === xy.length - 1) {
        options.sort(function (u,v) { return u[2] - v[2]; });
        options = [options[0]];
      }
      opts.push(options);
    }
    var edges = [null];
    for (var z = 1; z < opts.length; z++) {
      var e = [];
      for (var u = 0; u < opts[z-1].length; u++) {
        var row = [];
        for (var v = 0; v < opts[z].length; v++)
          row.push(hyp(opts[z-1][u][0]-opts[z][v][0],opts[z-1][u][1]-opts[z][v][1]) * unit);
        e.push(row);
      }
      edges.push(e);
    }
    function solve(lambda) {
      var old = opts[0].map(function (a) { return -a[2]; });
      var back = [null];
      for (var i = 1; i < opts.length; i++) {
        var scores = [], ids = [];
        for (var v = 0; v < opts[i].length; v++) {
          var best = -Infinity, bestId = 0;
          for (var u = 0; u < opts[i-1].length; u++) {
            var score = old[u] + lambda * edges[i][u][v];
            if (score > best) { best = score; bestId = u; }
          }
          scores.push(best - opts[i][v][2]); ids.push(bestId);
        }
        old = scores; back.push(ids);
      }
      var idx = 0;
      for (var x = 1; x < old.length; x++) if (old[x] > old[idx]) idx = x;
      var selected = new Array(opts.length);
      for (var j = opts.length - 1; j >= 0; j--) {
        selected[j] = opts[j][idx];
        if (j) idx = back[j][idx];
      }
      var grid = selected.map(function (a) { return [a[0],a[1]]; });
      var length = trCumulative(grid).slice(-1)[0] * unit;
      var maxDrift = 0;
      for (var t = 0; t < grid.length; t++)
        maxDrift = Math.max(maxDrift,hyp(grid[t][0]*unit-xy[t][0],grid[t][1]*unit-xy[t][1]));
      return {grid:grid,length:length,maxDrift:maxDrift};
    }
    var low = 0, best = solve(0), high = 0.5, greater = solve(high);
    if (best.length >= target) return best;
    while (greater.length < target && high < 32) {
      high *= 2; greater = solve(high);
    }
    function consider(a) { if (Math.abs(a.length-target) < Math.abs(best.length-target)) best = a; }
    consider(greater);
    for (var it = 0; it < 17; it++) {
      var mid = (low + high) / 2, candidate = solve(mid);
      consider(candidate);
      if (candidate.length < target) low = mid; else high = mid;
    }
    consider(solve(low)); consider(solve(high));
    return best;
  }

  // Preserve total ascent when reducing the 25m profile. A point is removed
  // first if its removal loses the least D+, with vertical interpolation error
  // used to break ties between monotonic sections.
  function trProfileKeep(z, targetCount) {
    var n=z.length, prev=[],next=[],live=[],gen=[],heap=[];
    for(var i=0;i<n;i++){prev[i]=i-1;next[i]=i+1;live[i]=true;gen[i]=0;}
    next[n-1]=-1;
    function cost(i) {
      var l=prev[i],r=next[i];
      if(l<0||r<0)return 1e30;
      var loss=Math.max(0,z[i]-z[l])+Math.max(0,z[r]-z[i])-Math.max(0,z[r]-z[l]);
      var interpolation=z[l]+(z[r]-z[l])*(i-l)/(r-l);
      return loss*1000 + Math.min(300,Math.abs(z[i]-interpolation))*0.001;
    }
    for(var q=1;q<n-1;q++)trHeapPush(heap,[cost(q),q,0]);
    var count=n;
    while(count>Math.max(2,targetCount)&&heap.length) {
      var top=trHeapPop(heap),j=top[1];
      if(!live[j]||top[2]!==gen[j])continue;
      var l=prev[j],r=next[j];live[j]=false;next[l]=r;prev[r]=l;count--;
      [l,r].forEach(function(x){
        if(x>0&&x<n-1){gen[x]++;trHeapPush(heap,[cost(x),x,gen[x]]);}
      });
    }
    var out=[];
    for(var k=0;k<n;k++)if(live[k])out.push(k);
    return out;
  }

  function trAdaptiveCandidate(track, xy, sc, name, plan, pins, nXY, nProfile) {
    var keep=trGeometryKeep(xy,nXY,pins);
    var original=keep.map(function(i){return xy[i];});
    var quant=trQuantize(original,sc[sc.length-1],UNIT_DM/10);
    var grid=quant.grid,unit=UNIT_DM/10;
    var line=[grid[0]], anchors=[0], body=[], sum=0, aDec=[0];
    for(var i=1;i<grid.length;i++) {
      var from=grid[i-1],to=grid[i];
      var dx=to[0]-from[0],dy=to[1]-from[1];
      var n=Math.max(1,Math.ceil(Math.max(Math.abs(dx),Math.abs(dy))/127));
      var x=from[0],y=from[1];
      for(var j=1;j<=n;j++){
        var nx=from[0]+rnd(dx*j/n),ny=from[1]+rnd(dy*j/n);
        body.push((ny-y)&255,(nx-x)&255);
        sum+=hyp(nx-x,ny-y)*unit;
        x=nx;y=ny;line.push([x,y]);
      }
      aDec.push(sum);
      anchors.push(line.length-1);
    }
    var aSrc=keep.map(function(i){return sc[i];});
    // Profile samples, continuously mapped back to source-kilometres.
    var nprof=Math.floor(sum/PSTEP)+1,z=[],xToSource=0,sourceIndex=0,remapIndex=0;
    var zSrc=track.map(function(p){return p[2]===null||p[2]===undefined?null:Number(p[2]);});
    var known=zSrc.find(function(x){return x!==null&&isFinite(x);});
    if (known===undefined) known=0;
    for (var a=0;a<zSrc.length;a++){
      if(zSrc[a]===null||!isFinite(zSrc[a]))zSrc[a]=known;
      else known=zSrc[a];
    }
    for(var step=0;step<nprof;step++) {
      var x=step*PSTEP;
      while(remapIndex<aDec.length-2&&aDec[remapIndex+1]<x)remapIndex++;
      var a0=aDec[remapIndex],b0=aDec[remapIndex+1];
      xToSource=aSrc[remapIndex]+(b0<=a0?0:(aSrc[remapIndex+1]-aSrc[remapIndex])*(x-a0)/(b0-a0));
      while(sourceIndex<sc.length-2&&sc[sourceIndex+1]<xToSource)sourceIndex++;
      var a1=sc[sourceIndex],b1=sc[sourceIndex+1];
      var weight=b1<=a1?0:clamp((xToSource-a1)/(b1-a1),0,1);
      z.push(clamp(rnd(zSrc[sourceIndex]+(zSrc[sourceIndex+1]-zSrc[sourceIndex])*weight),0,65535));
    }
    var pKeep=trProfileKeep(z,Math.min(nProfile,z.length)),profile=[],climb=0,descent=0,lastZ=z[0];
    for(var g=1;g<pKeep.length;g++){
      var steps=pKeep[g]-pKeep[g-1],dz=z[pKeep[g]]-lastZ;
      var pieces=Math.max(1,Math.ceil(steps/255),Math.ceil(Math.abs(dz)/127));
      var usedS=0,usedZ=0;
      for(var sub=1;sub<=pieces;sub++){
        var ds=rnd(steps*sub/pieces)-usedS;
        var dd=rnd(dz*sub/pieces)-usedZ;
        profile.push(ds,dd&255);
        usedS+=ds;usedZ+=dd;
        if(dd>0)climb+=dd;else descent-=dd;
      }
      lastZ+=dz;
    }
    var planData=planOnLine(plan||[],aDec,aSrc,sum,sc[sc.length-1]);
    var checkpoint=planBytes(planData),nameBytes=utf8(name||''),bytes=[0x54,hasRel(plan)?2:1];
    var la=rnd(track[0][0]*1e6),lo=rnd(track[0][1]*1e6);
    [la,lo].forEach(function(v){bytes.push((v>>>24)&255,(v>>>16)&255,(v>>>8)&255,v&255);});
    bytes.push(UNIT_DM,(line.length>>8)&255,line.length&255,
               ((profile.length/2+1)>>8)&255,(profile.length/2+1)&255,
               (z[0]>>8)&255,z[0]&255,nameBytes.length);
    bytes=bytes.concat(nameBytes,body,profile,checkpoint);
    var crc=crc16(bytes,bytes.length);
    bytes.push((crc>>8)&255,crc&255);
    var code='TR1'+b64(bytes);
    var ascentBase=0;
    for(var e=1;e<z.length;e++)ascentBase+=Math.max(0,z[e]-z[e-1]);
    var drift=0;
    for(var h=0;h<aDec.length;h++)drift=Math.max(drift,Math.abs(aDec[h]-aSrc[h]));
    // Compute cross-track error at every source point, on the snapped
    // chord joining its two surrounding surviving vertices.
    var errors=[],cur=0,meanDrift=0;
    for(var p=0;p<xy.length;p++){
      while(cur<keep.length-2&&keep[cur+1]<p)cur++;
      var A=[grid[cur][0]*unit,grid[cur][1]*unit],
          B=[grid[cur+1][0]*unit,grid[cur+1][1]*unit];
      errors.push(segD(xy[p],A,B));
    }
    errors.sort(function(a,b){return a-b;});
    return {code:code,vertices:line.length,profile:profile.length/2+1,
            lengthM:sum,climbM:climb,descentM:descent,ascentBase:ascentBase,
            characters:code.length,geom95:errors[Math.floor(errors.length*.95)],
            geomMax:errors[errors.length-1],cumDrift:drift,
            quantMaxDrift:quant.maxDrift,sourceDistanceM:sc[sc.length-1],
            checkpointCount:planData.length,keepCount:keep.length};
  }

  function encodeAdaptive(track,name,plan,options) {
    options=options||{};
    if(!Array.isArray(track)||track.length<2)throw new Error('TR1 needs at least two route points');
    if((plan||[]).length>255)throw new Error('TR1 allows at most 255 checkpoints');
    // Small courses cannot meet the fixed adaptive candidate minimum.
    // Fall back to the reference encoder and still enforce the output limit.
    if(track.length<16){
      var shortResult=encode(track,name,plan);
      if(shortResult.code.length>(options.maxCharacters||PART*MAX_FIELDS) || !decode(shortResult.code))
        throw new Error('TR1 short course does not fit the available fields');
      shortResult.parts=split(shortResult.code);
      shortResult.candidatesChecked=0;
      return shortResult;
    }
    var lat0=rnd(track[0][0]*1e6)/1e6,lon0=rnd(track[0][1]*1e6)/1e6,
        cf=Math.cos(lat0*Math.PI/180),xy=track.map(function(p){
          return [(p[1]-lon0)*R_LAT*cf,(p[0]-lat0)*R_LAT];
        }),sc=trCumulative(xy),pins={0:true};
    pins[track.length-1]=true;
    var planned=plan||[],finish=planned.length?planned[planned.length-1].km:0;
    planned.forEach(function(cp){
      if (isFinite(cp.km)&&cp.km>=0&&finish>0)
        pins[trNearest(sc,cp.km/finish*sc[sc.length-1])]=true;
    });

    var maxCode=options.maxCharacters||PART*MAX_FIELDS;
    var cpLen=planBytes(planned).length;
    var overhead=18+utf8(name||'').length+cpLen+2;
    var profiles=options.profileCandidates||[280,320,360,390,420,450,480,520,580];
    // Gentle ascent-preservation tie-breaker between geometry candidates:
    // 25m resampling itself already removes noise, so keep this weight small.
    var rawAscent=0;
    for (var z=1;z<track.length;z++) {
      var now=Number(track[z][2]), before=Number(track[z-1][2]);
      if(track[z][2]!=null && track[z-1][2]!=null &&
         isFinite(now)&&isFinite(before)) rawAscent+=Math.max(0,now-before);
    }
    var best=null, attempts=[];
    for (var a=0;a<profiles.length;a++){
      var p=profiles[a];
      // Leave room for occasional extra deltas required by ±127 FIT-like
      // signed-byte limits in both horizontal and vertical streams.
      var geoMax=Math.floor((Math.floor((maxCode-3)/4)*3-overhead-2*(p-1))/2)+1;
      for (var slack of [20,40]){
        var desired=Math.min(track.length,geoMax-slack);
        if(desired<16)continue;
        var r=trAdaptiveCandidate(track,xy,sc,name,planned,pins,desired,p);
        if(r.characters>maxCode)continue;
        var decoded=decode(r.code);
        if(!decoded)continue; // includes strict CRC validation
        if(decoded.xy.length!==r.vertices||decoded.prof.length!==r.profile)continue;
        var ascentLoss=Math.max(0,r.ascentBase-r.climbM);
        // Physical fidelity is more important than just filling the text fields.
        var score=1.0*r.geom95+0.1*r.geomMax+
                  0.055*r.cumDrift+0.12*Math.abs(r.lengthM-r.sourceDistanceM)+
                  0.40*ascentLoss + 0.03*Math.max(0,rawAscent-r.climbM);
        r.score=score;
        attempts.push({characters:r.characters,vertices:r.vertices,profile:r.profile,
                       lengthM:r.lengthM,climbM:r.climbM,geom95:r.geom95,score:score});
        if(!best||score<best.score)best=r;
      }
    }
    if(!best){
      var fallback=encode(track,name,plan);
      if(fallback.code.length>maxCode || !decode(fallback.code))
        throw new Error('TR1 adaptive/reference cannot fit this track in '+maxCode+' characters');
      fallback.parts=split(fallback.code);
      fallback.candidatesChecked=attempts.length;
      return fallback;
    }
    best.parts=split(best.code);
    best.candidatesChecked=attempts.length;
    return best;
  }

  var API = {encode: encode, encodeAdaptive: encodeAdaptive, split: split, decode: decode, toTrack: toTrack, PART: PART};
  if (typeof module !== 'undefined' && module.exports) module.exports = API; else G.RouteCode = API;
})(typeof window !== 'undefined' ? window : this);