import Toybox.Application;
import Toybox.Application.Properties;
import Toybox.Application.Storage;
import Toybox.WatchUi;
import Toybox.Graphics;
import Toybox.Activity;
import Toybox.Attention;
import Toybox.Math;
import Toybox.System;
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

    // One native Garmin Menu2 for the watch AND the simulator.
    // Neither Base64 configuration nor phone settings are shown here.
    function getSettingsView() {
        System.println("CGIQ: open native Menu2 settings");
        var menu = new CGIQNativeMenu();
        return [menu, new CGIQNativeMenuDelegate(menu)];
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
    var _previewIndex = -1;

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
            // A fresh roadbook must not inherit a CP preview from an older
            // event. Only the wave delay is intentionally shared.
            Storage.setValue("previewCpIndex", -1);
            clearState();
        }
        applyDeviceSettings();
    }

    // On-watch menu values are persisted in Storage, not in the phone
    // settings. Apply them without reloading/replacing the live roadbook.
    function applyDeviceSettings() {
        _delayMin = cgiqWatchStartDelay();

        var preview = Storage.getValue("previewCpIndex");
        _previewIndex = -1;
        if (_plan != null && _plan.error.length() == 0 &&
            preview != null && preview >= 0 &&
            preview < _plan.points.size()) {
            _previewIndex = preview;
        }

        var req = Storage.getValue("manualCpRequestId");
        var handled = Storage.getValue("manualCpAppliedId");
        if (req == null || req == handled ||
            _plan == null || _plan.error.length() != 0) {
            return;
        }
        var target = Storage.getValue("manualCpIndex");
        var requestPlan = Storage.getValue("manualCpPlanSig");
        if (requestPlan == _plan.signature && target != null &&
            target >= 0 && target < _plan.points.size()) {
            // Choosing the NEXT CP confirms every CP before it. Anchor the
            // virtual course position at least at the previous checkpoint;
            // otherwise selecting FIN at activity distance 0 would leave
            // the progress bar empty and show the full 18 km remaining.
            //
            // This is a ROADBOOK correction only: _rawDistanceM (Garmin's
            // measured activity distance and the FIT track) is unchanged.
            var previousM = target == 0 ? 0.0 :
                            _plan.points[target - 1].distanceM;
            var routeBeforeM = currentRouteM();
            if (target < _index || routeBeforeM < previousM) {
                _offsetM = previousM - _rawDistanceM;
            }

            _index = target;
            _atStation = false;
            _arrivalSec = 0;
            _departTimerSec = _timerSec;
            _nearSeconds = 0;
            _awaySeconds = 0;
            _prewarned = false;
            _departureWarned = false;
            _previewIndex = -1;
            saveState();
        }
        Storage.setValue("manualCpAppliedId", req);
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
        applyDeviceSettings();
        if (_elapsedSec >= 30 &&
            Storage.getValue("manualCpKeepAtStart") == true) {
            Storage.setValue("manualCpKeepAtStart", false);
        }
        if (_plan == null || _plan.error.length() != 0 ||
            _index >= _plan.points.size()) {
            return;
        }
        checkGps();
        checkRestAlerts();
    }

    function onTimerStart() as Void {
        if (_elapsedSec < 30) {
            // An explicit CP selection in watch settings before starting
            // an activity should not be discarded as a stale previous race.
            var armed = Storage.getValue("manualCpKeepAtStart");
            if (armed != true) { clearState(); }
        }
        Storage.setValue("manualCpKeepAtStart", false);
        applyDeviceSettings();
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
        Storage.setValue("manualCpKeepAtStart", false);
        Storage.setValue("previewCpIndex", -1);
        _previewIndex = -1;
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

    // Draw the numeric quantity and its unit as ONE measured visual group.
    // Independent, fixed-position labels gave a small "5.00", followed by
    // an enormous gap before "km" (same problem with "1:10" and "h").
    // Choose the largest number font that fits both the column and row;
    // then bottom-align the much smaller unit against the number.
    function drawPrimaryMetric(dc, centerX, y, numberValue, unitValue,
                               maxWidth, maxHeight, color) {
        var numberFont = Graphics.FONT_LARGE;
        var unitFont = Graphics.FONT_XTINY;
        var unitH = dc.getFontHeight(unitFont);
        var gap = 5;
        var numberW = dc.getTextWidthInPixels(numberValue, numberFont);
        var unitW = dc.getTextWidthInPixels(unitValue, unitFont);

        if ((numberW + gap + unitW > maxWidth ||
             dc.getFontHeight(numberFont) > maxHeight) &&
            numberFont == Graphics.FONT_LARGE) {
            numberFont = Graphics.FONT_MEDIUM;
        }
        numberW = dc.getTextWidthInPixels(numberValue, numberFont);
        if ((numberW + gap + unitW > maxWidth ||
             dc.getFontHeight(numberFont) > maxHeight) &&
            numberFont == Graphics.FONT_MEDIUM) {
            numberFont = Graphics.FONT_SMALL;
        }
        numberW = dc.getTextWidthInPixels(numberValue, numberFont);
        var numberH = dc.getFontHeight(numberFont);
        var totalW = numberW + gap + unitW;
        var left = centerX - totalW / 2;
        dc.setColor(color, Graphics.COLOR_TRANSPARENT);
        dc.drawText(left, y, numberFont, numberValue,
                    Graphics.TEXT_JUSTIFY_LEFT);
        var unitY = y + numberH - unitH;
        dc.drawText(left + numberW + gap, unitY,
                    unitFont, unitValue, Graphics.TEXT_JUSTIFY_LEFT);
    }

    // Fit text in one column without letting the ROUND AMOLED mask clip
    // its left or right edge. This is especially important for the CP name
    // and official BH near the top of the Fenix 9 Pro screen.
    function roundColumnText(dc, centerX, y, font, value, maxW, color) {
        var chord = usableTextWidth(dc, y, font);
        var leftEdge = (dc.getWidth() - chord) / 2;
        var rightEdge = dc.getWidth() - leftEdge;
        var leftSpace = centerX - leftEdge;
        var rightSpace = rightEdge - centerX;
        var half = leftSpace < rightSpace ? leftSpace : rightSpace;
        var roundWidth = half * 2 - 6;
        if (roundWidth < 0) { roundWidth = 0; }
        fittedText(dc, centerX, y, font, value,
                   roundWidth < maxW ? roundWidth : maxW, color);
    }

    // Guard each row against the actual font metrics published by the
    // device SDK. The slot positions below remain stable even when a
    // font must shrink, so adjusting the header cannot move the footer.
    function fontForSlot(dc, preferred, maxHeight) {
        var font = preferred;
        if (dc.getFontHeight(font) > maxHeight &&
            font == Graphics.FONT_LARGE) {
            font = Graphics.FONT_MEDIUM;
        }
        if (dc.getFontHeight(font) > maxHeight &&
            font == Graphics.FONT_MEDIUM) {
            font = Graphics.FONT_SMALL;
        }
        if (dc.getFontHeight(font) > maxHeight &&
            font == Graphics.FONT_SMALL) {
            font = Graphics.FONT_XTINY;
        }
        return font;
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

        // Subdued navy line and cool-blue progress, matching the
        // checkpoint timeline in the supplied real-watch reference.
        dc.setColor(0x354B67, Graphics.COLOR_TRANSPARENT);
        dc.fillRoundedRectangle(x, y - 2, span, 4, 2);
        if (doneW > 0) {
            dc.setColor(0x66C1F4, Graphics.COLOR_TRANSPARENT);
            dc.fillRoundedRectangle(x, y - 2, doneW, 4, 2);
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
            // Upcoming aid stations have a clear hollow blue ring;
            // completed checkpoints remain filled blue.
            var isPassed = km <= routeM;
            if (isPassed) {
                dc.setColor(0x86D7FF, Graphics.COLOR_TRANSPARENT);
                dc.fillCircle(markerX, y, 5);
            } else {
                dc.setColor(0x86D7FF, Graphics.COLOR_TRANSPARENT);
                dc.fillCircle(markerX, y, 7);
                dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_TRANSPARENT);
                dc.fillCircle(markerX, y, 4);
            }
        }

        // Keep the same outer BLUE RING even when the runner is exactly
        // on a CP marker. The WHITE CENTRE, not a blank white dot, clearly
        // distinguishes the current position from future hollow markers.
        var hereX = x + doneW;
        dc.setColor(0x86D7FF, Graphics.COLOR_TRANSPARENT);
        dc.fillCircle(hereX, y, 9);
        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_TRANSPARENT);
        dc.fillCircle(hereX, y, 6);
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        dc.fillCircle(hereX, y, 4);


    }

    function formatCountdown(seconds) {
        var left = Math.abs(seconds).toNumber();
        var h = (left / 3600).toNumber();
        var m = ((left % 3600) / 60).toNumber();
        return (seconds < 0 ? "-" : "") +
               h.format("%d") + ":" + m.format("%02d");
    }

    // Original tiny pictograms, only shown if the roadbook explicitly
    // identifies services. Bit 1 = water; bit 2 = food (bit 0 = cutoff mode).
    // Draw deliberately simple, original service symbols at compact size.
    // Only water and food are currently defined by CGIQ v1. The other
    // three symbol slots are RESERVED for future protocol extensions;
    // no nonexistent service is displayed or assumed.
    function drawCheckpointSymbol(dc, centerX, centerY, kind) {
        var x = centerX.toNumber();
        var y = centerY.toNumber();
        if (kind == 0) {
            // Water: droplet (filled tip and rounded lower body).
            dc.setColor(0x54CCFF, Graphics.COLOR_TRANSPARENT);
            dc.fillCircle(x, y + 4, 5);
            dc.drawLine(x - 4, y + 1, x, y - 8);
            dc.drawLine(x, y - 8, x + 4, y + 1);
            dc.drawLine(x - 3, y, x + 3, y);
        } else if (kind == 1) {
            // Food: fork and knife, using white for contrast.
            dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
            dc.drawLine(x - 7, y - 7, x - 7, y + 8);
            dc.drawLine(x - 10, y - 7, x - 10, y - 1);
            dc.drawLine(x - 4, y - 7, x - 4, y - 1);
            dc.drawLine(x + 5, y - 7, x + 5, y + 8);
            dc.drawLine(x + 5, y - 7, x + 9, y - 3);
        } else {
            // Visual placeholders for future service categories. These are
            // not shown until future blocks explicitly set their bits.
            dc.setColor(0xB7C6D8, Graphics.COLOR_TRANSPARENT);
            if (kind == 2) {
                dc.drawCircle(x, y, 7);
                dc.fillCircle(x, y, 2);
            } else if (kind == 3) {
                dc.drawLine(x - 6, y, x + 6, y);
                dc.drawLine(x, y - 6, x, y + 6);
            } else if (kind == 4) {
                dc.drawLine(x, y - 8, x + 6, y);
                dc.drawLine(x + 6, y, x, y + 8);
                dc.drawLine(x, y + 8, x - 6, y);
                dc.drawLine(x - 6, y, x, y - 8);
            }
        }
    }

    // First row: a single compact CP code followed by up to FIVE possible
    // service icons. Center the entire GROUP, rather than dividing it into
    // two columns. The second row (name / CUT) is much wider on a circle.
    function drawCheckpointHeader(dc, topY, maxHeight, cpCode, flags, color) {
        var w = dc.getWidth();
        var serviceBits = [2, 4, 8, 16, 32];
        var iconCapacity = serviceBits.size();
        var cpFont = fontForSlot(dc, Graphics.FONT_MEDIUM, maxHeight);
        var cpWidth = dc.getTextWidthInPixels(cpCode, cpFont);
        var iconCount = 0;
        // Bit 0 is cutoff timing; bit 1 water, bit 2 food.
        // Bits 3-5 are RESERVED and not currently emitted by CGIQ v1.
        for (var i = 0; i < iconCapacity; i++) {
            if ((flags & serviceBits[i]) != 0) { iconCount++; }
        }

        var gap = 12;
        var step = 25;
        var iconsWidth = iconCount > 0 ? gap + step * iconCount : 0;
        var available = usableTextWidth(dc, topY, cpFont);
        var total = cpWidth + iconsWidth;
        if (total > available) {
            cpFont = Graphics.FONT_SMALL;
            cpWidth = dc.getTextWidthInPixels(cpCode, cpFont);
            available = usableTextWidth(dc, topY, cpFont);
            total = cpWidth + iconsWidth;
        }
        if (total > available && iconCount > 0) {
            step = ((available - cpWidth - gap) / iconCount).toNumber();
            if (step < 19) { step = 19; }
            iconsWidth = gap + step * iconCount;
            total = cpWidth + iconsWidth;
        }

        var left = (w - total) / 2;
        dc.setColor(color, Graphics.COLOR_TRANSPARENT);
        dc.drawText(left, topY, cpFont, cpCode,
                    Graphics.TEXT_JUSTIFY_LEFT);

        if (iconCount == 0) { return; }
        var cursor = left + cpWidth + gap;
        var centerY = topY + dc.getFontHeight(cpFont) / 2;
        for (var j = 0; j < iconCapacity; j++) {
            if ((flags & serviceBits[j]) != 0) {
                drawCheckpointSymbol(dc, cursor + step / 2, centerY, j);
                cursor += step;
            }
        }
    }

    // Browsing is read-only: live CP, LAP, pause and GPS progress still use
    // _index. A separate preview screen prevents the CP's cutoff being
    // confused with the real race cutoff.
    function drawPointPreview(dc, index) {
        var width = dc.getWidth();
        var height = dc.getHeight();
        var point = _plan.points[index];
        var oldDistance = index == 0 ? 0.0 :
                          _plan.points[index - 1].distanceM;
        var oldUp = index == 0 ? 0 :
                    _plan.points[index - 1].ascentM;
        var oldDown = index == 0 ? 0 :
                      _plan.points[index - 1].descentM;
        var remaining = point.distanceM - currentRouteM();

        var blue = 0x5EC8FF;
        var white = Graphics.COLOR_WHITE;
        var muted = 0xB7BBC4;
        var heading = index == _plan.points.size() - 1 ?
            "CONSULT FIN" : "CONSULT CP" + (index + 1).format("%d");
        txt(dc, height * 0.105, Graphics.FONT_SMALL, heading, blue);
        txt(dc, height * 0.195, Graphics.FONT_MEDIUM, point.name, white);

        fittedText(dc, width * 0.29, height * 0.304,
            Graphics.FONT_XTINY, "KM CUMULES", width * 0.35, muted);
        fittedText(dc, width * 0.72, height * 0.304,
            Graphics.FONT_XTINY, "KM TRONCON", width * 0.35, muted);
        fittedText(dc, width * 0.29, height * 0.372,
            Graphics.FONT_MEDIUM, (point.distanceM / 1000.0).format("%.2f"),
            width * 0.35, white);
        fittedText(dc, width * 0.72, height * 0.372,
            Graphics.FONT_MEDIUM,
            ((point.distanceM - oldDistance) / 1000.0).format("%.2f"),
            width * 0.35, white);

        fittedText(dc, width * 0.29, height * 0.482,
            Graphics.FONT_XTINY, "D+ TRONCON", width * 0.35, muted);
        fittedText(dc, width * 0.72, height * 0.482,
            Graphics.FONT_XTINY, "D- TRONCON", width * 0.35, muted);
        fittedText(dc, width * 0.29, height * 0.548,
            Graphics.FONT_MEDIUM,
            (point.ascentM - oldUp).format("%d") + " m",
            width * 0.35, blue);
        fittedText(dc, width * 0.72, height * 0.548,
            Graphics.FONT_MEDIUM,
            (point.descentM - oldDown).format("%d") + " m",
            width * 0.35, blue);

        txt(dc, height * 0.632, Graphics.FONT_XTINY,
            "CUMULE D+ " + point.ascentM.format("%d") +
            " / D- " + point.descentM.format("%d"), muted);

        var fromCurrent = remaining < 0 ?
            "DEPASSE " + ((-remaining) / 1000.0).format("%.1f") :
            "RESTE " + (remaining / 1000.0).format("%.1f");
        txt(dc, height * 0.697, Graphics.FONT_XTINY,
            fromCurrent + " km / PAUSE " +
            point.pauseMin.format("%d") + " min", muted);

        var barTime = point.cutoffMin == 65535 ?
            "BH --:--" : "BH " + targetClock(point.cutoffMin);
        txt(dc, height * 0.772, Graphics.FONT_SMALL,
            barTime + " / PLAN " +
            targetClock(_plan.officialStartMin + point.targetMin),
            white);

        txt(dc, height * 0.852, Graphics.FONT_XTINY,
            "MENU > RETOUR SUIVI", blue);
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

        // Read a watch-menu selection even when the activity is paused.
        applyDeviceSettings();
        if (_previewIndex >= 0) {
            drawPointPreview(dc, _previewIndex);
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
        var cutoffRemainingSec = 0;
        var margin = 0.0;
        var requiredPace = "--:--";
        var marginColor = muted;
        if (goal >= 0) {
            // Official cutoffs never move with a later personal start.
            var deadline = (_plan.points[goal].cutoffMin -
                _plan.officialStartMin - _delayMin) * 60;
            cutoffRemainingSec = deadline - _elapsedSec;
            margin = cutoffRemainingSec - neededSecondsUntil(goal);
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

        // Reserved font-aware rows. The old top-down layout placed the
        // name/BH too high in the narrow chord of the ROUND display:
        // screenshot showed only "..MO" and "BH.." while CP1 vanished.
        // Each row now has its own screen slot; the rest of the dashboard
        // and the footer cannot be displaced by a larger firmware font.
        // Spread the lower rows and make use of the unused space above
        // the header. On the 51 mm watch the old rows crowded margin,
        // pace and descent together despite a large unused lower margin.
        var headerY = (height * 0.105).toNumber();
        var nameY = (height * 0.182).toNumber();
        var numberY = (height * 0.283).toNumber();
        var progressY = (height * 0.457).toNumber();
        var marginY = (height * 0.494).toNumber();
        var paceTitleY = (height * 0.566).toNumber();
        var paceValueY = (height * 0.635).toNumber();
        var descentTitleY = (height * 0.731).toNumber();
        var descentValueY = (height * 0.790).toNumber();
        var footerY = (height * 0.869).toNumber();

        // TrailRace-style two-tier header: CP + services on one narrow
        // first row, then the checkpoint name (left) / official CUT (right)
        // on the wider second row. More room for up to five icons without
        // sacrificing the large primary metrics.
        var nameFont = fontForSlot(dc, Graphics.FONT_SMALL,
                                   numberY - nameY - 6);
        var paceFont = fontForSlot(dc, Graphics.FONT_MEDIUM,
                                   descentTitleY - paceValueY - 5);
        var secondaryFont = fontForSlot(dc, Graphics.FONT_SMALL,
                                        footerY - descentValueY - 5);
        var captionFont = Graphics.FONT_XTINY;

        var cpTitle = _index == _plan.points.size() - 1 ?
                      "FIN" : "CP" + (_index + 1).format("%d");
        drawCheckpointHeader(dc, headerY,
                             nameY - headerY - 5, cpTitle,
                             point.flags, white);

        // Separate fields fit within the actual round-screen chord.
        // Long names are allowed to use the smaller X-TINY Garmin font.
        var topColW = width * 0.42;
        roundColumnText(dc, width * 0.303, nameY, nameFont,
                        point.name, topColW, white);
        roundColumnText(dc, width * 0.715, nameY, nameFont,
                        goal >= 0 ? "CUT " + cutoffClock : "SANS CUT",
                        topColW, white);

        var colW = width * 0.39;
        // Keep both quantities visibly dominant, as on the reference
        // TrailRace+ screenshot. The compact units follow the last digit.
        var numHeight = progressY - numberY - 4;
        drawPrimaryMetric(dc, width * 0.287, numberY,
                          _atStation ?
                          formatDuration(restRemainingSec(_index)) :
                          (remainingM / 1000.0).format("%.2f"),
                          _atStation ? "" : "km", width * 0.425,
                          numHeight, _atStation ? amber : 0x80BFFF);

        var leftToCutoff = goal >= 0 ?
                           formatCountdown(cutoffRemainingSec) : "--:--";
        drawPrimaryMetric(dc, width * 0.718, numberY,
                          leftToCutoff, goal >= 0 ? "h" : "",
                          width * 0.425, numHeight,
                          cutoffRemainingSec < 0 ? red : 0x00E8C0);

        drawRoadbookBar(dc, progressY, routeM, remainingM, false);
        fittedText(dc, width / 2, marginY, captionFont,
                   goal >= 0 ? "MARGE " + signedMargin : "PAS DE BH",
                   width * 0.66, marginColor);

        fittedText(dc, width * 0.285, paceTitleY, captionFont,
                   "ALLURE", colW, muted);
        fittedText(dc, width * 0.715, paceTitleY, captionFont,
                   "REQUIS", colW, muted);
        fittedText(dc, width * 0.285, paceValueY, paceFont,
                   segmentPace(), colW, 0x80BFFF);
        fittedText(dc, width * 0.715, paceValueY, paceFont,
                   requiredPace, colW, marginColor);

        // D- is still an estimate distributed proportionally within the
        // current segment (until we export the full GPX elevation profile).
        var previousDown = _index == 0 ? 0 :
                           _plan.points[_index - 1].descentM;
        var begin = _index == 0 ? 0.0 :
                    _plan.points[_index - 1].distanceM;
        var length = point.distanceM - begin;
        var share = length > 0 ? remainingM / length : 0.0;
        if (share > 1.0) { share = 1.0; }
        var downRemaining =
            ((point.descentM - previousDown) * share).toNumber();

        fittedText(dc, width * 0.285, descentTitleY, captionFont,
                   "D- CP", colW, muted);
        fittedText(dc, width * 0.715, descentTitleY, captionFont,
                   "ARRIVEE", colW, muted);
        fittedText(dc, width * 0.285, descentValueY, secondaryFont,
                   downRemaining.format("%d") + " m", colW, 0x80BFFF);
        fittedText(dc, width * 0.715, descentValueY, secondaryFont,
                   finishClock, colW, accent);

        // The footer stays near the widest lower part of the round screen.
        // Do not shift it downward when Garmin changes font metrics.
        var clock = System.getClockTime();
        var nowText = clock.hour.format("%02d") + ":" +
                      clock.min.format("%02d");
        var raceHours = (_elapsedSec / 3600).toNumber();
        var raceMinutes = ((_elapsedSec % 3600) / 60).toNumber();
        var raceSeconds = (_elapsedSec % 60).toNumber();
        var elapsedText = raceHours.format("%d") + ":" +
                          raceMinutes.format("%02d") + ":" +
                          raceSeconds.format("%02d");
        fittedText(dc, width / 2, footerY, captionFont,
                   nowText + "  |  " + elapsedText,
                   width * 0.64, 0x80BFFF);
    }
}
