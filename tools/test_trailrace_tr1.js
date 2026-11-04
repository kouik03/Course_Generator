/* Run: node tools/test_trailrace_tr1.js [optional-gpx-file] */
'use strict';
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const adaptive = require('../src/course_generator/tr1/routecode-adaptive.js');
const standard = require('../src/course_generator/tr1/routecode-site-reference.js');

const short = [[43.1, 4.1, 20], [43.1002, 4.1003, 30], [43.1003, 4.1004, 25]];
const plan = [{km: 0.04, name: 'Finish', rel: 4, cut: -1, srv: 'WF', dp: 10}];
for (const [label, codec, method] of [
  ['reference', standard, 'encode'],
  ['adaptive-short', adaptive, 'encodeAdaptive']
]) {
  const result = codec[method](short, 'TrailRace test', plan);
  const decoded = codec.decode(result.code);
  assert.ok(decoded, label + ': invalid CRC or format');
  assert.ok(result.code.length <= 6000, label + ': too long');
  assert.strictEqual(decoded.plan.length, 1);
  assert.strictEqual(decoded.plan[0].name, 'Finish');
  assert.strictEqual(decoded.plan[0].srv, 'WF');
  assert.strictEqual(decoded.plan[0].rel, 4);
  console.log(label + ': ' + result.code.length + ' chars, CRC OK');
}

if (process.argv[2]) {
  const xml = fs.readFileSync(process.argv[2], 'utf8');
  const track = [...xml.matchAll(/<trkpt\s+lat="([\d.-]+)"\s+lon="([\d.-]+)"[^>]*>\s*<ele>([\d.-]+)<\/ele>/g)]
    .map(m => [+m[1], +m[2], +m[3]]);
  assert.ok(track.length >= 2, 'No GPX trackpoints found');
  const rad = Math.PI / 180;
  function distance(a, b) {
    const phi1 = a[0] * rad, phi2 = b[0] * rad;
    const dp = (b[0] - a[0]) * rad, dl = (b[1] - a[1]) * rad;
    return 6371000 * 2 * Math.asin(Math.min(1, Math.sqrt(
      Math.sin(dp / 2) ** 2 + Math.cos(phi1) * Math.cos(phi2) * Math.sin(dl / 2) ** 2
    )));
  }
  let sourceDist = 0;
  for (let i = 1; i < track.length; ++i) sourceDist += distance(track[i - 1], track[i]);
  const result = adaptive.encodeAdaptive(track, 'Test route with finish', [
    {km: sourceDist / 1000, name: 'Finish', cut: -1, rel: -1, srv: '', dp: -1}
  ]);
  const decoded = adaptive.decode(result.code);
  assert.ok(decoded, 'Adaptive GPX format/CRC invalid');
  assert.ok(result.code.length <= 6000, 'Adaptive GPX exceeds three 2000-character blocks');
  assert.strictEqual(decoded.plan.length, 1);
  console.log(JSON.stringify({
    inputPoints: track.length,
    inputKm: sourceDist / 1000,
    decodedKm: result.lengthM / 1000,
    profileDplus: result.climbM,
    chars: result.code.length,
    fields: adaptive.split(result.code).map(x => x.length)
  }, null, 2));
}
