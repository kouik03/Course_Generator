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

    function txt(dc, y, font, value, color) {
        dc.setColor(color, Graphics.COLOR_TRANSPARENT);
        dc.drawText(dc.getWidth() / 2, y, font, value,
                    Graphics.TEXT_JUSTIFY_CENTER);
    }

    function onUpdate(dc as Graphics.Dc) as Void {
        var width = dc.getWidth();
        var height = dc.getHeight();
        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_BLACK);
        dc.clear();
        var compact = height < 185 || width < 185;
        var tiny = Graphics.FONT_XTINY;
        var small = Graphics.FONT_SMALL;
        var medium = Graphics.FONT_MEDIUM;
        var large = Graphics.FONT_LARGE;
        var white = Graphics.COLOR_WHITE;
        var pale = Graphics.COLOR_LT_GRAY;
        var aqua = Graphics.COLOR_BLUE;
        var gold = Graphics.COLOR_YELLOW;
        var red = Graphics.COLOR_RED;
        var green = Graphics.COLOR_GREEN;

        if (_plan == null || _plan.error.length() != 0) {
            txt(dc, height * 0.14, tiny, "CG IQ / DIAGNOSTIC", pale);
            txt(dc, height * 0.28, small,
                _plan == null ? "AUCUN PLAN" : _plan.error, red);
            var detail = "AUCUN CARACTERE DETECTE";
            if (_plan != null && _plan.errorPos > 0) {
                var index = _plan.errorPos;
                var block = 1;
                if (index > _b1len) {
                    index -= _b1len;
                    block = 2;
                    if (index > _b2len) {
                        index -= _b2len;
                        block = 3;
                    }
                }
                detail = "BLOC " + block.format("%d") +
                    "  POS " + index.format("%d");
            }
            txt(dc, height * 0.43, small, detail, white);
            if (_plan != null && _plan.errorCode >= 0) {
                txt(dc, height * 0.53, small,
                    "CHAR U+" + _plan.errorCode.format("%04X"), gold);
            }
            txt(dc, height * 0.66, tiny,
                "LONGUEURS " + _b1len.format("%d") +
                "/" + _b2len.format("%d") +
                "/" + _b3len.format("%d"), pale);
            txt(dc, height * 0.78, tiny, _decoderTest, aqua);
            return;
        }

        var title = _plan.demo ? "CG IQ / DEMO" : "COURSE GENERATOR IQ";
        if (_index >= _plan.points.size()) {
            txt(dc, height * 0.19, small, title, aqua);
            txt(dc, height * 0.42, large, "ARRIVEE", green);
            txt(dc, height * 0.64, medium,
                (_rawDistanceM / 1000.0).format("%.2f") + " km", white);
            return;
        }

        var point = _plan.points[_index];
        var routeM = currentRouteM();
        var remainingM = point.distanceM - routeM;
        if (remainingM < 0) { remainingM = 0.0; }

        var goal = findNextCutoff();
        var margin = 0.0;
        var cutoffClock = "--:--";
        var estimatedNeeded = 0.0;
        var requiredPace = "--:--";
        var marginColor = pale;
        if (goal >= 0) {
            var deadlineSec = (_plan.points[goal].cutoffMin -
                _plan.officialStartMin - _delayMin) * 60;
            estimatedNeeded = neededSecondsUntil(goal);
            margin = deadlineSec - _elapsedSec - estimatedNeeded;
            cutoffClock = targetClock(_plan.points[goal].cutoffMin);
            marginColor = margin < 0 ? red : (margin < 900 ? gold : green);

            var availableMove = deadlineSec - _elapsedSec -
                remainingRestUntil(goal);
            var goalRemainingM = _plan.points[goal].distanceM - routeM;
            if (availableMove > 60 && goalRemainingM > 10) {
                requiredPace = formatPace(
                    availableMove / 60.0 / (goalRemainingM / 1000.0));
            }
        }

        var prefix = (_index == _plan.points.size() - 1) ? "FIN" :
            "R" + (_index + 1).format("%d");
        var name = point.name;
        if (name.length() > (compact ? 11 : 17)) {
            name = name.substring(0, compact ? 11 : 17);
        }
        var label = prefix + " " + name;
        var etaSec = _elapsedSec + remainingLegSec();
        var etaClock = targetClock(_plan.officialStartMin +
            _delayMin + (etaSec / 60).toNumber());

        if (compact) {
            txt(dc, height * 0.03, tiny, title, aqua);
            txt(dc, height * 0.25, medium, label, white);
            if (_atStation) {
                var waitSec = restRemainingSec(_index);
                txt(dc, height * 0.51, large,
                    "PAUSE " + formatDuration(waitSec), gold);
            } else {
                txt(dc, height * 0.50, large,
                    (remainingM / 1000.0).format("%.1f") + " km", white);
            }
            txt(dc, height * 0.77, small,
                goal < 0 ? "SANS BH" : "BH " + cutoffClock +
                "  " + (margin < 0 ? "-" : "+") + formatDuration(margin),
                marginColor);
            return;
        }

        txt(dc, height * 0.075, tiny, title, aqua);
        txt(dc, height * 0.190, medium, label, white);

        if (_atStation) {
            txt(dc, height * 0.32, small, "RAVITAILLEMENT", gold);
            var left = restRemainingSec(_index);
            txt(dc, height * 0.42, large,
                left > 0 ? formatDuration(left) : "DEPART !",
                left > 0 ? white : gold);
            txt(dc, height * 0.59, small, "LAP = DEPART", pale);
        } else {
            txt(dc, height * 0.32, small, "DISTANCE RESTANTE", pale);
            txt(dc, height * 0.42, large,
                (remainingM / 1000.0).format("%.2f") + " km", white);
            txt(dc, height * 0.59, small, "ETA " + etaClock +
                "  |  CIBLE " + targetClock(
                    _plan.officialStartMin + _delayMin + point.targetMin),
                pale);
        }

        var bhText = goal >= 0 ? "BH " + cutoffClock :
            "AUCUNE BARRIERE";
        txt(dc, height * 0.680, small, bhText, pale);
        txt(dc, height * 0.757, medium,
            goal >= 0 ? "MARGE " +
                (margin < 0 ? "-" : "+") + formatDuration(margin) :
                "MARGE --",
            marginColor);

        var previousUp = _index == 0 ? 0 : _plan.points[_index - 1].ascentM;
        var legLengthM = point.distanceM -
            (_index == 0 ? 0 : _plan.points[_index - 1].distanceM);
        var remainPart = legLengthM > 0 ?
            remainingM / legLengthM : 0.0;
        if (remainPart > 1.0) { remainPart = 1.0; }
        var upRemaining = ((point.ascentM - previousUp) * remainPart).toNumber();
        txt(dc, height * 0.864, tiny,
            "D+ ~" + upRemaining.format("%d") +
            "m  |  REQUIS " + requiredPace + "/km", pale);
    }
}
