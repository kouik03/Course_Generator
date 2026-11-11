using Toybox.Application;
using Toybox.WatchUi;
using Toybox.Graphics;
using Toybox.System;
using Toybox.Activity;
using Toybox.Math;

// Prototype CGIQ v1 data field. Offline only; HTTP deliberately excluded.
// This module expects three Connect IQ string properties: blob1, blob2, blob3.
// The producer CRC and all mandatory checkpoints are validated before activation.
class CourseIqField extends WatchUi.DataField {
    var _course = null;
    var _error = "NO COURSE";
    var _next = 1;
    var _officialElapsed = null;
    var _cutoffOffsets = [];

    function initialize() {
        DataField.initialize();
        reload();
    }

    function reload() {
        try {
            var a = Application.Properties.getValue("blob1");
            var b = Application.Properties.getValue("blob2");
            var c = Application.Properties.getValue("blob3");
            if (a == null || b == null || c == null) {
                _error = "MISSING BLOBS";
                return;
            }
            _course = CgiqDecoder.decode(a + b + c);
            _error = null;
            _cutoffOffsets = [];
            var corrections = Application.Properties.getValue("cutoffAdjustments");
            if (corrections != null && corrections.length() > 0) {
                var values = corrections.split(",");
                for (var j = 0; j < values.size(); j++) {
                    var v = values[j].toNumber();
                    _cutoffOffsets.add(v == null ? 0 : v * 60);
                }
            }
            var selected = Application.Properties.getValue("nextPoint");
            if (selected != null && selected instanceof Number && selected >= 1 &&
                selected < _course.stops.size()) {
                _next = selected;
            }
        } catch (e) {
            _course = null;
            _error = "INVALID CGIQ";
            System.println("CGIQ load failed: " + e);
        }
    }

    function compute(info) {
        // Activity elapsedTime is in milliseconds. Start delay does not shift
        // official cutoff timestamps: it reduces the runner's available time.
        if (info != null && info.elapsedTime != null) {
            var delay = Application.Properties.getValue("startDelay");
            if (delay == null) { delay = 0; }
            _officialElapsed = (info.elapsedTime / 1000).toNumber() + delay * 60;
        }
        return null;
    }

    function onSettingsChanged() {
        reload();
    }

    function climbAt(distance) {
        var answer = 0;
        foreach (var v in _course.vertices) {
            if (v[:distance] > distance) { break; }
            answer = v[:climb];
        }
        return answer;
    }

    function descentAt(distance) {
        var answer = 0;
        foreach (var v in _course.vertices) {
            if (v[:distance] > distance) { break; }
            answer = v[:descent];
        }
        return answer;
    }

    function onUpdate(dc) {
        var w = dc.getWidth();
        var h = dc.getHeight();
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.clear();
        if (_course == null) {
            dc.drawText(w / 2, h / 2, Graphics.FONT_SMALL, _error, Graphics.TEXT_JUSTIFY_CENTER);
            return;
        }
        var point = _course.stops[_next];
        var previous = _course.stops[_next - 1];
        var margin = 4;
        var title = _course.name;
        dc.drawText(w / 2, margin, Graphics.FONT_SMALL, title, Graphics.TEXT_JUSTIFY_CENTER);
        var big = h > 180 ? Graphics.FONT_LARGE : Graphics.FONT_MEDIUM;
        dc.drawText(w / 2, h / 4, big, point.name, Graphics.TEXT_JUSTIFY_CENTER);
        var km = (point.distance - previous.distance).toFloat() / 1000.0;
        var gain = climbAt(point.distance) - climbAt(previous.distance);
        var loss = descentAt(point.distance) - descentAt(previous.distance);
        dc.drawText(w / 2, h / 2, big, km.format("%.1f") + " km segment",
                    Graphics.TEXT_JUSTIFY_CENTER);
        dc.drawText(w / 2, (h * 3) / 4, Graphics.FONT_SMALL,
                    "D+ " + gain + "m  D- " + loss + "m", Graphics.TEXT_JUSTIFY_CENTER);
        var status = "POINT";
        var cutoff = point.cutoff;
        if (_next < _cutoffOffsets.size()) { cutoff += _cutoffOffsets[_next]; }
        var overdue = cutoff > 0 && _officialElapsed != null && _officialElapsed > cutoff;
        if (point.kind == 2) {
            status = overdue ? "DEROUTAGE REQUIS" : "LIMITE DEROUTAGE";
        } else if (point.kind == 1) {
            status = overdue ? "HORS DELAI" : "BARRIERE ELIMINATOIRE";
        }
        dc.drawText(w / 2, h - 28, Graphics.FONT_XTINY, status,
                    Graphics.TEXT_JUSTIFY_CENTER);
    }
}

class CgiqDecoder {
    static function decode(text) {
        var data = base64(text);
        if (data.size() < 27) { throw new Lang.Exception("short CGIQ"); }
        var bodyLength = data.size() - 4;
        var expected = u32(data, bodyLength);
        if (crc32(data, bodyLength) != expected) { throw new Lang.Exception("CRC mismatch"); }
        var r = new CgiqReader(data);
        if (r.u8() != 67 || r.u8() != 71 || r.u8() != 73 || r.u8() != 81) {
            throw new Lang.Exception("magic mismatch");
        }
        if (r.u8() != 1) { throw new Lang.Exception("unsupported version"); }
        r.u8(); // flags
        var name = r.utf8(r.u8());
        var vertexCount = r.u16();
        var stopCount = r.u8();
        if (vertexCount < 2 || stopCount < 2) { throw new Lang.Exception("empty course"); }
        var ascent = r.u32();
        var descent = r.u32();
        var total = r.u32();
        var vertices = [];
        for (var i = 0; i < vertexCount; i++) {
            var v = {
                :lat => r.i32(), :lon => r.i32(), :distance => r.u32(),
                :elevation => r.i16(), :climb => r.u32(),
                :descent => r.u32(), :moving => r.u32()
            };
            vertices.add(v);
        }
        var stops = [];
        for (var j = 0; j < stopCount; j++) {
            var distance = r.u32();
            var moving = r.u32();
            var cutoff = r.i32();
            var pause = r.u32();
            var services = r.u8();
            var kind = r.u8();
            var label = r.utf8(r.u8());
            if (kind > 2) { throw new Lang.Exception("bad cutoff kind"); }
            if (j > 0 && distance < stops[j - 1].distance) {
                throw new Lang.Exception("unordered checkpoints");
            }
            stops.add(new CgiqStop(distance, moving, cutoff, pause, services, kind, label));
        }
        if (r.position() != bodyLength) { throw new Lang.Exception("trailing bytes"); }
        return new CgiqCourse(name, vertices, stops, total, ascent, descent);
    }

    static function base64(text) {
        var chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        var output = [];
        var acc = 0;
        var bits = 0;
        for (var i = 0; i < text.length(); i++) {
            var ch = text.substring(i, i + 1);
            if (ch == "=") { break; }
            var value = chars.find(ch);
            if (value == null) { throw new Lang.Exception("invalid base64"); }
            acc = (acc << 6) | value;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                output.add((acc >> bits) & 255);
                acc = acc & ((1 << bits) - 1);
            }
        }
        return output;
    }

    static function u32(data, pos) {
        return (((data[pos] << 24) | (data[pos + 1] << 16)) |
                (data[pos + 2] << 8) | data[pos + 3]) & 0xffffffff;
    }

    static function crc32(data, length) {
        var crc = 0xffffffff;
        for (var i = 0; i < length; i++) {
            crc = crc ^ data[i];
            for (var j = 0; j < 8; j++) {
                crc = (crc & 1) != 0 ? ((crc >> 1) ^ 0xedb88320) : (crc >> 1);
            }
        }
        return (crc ^ 0xffffffff) & 0xffffffff;
    }
}

class CgiqReader {
    var _data;
    var _at = 0;
    function initialize(data) { _data = data; }
    function position() { return _at; }
    function u8() {
        if (_at >= _data.size()) { throw new Lang.Exception("truncated CGIQ"); }
        return _data[_at++];
    }
    function u16() { return (u8() << 8) | u8(); }
    function u32() { return ((u16() << 16) | u16()) & 0xffffffff; }
    function i16() {
        var v = u16();
        return v >= 32768 ? v - 65536 : v;
    }
    function i32() {
        var v = u32();
        return v >= 2147483648 ? v - 4294967296 : v;
    }
    function utf8(count) {
        var text = "";
        for (var i = 0; i < count; i++) { text += u8().toChar(); }
        return text;
    }
}

class CgiqStop {
    var distance; var moving; var cutoff; var pause;
    var services; var kind; var name;
    function initialize(d, m, c, p, s, k, n) {
        distance = d; moving = m; cutoff = c; pause = p;
        services = s; kind = k; name = n;
    }
}

class CgiqCourse {
    var name; var vertices; var stops; var distance; var ascent; var descent;
    function initialize(n, v, s, d, a, de) {
        name = n; vertices = v; stops = s;
        distance = d; ascent = a; descent = de;
    }
}
