/*
 * Portable atob/btoa for bare ECMAScript runtimes (not provided by GraalJS).
 * Binary-string semantics match the browser APIs required by RouteCode.
 */
(function (root) {
  'use strict';
  var alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';

  if (typeof root.btoa !== 'function') {
    root.btoa = function (input) {
      var src = String(input), out = '';
      for (var i = 0; i < src.length; i += 3) {
        var a = src.charCodeAt(i);
        var b = i + 1 < src.length ? src.charCodeAt(i + 1) : 0;
        var c = i + 2 < src.length ? src.charCodeAt(i + 2) : 0;
        if (a > 255 || b > 255 || c > 255) throw new TypeError('btoa expects binary bytes');
        out += alphabet[a >>> 2];
        out += alphabet[((a & 3) << 4) | (b >>> 4)];
        out += i + 1 < src.length ? alphabet[((b & 15) << 2) | (c >>> 6)] : '=';
        out += i + 2 < src.length ? alphabet[c & 63] : '=';
      }
      return out;
    };
  }

  if (typeof root.atob !== 'function') {
    root.atob = function (input) {
      var src = String(input).replace(/\s+/g, ''), out = '';
      if (src.length % 4 !== 0) throw new TypeError('Invalid Base64 length');
      for (var i = 0; i < src.length; i += 4) {
        var a = alphabet.indexOf(src[i]), b = alphabet.indexOf(src[i + 1]);
        var c = src[i + 2] === '=' ? -1 : alphabet.indexOf(src[i + 2]);
        var d = src[i + 3] === '=' ? -1 : alphabet.indexOf(src[i + 3]);
        if (a < 0 || b < 0 || c < -1 || d < -1 || (c < 0 && d >= 0))
          throw new TypeError('Invalid Base64 data');
        var x = (a << 18) | (b << 12) | ((c < 0 ? 0 : c) << 6) | (d < 0 ? 0 : d);
        out += String.fromCharCode((x >>> 16) & 255);
        if (c >= 0) out += String.fromCharCode((x >>> 8) & 255);
        if (d >= 0) out += String.fromCharCode(x & 255);
      }
      return out;
    };
  }
})(globalThis);
