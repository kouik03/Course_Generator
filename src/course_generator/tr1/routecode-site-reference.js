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

  var API = {encode: encode, split: split, decode: decode, toTrack: toTrack, PART: PART};
  if (typeof module !== 'undefined' && module.exports) module.exports = API; else G.RouteCode = API;
})(typeof window !== 'undefined' ? window : this);