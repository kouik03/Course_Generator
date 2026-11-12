import Toybox.Application;
import Toybox.Application.Properties;
import Toybox.Application.Storage;
import Toybox.WatchUi;
import Toybox.Graphics;
import Toybox.Activity;
import Toybox.Attention;
import Toybox.Math;
import Toybox.Lang;

var gCourseGeneratorField = null;

class CourseGeneratorIQApp extends Application.AppBase {
    function initialize() {
        AppBase.initialize();
    }

    function getInitialView() {
        gCourseGeneratorField = new CourseGeneratorIQField();
        return [gCourseGeneratorField];
    }

    function onSettingsChanged() as Void {
        if (gCourseGeneratorField != null) {
            gCourseGeneratorField.reloadSettings();
        }
    }
}

class CourseGeneratorIQField extends WatchUi.DataField {
    var _plan = null;
    var _index = 0;
    var _atStation = false;
    var _arrivalSec = 0;
    var _departTimerSec = 0;
    var _offsetM = 0.0;
    var _elapsedSec = 0;
    var _timerSec = 0;
    var _rawDistanceM = 0.0;
    var _speedMps = 0.0;
    var _lat = 0.0;
    var _lon = 0.0;
    var _hasGps = false;
    var _nearSeconds = 0;
    var _awaySeconds = 0;
    var _prewarned = false;
    var _departureWarned = false;
    var _delayMin = 0;
    var _prewarnMin = 3;
    var _autoGps = true;
    var _manualLap = true;
    var _radiusM = 100;
    var _progressMode = 2; // 0 full course, 1 between CPs, 2 last-km zoom
    var _paused = false;
    var _b1len = 0;
    var _b2len = 0;
    var _b3len = 0;
    var _decoderTest = "NON TESTE";

    function initialize() {
        DataField.initialize();
        reloadSettings();
    }

    function reloadSettings() {
        var first = Properties.getValue("Block1");
        var second = Properties.getValue("Block2");
        var third = Properties.getValue("Block3");
        var b1 = first == null ? "" : first.toString();
        var b2 = second == null ? "" : second.toString();
        var b3 = third == null ? "" : third.toString();
        _b1len = b1.length();
        _b2len = b2.length();
        _b3len = b3.length();
        var data = b1 + b2 + b3;

        var nextPlan = new CGIQPlan();
        if (data.length() == 0) {
            nextPlan.demoPlan();
        } else {
            nextPlan.load(data);
        }
        _plan = nextPlan;

        // Diagnostic is run only on failed imports. It separates a broken
        // decoder from a corruption in Garmin's property transfer/editing.
        _decoderTest = "NON TESTE";
        if (_plan.error.length() != 0) {
            var reference = new CGIQPlan();
            var check = "Q0dJUQEDwgH0AbQAZAAwAAUIAgEAAAAAAAAHUjEg" +
                        "REVNT0wEzAFeAXMACFMCAQAAAAAAAAdSMiBERU1P" +
                        "CAfQArICyAAArQIAAAAAAAAACEZJTiBERU1Ppg==";
            if (reference.load(check)) {
                _decoderTest = "DECODEUR OK";
            } else {
                _decoderTest = "DECODEUR: " + reference.error;
            }
        }

        var delay = Properties.getValue("StartDelay");
        if (delay != null) { _delayMin = delay; }
        var warn = Properties.getValue("RestAlertMin");
        if (warn != null) { _prewarnMin = warn; }
        var automatic = Properties.getValue("AutoGps");
        if (automatic != null) { _autoGps = automatic; }
        var manual = Properties.getValue("ManualLap");
        if (manual != null) { _manualLap = manual; }
        var radius = Properties.getValue("GpsRadius");
        if (radius != null) { _radiusM = radius; }
        var mode = Properties.getValue("ProgressMode");
        if (mode != null && mode >= 0 && mode <= 2) {
            _progressMode = mode;
        }

        if (_plan.error.length() != 0) {
            clearState();
            return;
        }
        var storedId = Storage.getValue("courseSig");
        if (storedId != null && storedId == _plan.signature) {
            var storedIndex = Storage.getValue("pointIndex");
            if (storedIndex != null && storedIndex >= 0 &&
                storedIndex <= _plan.points.size()) {
                _index = storedIndex;
            }
            var atStation = Storage.getValue("atStation");
            if (atStation != null) { _atStation = atStation; }
            var arrival = Storage.getValue("arrivalSec");
            if (arrival != null) { _arrivalSec = arrival; }
            var departure = Storage.getValue("departTimerSec");
            if (departure != null) { _departTimerSec = departure; }
            var offset = Storage.getValue("routeOffsetM");
            if (offset != null) { _offsetM = offset; }
        } else {
            clearState();
        }
    }

    function clearState() {
        _index = 0;
        _atStation = false;
        _arrivalSec = 0;
        _departTimerSec = 0;
        _offsetM = 0.0;
        _nearSeconds = 0;
        _awaySeconds = 0;
        _prewarned = false;
        _departureWarned = false;
        saveState();
    }

    function saveState() {
        if (_plan == null || _plan.error.length() != 0) {
            return;
        }
        Storage.setValue("courseSig", _plan.signature);
        Storage.setValue("pointIndex", _index);
        Storage.setValue("atStation", _atStation);
        Storage.setValue("arrivalSec", _arrivalSec);
        Storage.setValue("departTimerSec", _departTimerSec);
        Storage.setValue("routeOffsetM", _offsetM);
    }

    function compute(info as Activity.Info) as Void {
        if (info.elapsedTime != null) {
            _elapsedSec = (info.elapsedTime / 1000).toNumber();
        }
        if (info.timerTime != null) {
            _timerSec = (info.timerTime / 1000).toNumber();
        }
        if (info.elapsedDistance != null) {
            _rawDistanceM = info.elapsedDistance;
        }
        if (info.currentSpeed != null) {
            _speedMps = info.currentSpeed;
        } else {
            _speedMps = 0.0;
        }
        _hasGps = false;
        if (info.currentLocation != null) {
            var coords = info.currentLocation.toDegrees();
            _lat = coords[0];
            _lon = coords[1];
            _hasGps = true;
        }
        if (_plan == null || _plan.error.length() != 0 ||
            _index >= _plan.points.size()) {
            return;
        }
        if (_elapsedSec < 10 && _index > 0) {
            clearState();
        }
        checkGps();
        checkRestAlerts();
    }

    function onTimerStart() as Void {
        if (_elapsedSec < 30) {
            clearState();
        }
        _paused = false;
    }

    function onTimerPause() as Void {
        _paused = true;
    }

    function onTimerResume() as Void {
        _paused = false;
    }

    function onTimerReset() as Void {
        _elapsedSec = 0;
        _timerSec = 0;
        _rawDistanceM = 0.0;
        clearState();
    }

    // On CIQ 5.2.2+ the cause of LAP is available. Ignore Garmin Auto Lap.
    function onTimerLap2(trigger as WatchUi.DataField.LapInfoType) as Lang.Boolean {
        if (_manualLap && _plan != null && _plan.error.length() == 0 &&
            trigger[:lapTrigger] == WatchUi.DataField.LAP_TRIGGER_MANUAL) {
            if (_atStation) {
                leaveStation();
            } else {
                arriveStation();
            }
        }
        return true;
    }

    function currentRouteM() {
        return _rawDistanceM + _offsetM;
    }

    function stationDistanceM(point) {
        if (!_hasGps || (point.latitude10000 == 0 && point.longitude10000 == 0)) {
            return -1.0;
        }
        var latitude = point.latitude10000 / 10000.0;
        var longitude = point.longitude10000 / 10000.0;
        var north = (_lat - latitude) * 111195.0;
        var east = (_lon - longitude) * 111195.0 * Math.cos(_lat * Math.PI / 180.0);
        return Math.sqrt(north * north + east * east);
    }

    function checkGps() {
        if (!_autoGps || _paused || _elapsedSec <= 0) {
            _nearSeconds = 0;
            _awaySeconds = 0;
            return;
        }
        var point = _plan.points[_index];
        var awayM = stationDistanceM(point);
        if (awayM < 0) {
            _nearSeconds = 0;
            _awaySeconds = 0;
            return;
        }
        if (_atStation) {
            if (awayM > _radiusM + 90 && _elapsedSec - _arrivalSec >= 45) {
                _awaySeconds++;
                if (_awaySeconds >= 10) {
                    leaveStation();
                }
            } else {
                _awaySeconds = 0;
            }
        } else {
            var closeEnoughByRoute =
                Math.abs(currentRouteM() - point.distanceM) < 1700.0;
            if (awayM <= _radiusM && closeEnoughByRoute && _speedMps <= 2.0) {
                _nearSeconds++;
                if (_nearSeconds >= 8) {
                    arriveStation();
                }
            } else {
                _nearSeconds = 0;
            }
        }
    }

    function arriveStation() {
        if (_atStation || _index >= _plan.points.size()) { return; }
        // Final checkpoint completes the course immediately: no departure LAP.
        if (_index == _plan.points.size() - 1) {
            _offsetM = _plan.points[_index].distanceM - _rawDistanceM;
            _index++;
            _atStation = false;
            saveState();
            buzz();
            return;
        }
        _atStation = true;
        _arrivalSec = _elapsedSec;
        _offsetM = _plan.points[_index].distanceM - _rawDistanceM;
        _nearSeconds = 0;
        _awaySeconds = 0;
        _prewarned = false;
        _departureWarned = false;
        saveState();
        buzz();
    }

    function leaveStation() {
        if (!_atStation) { return; }
        _atStation = false;
        _index++;
        _departTimerSec = _timerSec;
        _nearSeconds = 0;
        _awaySeconds = 0;
        saveState();
        buzz();
    }

    function checkRestAlerts() {
        if (!_atStation) { return; }
        var restLeft = _plan.points[_index].pauseMin * 60 -
            (_elapsedSec - _arrivalSec);
        if (restLeft > 0 && restLeft <= _prewarnMin * 60 && !_prewarned) {
            _prewarned = true;
            buzz();
        }
        if (restLeft <= 0 && !_departureWarned) {
            _departureWarned = true;
            buzz();
        }
    }

    function buzz() {
        if (Attention has :vibrate) {
            Attention.vibrate([new Attention.VibeProfile(70, 220)]);
        }
    }

    function plannedLegSec(i) {
        var point = _plan.points[i];
        var previousEnd = 0;
        if (i > 0) {
            var previous = _plan.points[i - 1];
            previousEnd = previous.targetMin + previous.pauseMin;
        }
        var result = (point.targetMin - previousEnd) * 60;
        return result > 0 ? result : 60;
    }

    function remainingLegSec() {
        if (_atStation || _index >= _plan.points.size()) { return 0.0; }
        var endM = _plan.points[_index].distanceM;
        var beginM = _index == 0 ? 0.0 : _plan.points[_index - 1].distanceM;
        var lengthM = endM - beginM;
        if (lengthM <= 0.0) { return 0.0; }
        var progressM = currentRouteM() - beginM;
        if (progressM < 0.0) { progressM = 0.0; }
        if (progressM > lengthM) { progressM = lengthM; }
        var scheduled = plannedLegSec(_index);
        var factor = 1.0;
        var spent = _timerSec - _departTimerSec;
        if (progressM >= 1500.0 && spent >= 480) {
            var expectedSpent = scheduled * (progressM / lengthM);
            if (expectedSpent > 0) {
                factor = spent / expectedSpent;
                if (factor < 0.65) { factor = 0.65; }
                if (factor > 2.2) { factor = 2.2; }
            }
        }
        return scheduled * (lengthM - progressM) / lengthM * factor;
    }

    function restRemainingSec(i) {
        var planned = _plan.points[i].pauseMin * 60;
        if (i == _index && _atStation) {
            planned -= _elapsedSec - _arrivalSec;
            if (planned < 0) { planned = 0; }
        }
        return planned;
    }

    function findNextCutoff() {
        for (var i = _index; i < _plan.points.size(); i++) {
            if (_plan.points[i].cutoffMin != 65535) { return i; }
        }
        return -1;
    }

    // Planned time still needed up to the next binding cutoff.
    function neededSecondsUntil(goal) {
        var result = remainingLegSec();
        for (var i = _index + 1; i <= goal; i++) {
            result += plannedLegSec(i);
        }
        for (var j = _index; j <= goal; j++) {
            if (j < goal || (_plan.points[j].flags & 1) != 0) {
                result += restRemainingSec(j);
            }
        }
        return result;
    }

    function remainingRestUntil(goal) {
        var result = 0;
        for (var i = _index; i <= goal; i++) {
            if (i < goal || (_plan.points[i].flags & 1) != 0) {
                result += restRemainingSec(i);
            }
        }
        return result;
    }

    function targetClock(minutes) {
        var absolute = minutes;
        var day = (absolute / 1440).toNumber();
        var within = absolute % 1440;
        var hour = (within / 60).toNumber();
        var minute = within % 60;
        return hour.format("%02d") + ":" + minute.format("%02d") +
            (day > 0 ? "+1j" : "");
    }

    function formatDuration(seconds) {
        var s = Math.abs(seconds).toNumber();
        var h = (s / 3600).toNumber();
        var m = ((s % 3600) / 60).toNumber();
        if (h > 0) {
            return h.format("%d") + "h" + m.format("%02d");
        }
        return m.format("%d") + "m";
    }

    function formatPace(minutesPerKm) {
        if (minutesPerKm <= 0 || minutesPerKm >= 100) { return "--:--"; }
        var seconds = (minutesPerKm * 60).toNumber();
        return (seconds / 60).toNumber().format("%d") + ":" +
            (seconds % 60).format("%02d");
    }

    // Garmin round displays have less horizontal room near the top and
    // bottom. Measure each string against the actual safe chord of the dial.
    // Smaller rectangular data fields use their full rectangular bounds.
    function usableTextWidth(dc, y, font) {
        var width = dc.getWidth();
        var height = dc.getHeight();
        var allowed = width * 0.91;
        if (width >= 230 && height >= 230 &&
            width * 100 / height >= 87 &&
            width * 100 / height <= 113) {
            var diameter = width < height ? width : height;
            var radius = diameter * 0.468;
            var centerY = y + dc.getFontHeight(font) / 2;
            var offsetY = Math.abs(centerY - height / 2);
            if (offsetY >= radius) {
                return width * 0.18;
            }
            var chord = 2.0 * Math.sqrt(
                radius * radius - offsetY * offsetY) - 14;
            if (chord < allowed) {
                allowed = chord;
            }
        }
        return allowed;
    }

    // Keep important values visible: lower the font when necessary and
    // truncate only as a last resort. No label can bleed outside the dial.
    function txt(dc, y, font, value, color) {
        var drawFont = font;
        var allowed = usableTextWidth(dc, y, drawFont);
        if (dc.getTextWidthInPixels(value, drawFont) > allowed &&
            drawFont == Graphics.FONT_LARGE) {
            drawFont = Graphics.FONT_MEDIUM;
            allowed = usableTextWidth(dc, y, drawFont);
        }
        if (dc.getTextWidthInPixels(value, drawFont) > allowed &&
            drawFont == Graphics.FONT_MEDIUM) {
            drawFont = Graphics.FONT_SMALL;
            allowed = usableTextWidth(dc, y, drawFont);
        }
        if (dc.getTextWidthInPixels(value, drawFont) > allowed &&
            drawFont == Graphics.FONT_SMALL) {
            drawFont = Graphics.FONT_XTINY;
            allowed = usableTextWidth(dc, y, drawFont);
        }

        var shown = value;
        if (dc.getTextWidthInPixels(shown, drawFont) > allowed) {
            var endIndex = shown.length();
            while (endIndex > 0 &&
                dc.getTextWidthInPixels(
                    value.substring(0, endIndex) + "..", drawFont) > allowed) {
                endIndex--;
            }
            shown = endIndex > 0 ? value.substring(0, endIndex) + ".." : "";
        }
        dc.setColor(color, Graphics.COLOR_TRANSPARENT);
        dc.drawText(dc.getWidth() / 2, y, drawFont, shown,
                    Graphics.TEXT_JUSTIFY_CENTER);
    }

    // Fit a single text item in its own column. We never draw off the dial.
    function fittedText(dc, centerX, y, font, textValue, maxW, color) {
        var selected = font;
        if (dc.getTextWidthInPixels(textValue, selected) > maxW &&
            selected == Graphics.FONT_LARGE) {
            selected = Graphics.FONT_MEDIUM;
        }
        if (dc.getTextWidthInPixels(textValue, selected) > maxW &&
            selected == Graphics.FONT_MEDIUM) {
            selected = Graphics.FONT_SMALL;
        }
        if (dc.getTextWidthInPixels(textValue, selected) > maxW &&
            selected == Graphics.FONT_SMALL) {
            selected = Graphics.FONT_XTINY;
        }
        var out = textValue;
        if (dc.getTextWidthInPixels(out, selected) > maxW) {
            var length = out.length();
            while (length > 0 && dc.getTextWidthInPixels(
                textValue.substring(0, length) + "..", selected) > maxW) {
                length--;
            }
            out = length == 0 ? "" : textValue.substring(0, length) + "..";
        }
        dc.setColor(color, Graphics.COLOR_TRANSPARENT);
        dc.drawText(centerX, y, selected, out,
                    Graphics.TEXT_JUSTIFY_CENTER);
    }

    function remainingFinishSeconds() {
        if (_plan == null || _index >= _plan.points.size()) { return 0; }
        var result = remainingLegSec();
        for (var i = _index + 1; i < _plan.points.size(); i++) {
            result += plannedLegSec(i);
        }
        for (var j = _index; j < _plan.points.size() - 1; j++) {
            result += restRemainingSec(j);
        }
        return result;
    }

    function segmentPace() {
        if (_atStation || _index >= _plan.points.size()) { return "--:--"; }
        var segmentStart = _index == 0 ? 0.0 :
                           _plan.points[_index - 1].distanceM;
        var movedM = currentRouteM() - segmentStart;
        var activeSec = _timerSec - _departTimerSec;
        if (movedM < 400 || activeSec <= 30) { return "--:--"; }
        return formatPace(activeSec / 60.0 / (movedM / 1000.0));
    }

    // A checkpoint timeline, with the white dot tracking the athlete.
    // View modes: entire race, current leg, or automatic last-kilometer zoom.
    function drawRoadbookBar(dc, y, routeM, remainingM, compact) {
        var width = dc.getWidth();
        var x = (width * (compact ? 0.075 : 0.16)).toNumber();
        var span = (width * (compact ? 0.85 : 0.68)).toNumber();
        var last = _plan.points[_plan.points.size() - 1];
        var fromM = 0.0;
        var toM = last.distanceM;
        var modeLabel = "PARCOURS";
        var legStartM = _index == 0 ? 0.0 :
                        _plan.points[_index - 1].distanceM;
        var legEndM = _plan.points[_index].distanceM;

        if (_progressMode == 1) {
            fromM = legStartM;
            toM = legEndM;
            modeLabel = "TRONCON";
        } else if (_progressMode == 2 &&
                   remainingM <= 1000.0 && !_atStation &&
                   _index < _plan.points.size()) {
            fromM = legEndM - 1000.0;
            if (fromM < legStartM) { fromM = legStartM; }
            toM = legEndM;
            modeLabel = "DERNIER KM";
        }

        if (toM <= fromM) { return; }
        var viewM = routeM;
        if (_atStation) { viewM = legEndM; }
        if (viewM < fromM) { viewM = fromM; }
        if (viewM > toM) { viewM = toM; }
        var ratio = (viewM - fromM) / (toM - fromM);
        var doneW = (span * ratio).toNumber();

        dc.setColor(0x454C55, Graphics.COLOR_TRANSPARENT);
        dc.fillRoundedRectangle(x, y - 3, span, 6, 3);
        if (doneW > 0) {
            dc.setColor(0xFF9800, Graphics.COLOR_TRANSPARENT);
            dc.fillRoundedRectangle(x, y - 3, doneW, 6, 3);
        }

        // Small intermediate ticks make the last 1 km useful at a glance.
        if (modeLabel == "DERNIER KM" && !compact) {
            for (var tick = 1; tick < 10; tick++) {
                var positionM = fromM + (toM - fromM) * tick / 10.0;
                var pixel = x + ((positionM - fromM) /
                                 (toM - fromM) * span).toNumber();
                dc.setColor(0x65717C, Graphics.COLOR_TRANSPARENT);
                dc.drawLine(pixel, y - 8, pixel, y + 7);
            }
        }

        // All roadbook checkpoints visible in full-course view.
        for (var i = 0; i < _plan.points.size(); i++) {
            var km = _plan.points[i].distanceM;
            if (km < fromM || km > toM) { continue; }
            var markerX = x +
                ((km - fromM) / (toM - fromM) * span).toNumber();
            var isPassed = i < _index || (i == _index && _atStation);
            var color = isPassed ? 0xFF9800 : 0xBAC1CA;
            dc.setColor(color, Graphics.COLOR_TRANSPARENT);
            dc.fillCircle(markerX, y, i == _index ? 5 : 3);
        }

        // Current athlete position, deliberately bigger than CP markers.
        var hereX = x + doneW;
        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_TRANSPARENT);
        dc.fillCircle(hereX, y, 7);
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        dc.fillCircle(hereX, y, 4);

        if (!compact) {
            var caption = modeLabel + "  ";
            if (modeLabel == "PARCOURS") {
                caption += (routeM / 1000.0).format("%.1f") + "/" +
                           (last.distanceM / 1000.0).format("%.1f") + " KM";
            } else {
                caption += ((viewM - fromM) / 1000.0).format("%.1f") +
                           "/" + ((toM - fromM) / 1000.0).format("%.1f") + " KM";
            }
            fittedText(dc, width / 2, y + (dc.getHeight() * 0.026),
                       Graphics.FONT_XTINY, caption, width * 0.72, 0xB1B6C0);
        }
    }

    function drawNextPointRow(dc, y, index, routeM, width) {
        if (index >= _plan.points.size()) { return; }
        var station = _plan.points[index];
        var toGo = station.distanceM - routeM;
        if (toGo < 0) { toGo = 0; }
        var xLabel = width * 0.38;
        var xValue = width * 0.73;
        var nameColor = index == _index ? 0xFFFFFF : 0xBFC7D2;
        fittedText(dc, xLabel, y, Graphics.FONT_SMALL, station.name,
                   width * 0.39, nameColor);
        fittedText(dc, xValue, y, Graphics.FONT_SMALL,
                   (toGo / 1000.0).format("%.1f") + " km",
                   width * 0.27, index == _index ? 0xFF9800 : 0xBFC7D2);
    }

    function onUpdate(dc as Graphics.Dc) as Void {
        var width = dc.getWidth();
        var height = dc.getHeight();
        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_BLACK);
        dc.clear();

        var compact = height < 190 || width < 190;
        var white = Graphics.COLOR_WHITE;
        var muted = 0xB7BBC4;
        var accent = 0x18B8F3;
        var orange = 0xFF9800;
        var amber = 0xFFBD38;
        var red = 0xFF3737;
        var green = 0x24DD53;

        if (_plan == null || _plan.error.length() != 0) {
            txt(dc, height * 0.14, Graphics.FONT_XTINY,
                "CG IQ / DIAGNOSTIC", muted);
            txt(dc, height * 0.28, Graphics.FONT_SMALL,
                _plan == null ? "AUCUN PLAN" : _plan.error, red);
            var detail = "AUCUN CARACTERE DETECTE";
            if (_plan != null && _plan.errorPos > 0) {
                var location = _plan.errorPos;
                var block = 1;
                if (location > _b1len) {
                    location -= _b1len;
                    block = 2;
                    if (location > _b2len) {
                        location -= _b2len;
                        block = 3;
                    }
                }
                detail = "BLOC " + block.format("%d") +
                         " POS " + location.format("%d");
            }
            txt(dc, height * 0.43, Graphics.FONT_SMALL, detail, white);
            if (_plan != null && _plan.errorCode >= 0) {
                txt(dc, height * 0.53, Graphics.FONT_SMALL,
                    "CHAR U+" + _plan.errorCode.format("%04X"), amber);
            }
            txt(dc, height * 0.66, Graphics.FONT_XTINY,
                "LONGUEURS " + _b1len.format("%d") +
                "/" + _b2len.format("%d") +
                "/" + _b3len.format("%d"), muted);
            txt(dc, height * 0.78, Graphics.FONT_XTINY,
                _decoderTest, accent);
            return;
        }

        var title = _plan.demo ? "CG IQ / DEMO" : "COURSE GENERATOR IQ";
        if (_index >= _plan.points.size()) {
            txt(dc, height * 0.18, Graphics.FONT_SMALL, title, accent);
            txt(dc, height * 0.38, Graphics.FONT_LARGE, "ARRIVEE", green);
            txt(dc, height * 0.59, Graphics.FONT_MEDIUM,
                (_rawDistanceM / 1000.0).format("%.2f") + " km", white);
            txt(dc, height * 0.72, Graphics.FONT_SMALL,
                "ROADBOOK TERMINE", muted);
            return;
        }

        var point = _plan.points[_index];
        var routeM = currentRouteM();
        if (routeM < 0.0) { routeM = 0.0; }
        var remainingM = point.distanceM - routeM;
        if (remainingM < 0) { remainingM = 0.0; }

        var goal = findNextCutoff();
        var cutoffClock = "--:--";
        var margin = 0.0;
        var requiredPace = "--:--";
        var marginColor = muted;
        if (goal >= 0) {
            // Official cutoffs never move with a later personal start.
            var deadline = (_plan.points[goal].cutoffMin -
                _plan.officialStartMin - _delayMin) * 60;
            margin = deadline - _elapsedSec - neededSecondsUntil(goal);
            cutoffClock = targetClock(_plan.points[goal].cutoffMin);
            marginColor = margin < 0 ? red : (margin < 900 ? amber : green);

            var movable = deadline - _elapsedSec -
                          remainingRestUntil(goal);
            var distanceToLimit = _plan.points[goal].distanceM - routeM;
            if (movable > 60 && distanceToLimit > 10) {
                requiredPace = formatPace(
                    movable / 60.0 / (distanceToLimit / 1000.0));
            }
        }

        var eta = _elapsedSec + remainingLegSec();
        var etaClock = targetClock(_plan.officialStartMin +
            _delayMin + (eta / 60).toNumber());
        var plannedClock = targetClock(_plan.officialStartMin +
            _delayMin + point.targetMin);
        var signedMargin = (margin < 0 ? "-" : "+") +
            formatDuration(margin);
        var finishClock = targetClock(_plan.officialStartMin +
            _delayMin +
            ((_elapsedSec + remainingFinishSeconds()) / 60).toNumber());

        if (compact) {
            txt(dc, height * 0.035, Graphics.FONT_XTINY,
                point.name, accent);
            txt(dc, height * 0.25, Graphics.FONT_LARGE,
                _atStation ? "PAUSE " + formatDuration(restRemainingSec(_index)) :
                (remainingM / 1000.0).format("%.1f") + " km", white);
            drawRoadbookBar(dc, (height * 0.61).toNumber(), routeM,
                            remainingM, true);
            txt(dc, height * 0.76, Graphics.FONT_SMALL,
                goal < 0 ? "SANS BH" :
                "BH " + cutoffClock + " / " + signedMargin, marginColor);
            return;
        }

        // Garmin 51 mm full-screen: every line stays inside a safe chord
        // of the round dial, unlike the former single-column prototype.
        txt(dc, height * 0.116, Graphics.FONT_SMALL, point.name, accent);

        var colW = width * 0.315;
        fittedText(dc, width * 0.30, height * 0.208,
                   Graphics.FONT_XTINY,
                   _atStation ? "PAUSE RESTANTE" : "PROCHAIN / KM",
                   colW, muted);
        fittedText(dc, width * 0.70, height * 0.208,
                   Graphics.FONT_XTINY,
                   goal >= 0 ? "BH " + cutoffClock : "SANS BH",
                   colW, muted);

        fittedText(dc, width * 0.30, height * 0.278,
                   Graphics.FONT_LARGE,
                   _atStation ? formatDuration(restRemainingSec(_index)) :
                   (remainingM / 1000.0).format("%.2f"),
                   colW, _atStation ? amber : white);
        fittedText(dc, width * 0.70, height * 0.278,
                   Graphics.FONT_LARGE,
                   goal >= 0 ? signedMargin : "--",
                   colW, marginColor);

        txt(dc, height * 0.398, Graphics.FONT_SMALL,
            _atStation ? "RAVITO / LAP POUR REPARTIR" :
            "ETA " + etaClock + "  /  PLAN " + plannedClock, muted);

        drawRoadbookBar(dc, (height * 0.481).toNumber(), routeM,
                        remainingM, false);

        // The two next stops are always visible in addition to all the
        // checkpoint dots along the timeline, each with the remaining km.
        var first = _atStation ? _index + 1 : _index;
        drawNextPointRow(dc, height * 0.583, first, routeM, width);
        drawNextPointRow(dc, height * 0.644, first + 1, routeM, width);

        var leftPace = segmentPace();
        fittedText(dc, width * 0.30, height * 0.733,
                   Graphics.FONT_XTINY, "MOY. TRONCON", colW, muted);
        fittedText(dc, width * 0.70, height * 0.733,
                   Graphics.FONT_XTINY, "REQUIS / KM", colW, muted);
        fittedText(dc, width * 0.30, height * 0.780,
                   Graphics.FONT_MEDIUM, leftPace, colW, white);
        fittedText(dc, width * 0.70, height * 0.780,
                   Graphics.FONT_MEDIUM, requiredPace, colW,
                   marginColor);

        var previousUp = _index == 0 ? 0 :
                         _plan.points[_index - 1].ascentM;
        var begin = _index == 0 ? 0.0 :
                    _plan.points[_index - 1].distanceM;
        var length = point.distanceM - begin;
        var share = length > 0 ? remainingM / length : 0.0;
        if (share > 1.0) { share = 1.0; }
        var upRemaining = ((point.ascentM - previousUp) * share).toNumber();
        txt(dc, height * 0.860, Graphics.FONT_XTINY,
            "D+ ~" + upRemaining.format("%d") +
            "m  /  FIN ~" + finishClock, muted);
    }
}
