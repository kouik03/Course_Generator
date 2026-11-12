import Toybox.Lang;

// CGIQ v1. Offline roadbook: compact binary, Base64 in three application settings.
// All clocks are official local minutes on race day; values >=1440 mean following day.
class CGIQPoint {
    var name = "";
    var distanceM = 0.0;
    var ascentM = 0;
    var descentM = 0;
    var targetMin = 0;
    var pauseMin = 0;
    var cutoffMin = 65535;
    var flags = 1;
    var latitude10000 = 0;
    var longitude10000 = 0;
}

class CGIQBytes {
    var bytes = [];
    var pos = 0;
    var error = "";
    var errorPos = 0;
    var errorCode = -1;

    function initialize() {}

    function decode(source) as Boolean {
        bytes = [];
        pos = 0;
        error = "";
        errorPos = 0;
        errorCode = -1;
        var alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        var buffer = 0;
        var bitCount = 0;
        var padded = false;
        for (var i = 0; i < source.length(); i++) {
            var ch = source.substring(i, i + 1);
            if (ch == " " || ch == "\n" || ch == "\r" || ch == "\t") {
                continue;
            }
            if (ch == "=") {
                padded = true;
                continue;
            }
            if (padded) {
                error = "BASE64 PADDING";
                errorPos = i + 1;
                errorCode = ch.toCharArray()[0].toNumber();
                return false;
            }
            // Also accept URL-safe Base64, which avoids '+' and '/' in
            // mobile settings editors. Existing standard Base64 remains valid.
            var n = alphabet.find(ch);
            if (ch == "-") { n = 62; }
            if (ch == "_") { n = 63; }
            if (n == null) {
                error = "BASE64 CARACTERE";
                errorPos = i + 1;
                errorCode = ch.toCharArray()[0].toNumber();
                return false;
            }
            buffer = buffer * 64 + n;
            bitCount += 6;
            if (bitCount >= 8) {
                bitCount -= 8;
                var divisor = 1 << bitCount;
                bytes.add((buffer / divisor).toNumber());
                buffer = buffer % divisor;
                if (bytes.size() > 4096) {
                    error = "PLAN TROP LONG";
                    return false;
                }
            }
        }
        if (bytes.size() < 10) {
            error = "PLAN TROP COURT";
            return false;
        }
        var sum = 0;
        for (var j = 0; j < bytes.size(); j++) {
            sum = (sum + bytes[j]) % 256;
        }
        if (sum != 0) {
            error = "CONTROLE INVALIDE";
            return false;
        }
        return true;
    }

    function remaining() {
        return bytes.size() - pos;
    }

    function u8() {
        if (remaining() < 1) {
            return -1;
        }
        var n = bytes[pos];
        pos++;
        return n;
    }

    function u16() {
        var lo = u8();
        var hi = u8();
        if (lo < 0 || hi < 0) {
            return -1;
        }
        return lo + hi * 256;
    }

    function i24() {
        var a = u8();
        var b = u8();
        var c = u8();
        if (a < 0 || b < 0 || c < 0) {
            return 0;
        }
        var v = a + b * 256 + c * 65536;
        return v >= 8388608 ? v - 16777216 : v;
    }

    function ascii(count) {
        var result = "";
        for (var i = 0; i < count; i++) {
            var c = u8();
            if (c < 32 || c > 126) {
                return "";
            }
            // UTF-8 is intentionally not used on the watch for bounded memory and width.
            result += c.toChar().toString();
        }
        return result;
    }

    function signature() {
        var value = 17;
        for (var i = 0; i < bytes.size(); i++) {
            value = (value * 31 + bytes[i]) % 50000000;
        }
        return value;
    }
}

class CGIQPlan {
    var points = [];
    var officialStartMin = 450;
    var signature = -1;
    var error = "";
    var errorPos = 0;
    var errorCode = -1;
    var demo = false;

    function initialize() {}

    function load(payload) as Boolean {
        points = [];
        demo = false;
        error = "";
        errorPos = 0;
        errorCode = -1;
        var reader = new CGIQBytes();
        if (!reader.decode(payload)) {
            error = reader.error;
            errorPos = reader.errorPos;
            errorCode = reader.errorCode;
            return false;
        }
        if (reader.u8() != 67 || reader.u8() != 71 ||
            reader.u8() != 73 || reader.u8() != 81) {
            error = "MAGIC CGIQ";
            return false;
        }
        if (reader.u8() != 1) {
            error = "VERSION NON SUPPORTEE";
            return false;
        }
        var count = reader.u8();
        officialStartMin = reader.u16();
        if (count < 1 || count > 25 || officialStartMin < 0 || officialStartMin >= 1440) {
            error = "ENTETE INVALIDE";
            return false;
        }
        var lastDistance = -1.0;
        var lastTarget = -1;
        var lastAscent = -1;
        var lastDescent = -1;
        for (var i = 0; i < count; i++) {
            if (reader.remaining() < 20) {
                error = "POINT TRONQUE";
                return false;
            }
            var p = new CGIQPoint();
            p.distanceM = reader.u16() * 10.0;
            p.ascentM = reader.u16();
            p.descentM = reader.u16();
            p.targetMin = reader.u16();
            p.pauseMin = reader.u8();
            p.cutoffMin = reader.u16();
            p.flags = reader.u8();
            p.latitude10000 = reader.i24();
            p.longitude10000 = reader.i24();
            var nameSize = reader.u8();
            if (nameSize < 1 || nameSize > 18 || reader.remaining() < nameSize + 1) {
                error = "NOM INVALIDE";
                return false;
            }
            p.name = reader.ascii(nameSize);
            if (p.name.length() == 0 || p.distanceM <= lastDistance ||
                p.targetMin < lastTarget || p.ascentM < lastAscent ||
                p.descentM < lastDescent || p.pauseMin > 240 ||
                (p.cutoffMin != 65535 && p.cutoffMin < officialStartMin)) {
                error = "ORDRE POINTS";
                return false;
            }
            points.add(p);
            lastDistance = p.distanceM;
            lastTarget = p.targetMin;
            lastAscent = p.ascentM;
            lastDescent = p.descentM;
        }
        if (reader.remaining() != 1) {
            error = "LONGUEUR INVALIDE";
            return false;
        }
        signature = reader.signature();
        return true;
    }

    function demoPlan() {
        points = [];
        officialStartMin = 450;
        signature = 0;
        error = "";
        demo = true;

        var a = new CGIQPoint();
        a.name = "R1 DEMO";
        a.distanceM = 5000.0;
        a.ascentM = 180;
        a.descentM = 100;
        a.targetMin = 48;
        a.pauseMin = 5;
        a.cutoffMin = 520;
        points.add(a);

        var b = new CGIQPoint();
        b.name = "R2 DEMO";
        b.distanceM = 11000.0;
        b.ascentM = 460;
        b.descentM = 350;
        b.targetMin = 115;
        b.pauseMin = 8;
        b.cutoffMin = 595;
        points.add(b);

        var c = new CGIQPoint();
        c.name = "FIN DEMO";
        c.distanceM = 18000.0;
        c.ascentM = 720;
        c.descentM = 690;
        c.targetMin = 200;
        c.pauseMin = 0;
        c.cutoffMin = 685;
        points.add(c);
    }
}
