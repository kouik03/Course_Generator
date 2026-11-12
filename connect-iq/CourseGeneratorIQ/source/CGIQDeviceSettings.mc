import Toybox.WatchUi;
import Toybox.Graphics;
import Toybox.Lang;
import Toybox.Application.Storage;
import Toybox.System;

// Device-settings entry uses a plain WatchUi.View because returning Menu2
// directly from a data field's getSettingsView() can render black/empty in
// Garmin Connect IQ Simulator. No pushView or popView is used here.
//
// Navigation: UP/DOWN chooses a line; START selects; BACK goes to main,
// and BACK again exits the system's settings mode.
class CGIQDeviceSettingsView extends WatchUi.View {
    var _page = "main";
    var _cursor = 0;
    var _target = 0;
    var _plan = null;
    var _waveChoices = [0, 5, 10, 15, 20, 30, 45, 60, 90, 120, 180, -1, -2, -3, -4, -10];

    function initialize() {
        View.initialize();
        _plan = cgiqLoadWatchPlan();
    }

    function itemCount() {
        if (_page == "main") { return 4; }
        if (_page == "wave") { return _waveChoices.size(); }
        if (_page == "browse" || _page == "jump") {
            return _plan.error.length() == 0 ? _plan.points.size() : 0;
        }
        if (_page == "confirm") { return 2; }
        if (_page == "details") { return 1; }
        return 0;
    }

    function move(delta) {
        var count = itemCount();
        if (count <= 0) { return; }
        if (_page == "details") {
            _target += delta;
            if (_target < 0) { _target = 0; }
            if (_target >= _plan.points.size()) {
                _target = _plan.points.size() - 1;
            }
        } else {
            _cursor += delta;
            if (_cursor < 0) { _cursor = 0; }
            if (_cursor >= count) { _cursor = count - 1; }
        }
        WatchUi.requestUpdate();
    }

    function setPage(page) {
        _page = page;
        _cursor = 0;
        WatchUi.requestUpdate();
    }

    function goBack() {
        if (_page == "main") {
            return false;  // Let Garmin close the settings view.
        }
        if (_page == "details") {
            _page = "browse";
            _cursor = _target;
        } else if (_page == "confirm") {
            _page = "jump";
            _cursor = _target;
        } else {
            _page = "main";
            _cursor = 0;
        }
        WatchUi.requestUpdate();
        return true;
    }

    function select() {
        if (_page == "main") {
            if (_cursor == 0) {
                setPage("wave");
            } else if (_cursor == 1) {
                setPage("browse");
            } else if (_cursor == 2) {
                setPage("jump");
            } else {
                Storage.setValue("previewCpIndex", -1);
                _plan = cgiqLoadWatchPlan();
                WatchUi.requestUpdate();
            }
            return;
        }
        if (_page == "wave") {
            var selected = _waveChoices[_cursor];
            var current = cgiqWatchStartDelay();
            if (selected == -10) {
                Storage.deleteValue("watchStartDelayMin");
            } else {
                if (selected == -1) { current += 1; }
                else if (selected == -2) { current -= 1; }
                else if (selected == -3) { current += 5; }
                else if (selected == -4) { current -= 5; }
                else { current = selected; }
                if (current < 0) { current = 0; }
                if (current > 180) { current = 180; }
                Storage.setValue("watchStartDelayMin", current);
            }
            setPage("main");
            return;
        }
        if (_page == "browse" || _page == "jump") {
            if (_plan.error.length() != 0 ||
                _cursor < 0 || _cursor >= _plan.points.size()) {
                return;
            }
            _target = _cursor;
            setPage(_page == "browse" ? "details" : "confirm");
            return;
        }
        if (_page == "details") {
            // Browse only; the actual following CP and its timer stay intact.
            Storage.setValue("previewCpIndex", _target);
            setPage("main");
            return;
        }
        if (_page == "confirm") {
            if (_cursor == 0 && _target >= 0 &&
                _target < _plan.points.size() &&
                _plan.error.length() == 0) {
                Storage.setValue("manualCpIndex", _target);
                Storage.setValue("manualCpPlanSig", _plan.signature);
                Storage.setValue("manualCpKeepAtStart", true);
                var previous = Storage.getValue("manualCpRequestId");
                var sequence = 0;
                if (previous instanceof Lang.Number) {
                    sequence = previous;
                }
                Storage.setValue("manualCpRequestId", sequence + 1);
                Storage.setValue("previewCpIndex", -1);
            }
            setPage("main");
        }
    }

    function textFit(dc, centerX, y, font, label, maxW, color) {
        var chosen = font;
        if (dc.getTextWidthInPixels(label, chosen) > maxW) {
            chosen = Graphics.FONT_XTINY;
        }
        var value = label;
        while (value.length() > 0 &&
               dc.getTextWidthInPixels(value, chosen) > maxW) {
            value = value.substring(0, value.length() - 1);
        }
        dc.setColor(color, Graphics.COLOR_TRANSPARENT);
        dc.drawText(centerX, y, chosen, value, Graphics.TEXT_JUSTIFY_CENTER);
    }

    function menuName(index) {
        if (_page == "main") {
            if (index == 0) { return "DEPART EN VAGUE"; }
            if (index == 1) { return "CONSULTER UN CP"; }
            if (index == 2) { return "CHANGER PROCHAIN CP"; }
            return "RETOUR SUIVI LIVE";
        }
        if (_page == "wave") {
            var amount = _waveChoices[index];
            if (amount == -10) { return "REGLAGE TELEPHONE"; }
            if (amount == -1) { return "AJOUTER 1 MIN"; }
            if (amount == -2) { return "RETIRER 1 MIN"; }
            if (amount == -3) { return "AJOUTER 5 MIN"; }
            if (amount == -4) { return "RETIRER 5 MIN"; }
            return "+" + amount.format("%d") + " MINUTES";
        }
        if (_page == "browse" || _page == "jump") {
            var cp = _plan.points[index];
            return (index == _plan.points.size() - 1 ?
                    "FIN " : "CP" + (index + 1).format("%d") + " ") + cp.name;
        }
        if (_page == "confirm") {
            return index == 0 ? "CONFIRMER LE CP" : "ANNULER";
        }
        return "";
    }

    function screenHeading() {
        if (_page == "main") { return "COURSE GENERATOR"; }
        if (_page == "wave") { return "DEPART EN VAGUE"; }
        if (_page == "browse") { return "CONSULTER CP"; }
        if (_page == "jump") { return "PROCHAIN CP"; }
        if (_page == "details") { return "DETAILS CP"; }
        if (_page == "confirm") { return "CONFIRMER CP"; }
        return "REGLAGES";
    }

    function onUpdate(dc as Graphics.Dc) as Void {
        var w = dc.getWidth();
        var h = dc.getHeight();
        var cyan = 0x59C8FF;
        var muted = 0xAAB7C3;
        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_BLACK);
        dc.clear();

        textFit(dc, w / 2, h * 0.115, Graphics.FONT_SMALL,
                screenHeading(), w * 0.72, cyan);

        if (_page == "details" && _plan.error.length() == 0) {
            var cp = _plan.points[_target];
            var beforeKm = _target == 0 ? 0.0 :
                           _plan.points[_target - 1].distanceM;
            var beforeUp = _target == 0 ? 0 :
                           _plan.points[_target - 1].ascentM;
            var beforeDown = _target == 0 ? 0 :
                             _plan.points[_target - 1].descentM;
            textFit(dc, w / 2, h * 0.235, Graphics.FONT_MEDIUM,
                    cp.name, w * 0.70, Graphics.COLOR_WHITE);
            textFit(dc, w / 2, h * 0.350, Graphics.FONT_SMALL,
                    (cp.distanceM / 1000.0).format("%.2f") +
                    " km cumules", w * 0.80, cyan);
            textFit(dc, w / 2, h * 0.455, Graphics.FONT_SMALL,
                    "Troncon " + ((cp.distanceM - beforeKm) / 1000.0).format("%.2f") + " km",
                    w * 0.80, muted);
            textFit(dc, w / 2, h * 0.555, Graphics.FONT_SMALL,
                    "D+ " + (cp.ascentM - beforeUp).format("%d") +
                    " / D- " + (cp.descentM - beforeDown).format("%d") + " m",
                    w * 0.80, cyan);
            textFit(dc, w / 2, h * 0.665, Graphics.FONT_XTINY,
                    "Pause " + cp.pauseMin.format("%d") + " min  /  " +
                    (cp.cutoffMin == 65535 ? "sans BH" :
                    "BH " + (cp.cutoffMin / 60).toNumber().format("%02d") +
                    ":" + (cp.cutoffMin % 60).format("%02d")),
                    w * 0.76, muted);
            textFit(dc, w / 2, h * 0.797, Graphics.FONT_XTINY,
                    "HAUT/BAS: CP    OK: VOIR", w * 0.72, cyan);
            return;
        }

        if (_plan.error.length() != 0 && _page != "wave") {
            textFit(dc, w / 2, h * 0.38, Graphics.FONT_SMALL,
                    "ROADBOOK: " + _plan.error, w * 0.73,
                    Graphics.COLOR_RED);
            textFit(dc, w / 2, h * 0.55, Graphics.FONT_XTINY,
                    "Retour et verifier les blocs", w * 0.72, muted);
            return;
        }

        var count = itemCount();
        // Present 4 rows at once and scroll the source list if necessary.
        var start = _cursor - 1;
        if (start < 0) { start = 0; }
        if (start + 4 > count) {
            start = count - 4;
            if (start < 0) { start = 0; }
        }
        for (var pos = 0; pos < 4; pos++) {
            var item = start + pos;
            if (item >= count) { break; }
            var y = (h * (0.275 + pos * 0.135)).toNumber();
            if (item == _cursor) {
                dc.setColor(0x17405A, Graphics.COLOR_TRANSPARENT);
                dc.fillRoundedRectangle((w * 0.13).toNumber(),
                    y - 7, (w * 0.74).toNumber(),
                    dc.getFontHeight(Graphics.FONT_SMALL) + 13, 5);
            }
            textFit(dc, w / 2, y, Graphics.FONT_SMALL,
                    menuName(item), w * 0.69,
                    item == _cursor ? Graphics.COLOR_WHITE : muted);
        }
        var footer = "HAUT/BAS  OK  RETOUR";
        if (_page == "wave") {
            footer = "DECALAGE ACTUEL +" +
                     cgiqWatchStartDelay().format("%d") + " MIN";
        } else if (_page == "confirm") {
            footer = "CP" + (_target + 1).format("%d") +
                     " : CONFIRMER OU ANNULER";
        } else if (_page == "main") {
            footer = "CP LIVE / PARAMETRES MONTRE";
        }
        textFit(dc, w / 2, h * 0.842, Graphics.FONT_XTINY,
                footer, w * 0.73, cyan);
    }
}

class CGIQDeviceSettingsDelegate extends WatchUi.BehaviorDelegate {
    var _view = null;

    function initialize(view) {
        BehaviorDelegate.initialize();
        _view = view;
    }

    function onNextPage() as Lang.Boolean {
        _view.move(1);
        return true;
    }

    function onPreviousPage() as Lang.Boolean {
        _view.move(-1);
        return true;
    }

    function onSelect() as Lang.Boolean {
        _view.select();
        return true;
    }

    function onBack() as Lang.Boolean {
        return _view.goBack();
    }
}
