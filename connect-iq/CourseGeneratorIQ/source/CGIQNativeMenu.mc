import Toybox.WatchUi;
import Toybox.Application.Storage;
import Toybox.Lang;

// Native Garmin Menu2 used in real-device builds (and optionally testable
// in the simulator). This is a SINGLE system-managed Menu2: its items are
// replaced in place when changing pages. No pushView is used from a data
// field's settings context, avoiding unsupported nested view stacks.
//
// Numeric page IDs: 0=root, 1=wave, 2=inspect, 3=choose,
//                   4=CP detail, 5=confirm.
class CGIQNativeMenu extends WatchUi.Menu2 {
    var _page = 0;
    var _target = 0;
    var _count = 0;
    var _initializing = false;
    var _plan = null;
    var _waves = [0, 5, 10, 15, 20, 30, 45, 60, 90, 120, 180, -1, -2, -3, -4, -10];

    function initialize() {
        Menu2.initialize({:title=>"MENU2 NATIF"});
        _plan = cgiqLoadWatchPlan();
        _initializing = true;
        showPage(0, 0);
        _initializing = false;
    }

    function add(label, sub, id) {
        addItem(new WatchUi.MenuItem(label, sub, id, null));
        _count++;
    }

    function showPage(page, target) {
        while (_count > 0) {
            deleteItem(0);
            _count--;
        }
        _page = page;
        _target = target;
        if (_page == 0) {
            setTitle("MENU2 NATIF");
            add("Depart en vague", "+" +
                cgiqWatchStartDelay().format("%d") + " min", 100);
            add("Consulter CP", "Lecture seule", 101);
            add("Choisir CP suivant", "Modifier le suivi", 102);
            add("Retour au suivi", "Fermer les reglages", 103);
        } else if (_page == 1) {
            setTitle("Depart en vague");
            for (var i = 0; i < _waves.size(); i++) {
                var amount = _waves[i];
                var label = amount == -10 ? "Reglage telephone" :
                            (amount == -1 ? "+1 minute" :
                            (amount == -2 ? "-1 minute" :
                            (amount == -3 ? "+5 minutes" :
                            (amount == -4 ? "-5 minutes" :
                            "+" + amount.format("%d") + " min"))));
                add(label, amount == cgiqWatchStartDelay() ?
                    "Actuel" : null, 1000 + i);
            }
        } else if (_page == 2 || _page == 3) {
            setTitle(_page == 2 ? "Consulter CP" : "CP suivant");
            if (_plan.error.length() != 0) {
                add("Roadbook invalide", _plan.error, 999);
            } else {
                for (var j = 0; j < _plan.points.size(); j++) {
                    var cp = _plan.points[j];
                    add(cp.name, (cp.distanceM / 1000.0).format("%.1f") +
                        " km / D+ " + cp.ascentM.format("%d"),
                        (_page == 2 ? 2000 : 3000) + j);
                }
            }
        } else if (_page == 4) {
            setTitle(_plan.points[_target].name);
            var cp = _plan.points[_target];
            var pDistance = _target == 0 ? 0.0 :
                            _plan.points[_target - 1].distanceM;
            var pUp = _target == 0 ? 0 :
                      _plan.points[_target - 1].ascentM;
            var pDown = _target == 0 ? 0 :
                        _plan.points[_target - 1].descentM;
            add("Distance", (cp.distanceM / 1000.0).format("%.2f") +
                " km cumules", 4001);
            add("Troncon", ((cp.distanceM - pDistance) / 1000.0).format("%.2f") +
                " km", 4001);
            add("Denivele", "D+ " + (cp.ascentM - pUp).format("%d") +
                " / D- " + (cp.descentM - pDown).format("%d") + " m", 4001);
            add("Pause", cp.pauseMin.format("%d") + " min", 4001);
            add("Consulter ce CP", "Retour a l'ecran du champ", 4000);
            add("Retour aux CP", null, 4002);
        } else if (_page == 5) {
            setTitle("Prochain : " + _plan.points[_target].name);
            add("Confirmer", "Recaler la cible", 5000);
            add("Annuler", "Conserver le suivi", 5001);
        }
        if (_count > 0) { setFocus(0); }
        // The initial menu is constructed before getSettingsView returns:
        // requesting a redraw before Garmin displays it can be invalid.
        if (!_initializing) {
            WatchUi.requestUpdate();
        }
    }
}

class CGIQNativeMenuDelegate extends WatchUi.Menu2InputDelegate {
    var _menu = null;

    function initialize(menu) {
        Menu2InputDelegate.initialize();
        _menu = menu;
    }

    function onBack() as Void {
        if (_menu._page == 0) {
            cgiqCloseSettings();
        } else if (_menu._page == 4) {
            _menu.showPage(2, 0);
        } else if (_menu._page == 5) {
            _menu.showPage(3, 0);
        } else {
            _menu.showPage(0, 0);
        }
    }

    function onSelect(item as WatchUi.MenuItem) as Void {
        var id = item.getId();
        if (!(id instanceof Lang.Number)) { return; }
        var value = id as Lang.Number;

        if (_menu._page == 0) {
            if (value == 100) { _menu.showPage(1, 0); }
            else if (value == 101) { _menu.showPage(2, 0); }
            else if (value == 102) { _menu.showPage(3, 0); }
            else if (value == 103) {
                Storage.setValue("previewCpIndex", -1);
                cgiqCloseSettings();
            }
            return;
        }
        if (_menu._page == 1 && value >= 1000 && value < 1000 + _menu._waves.size()) {
            var selected = _menu._waves[value - 1000];
            if (selected == -10) {
                Storage.deleteValue("watchStartDelayMin");
            } else {
                var delay = cgiqWatchStartDelay();
                if (selected == -1) { delay++; }
                else if (selected == -2) { delay--; }
                else if (selected == -3) { delay += 5; }
                else if (selected == -4) { delay -= 5; }
                else { delay = selected; }
                if (delay < 0) { delay = 0; }
                if (delay > 180) { delay = 180; }
                Storage.setValue("watchStartDelayMin", delay);
            }
            _menu.showPage(0, 0);
            return;
        }
        if (_menu._page == 2 && value >= 2000 &&
            value < 2000 + _menu._plan.points.size()) {
            _menu.showPage(4, value - 2000);
            return;
        }
        if (_menu._page == 3 && value >= 3000 &&
            value < 3000 + _menu._plan.points.size()) {
            _menu.showPage(5, value - 3000);
            return;
        }
        if (_menu._page == 4) {
            if (value == 4000) {
                Storage.setValue("previewCpIndex", _menu._target);
                cgiqCloseSettings();
            } else if (value == 4002) {
                _menu.showPage(2, 0);
            }
            return;
        }
        if (_menu._page == 5) {
            if (value == 5000) {
                Storage.setValue("manualCpIndex", _menu._target);
                Storage.setValue("manualCpPlanSig", _menu._plan.signature);
                Storage.setValue("manualCpKeepAtStart", true);
                var previous = Storage.getValue("manualCpRequestId");
                var sequence = 0;
                if (previous instanceof Lang.Number) {
                    sequence = previous;
                }
                Storage.setValue("manualCpRequestId", sequence + 1);
                Storage.setValue("previewCpIndex", -1);
                cgiqCloseSettings();
            } else if (value == 5001) {
                _menu.showPage(3, 0);
            }
        }
    }
}
