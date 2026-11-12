import Toybox.Application.Properties;
import Toybox.Application.Storage;
import Toybox.WatchUi;
import Toybox.Lang;

// Watch-side menus are entirely separate from the phone-side XML settings.
// In particular the three Base64 blocks are NEVER shown on the watch.
function cgiqLoadWatchPlan() {
    var p = new CGIQPlan();
    var first = Properties.getValue("Block1");
    var second = Properties.getValue("Block2");
    var third = Properties.getValue("Block3");
    var encoded = (first == null ? "" : first.toString()) +
                  (second == null ? "" : second.toString()) +
                  (third == null ? "" : third.toString());
    if (encoded.length() == 0) {
        p.demoPlan();
    } else {
        p.load(encoded);
    }
    return p;
}

function cgiqWatchStartDelay() {
    var override = Storage.getValue("watchStartDelayMin");
    if (override != null && override >= 0 && override <= 180) {
        return override;
    }
    var phone = Properties.getValue("StartDelay");
    return phone == null ? 0 : phone;
}

class CGIQWatchMenu extends WatchUi.Menu2 {
    function initialize(page, target) {
        Menu2.initialize({:title=>"Course Generator IQ"});
        var plan = cgiqLoadWatchPlan();

        if (page == "main") {
            addItem(new WatchUi.MenuItem("Depart en vague",
                "Choisir un retard de depart", "wave", null));
            if (plan.error.length() != 0) {
                addItem(new WatchUi.MenuItem("Roadbook invalide",
                    plan.error, "error", null));
                return;
            }
            var preview = Storage.getValue("previewCpIndex");
            addItem(new WatchUi.MenuItem("Consulter un CP",
                preview != null && preview >= 0 &&
                    preview < plan.points.size() ?
                    plan.points[preview].name : "Voir distances / D+ / D-",
                "preview", null));
            if (preview != null && preview >= 0) {
                addItem(new WatchUi.MenuItem("Retour suivi live",
                    "Arreter la consultation", "live", null));
            }
            var current = Storage.getValue("pointIndex");
            if (Storage.getValue("courseSig") != plan.signature) {
                current = 0;
            }
            if (current == null || current < 0 ||
                current >= plan.points.size()) {
                current = 0;
            }
            addItem(new WatchUi.MenuItem("Changer prochain CP",
                "Suivi actuel : " + plan.points[current].name,
                "jump", null));
        } else if (page == "wave") {
            addItem(new WatchUi.MenuItem("Reglage telephone",
                "Annuler le decalage montre", -10, null));
            var choices = [0, 5, 10, 15, 20, 30, 45, 60, 90, 120, 180];
            for (var w = 0; w < choices.size(); w++) {
                addItem(new WatchUi.MenuItem(
                    "+" + choices[w].format("%d") + " minutes",
                    choices[w] == cgiqWatchStartDelay() ? "Selection actuelle" : null,
                    choices[w], null));
            }
            addItem(new WatchUi.MenuItem("Ajouter 1 minute",
                "Ajuster un depart irregulier", -1, null));
            addItem(new WatchUi.MenuItem("Retirer 1 minute",
                "Ajuster un depart irregulier", -2, null));
            addItem(new WatchUi.MenuItem("Ajouter 5 minutes",
                "Ajustement rapide", -3, null));
            addItem(new WatchUi.MenuItem("Retirer 5 minutes",
                "Ajustement rapide", -4, null));
        } else if (page == "preview" || page == "jump") {
            if (plan.error.length() != 0) {
                addItem(new WatchUi.MenuItem("Roadbook invalide",
                    plan.error, -1, null));
                return;
            }
            for (var i = 0; i < plan.points.size(); i++) {
                var cp = plan.points[i];
                var name = (i == plan.points.size() - 1 ?
                    "FIN " : "CP" + (i + 1).format("%d") + " ") + cp.name;
                var details = (cp.distanceM / 1000.0).format("%.1f") +
                    " km / D+ " + cp.ascentM.format("%d");
                addItem(new WatchUi.MenuItem(name, details, i, null));
            }
        } else if (page == "confirm") {
            if (target < 0 || target >= plan.points.size()) {
                addItem(new WatchUi.MenuItem("Point invalide",
                    "Annuler", "cancel", null));
                return;
            }
            addItem(new WatchUi.MenuItem("Confirmer",
                "Prochain : " + plan.points[target].name, "apply", null));
            addItem(new WatchUi.MenuItem("Annuler",
                "Ne pas toucher au suivi", "cancel", null));
        }
    }
}

class CGIQWatchMenuDelegate extends WatchUi.Menu2InputDelegate {
    var _page = "main";
    var _target = -1;

    function initialize(page, target) {
        Menu2InputDelegate.initialize();
        _page = page;
        _target = target;
    }

    function next(page, target) {
        WatchUi.pushView(new CGIQWatchMenu(page, target),
            new CGIQWatchMenuDelegate(page, target),
            WatchUi.SLIDE_IMMEDIATE);
    }

    function onSelect(item as WatchUi.MenuItem) as Void {
        var id = item.getId();

        if (_page == "main") {
            if (id == "wave" || id == "preview" || id == "jump") {
                next(id, -1);
            } else if (id == "live") {
                Storage.setValue("previewCpIndex", -1);
                WatchUi.popView(WatchUi.SLIDE_IMMEDIATE);
            }
            return;
        }

        if (_page == "wave") {
            var choice = id;
            if (choice == -10) {
                Storage.deleteValue("watchStartDelayMin");
            } else {
                var value = cgiqWatchStartDelay();
                if (choice == -1) { value++; }
                else if (choice == -2) { value--; }
                else if (choice == -3) { value += 5; }
                else if (choice == -4) { value -= 5; }
                else if (choice >= 0) { value = choice; }
                if (value < 0) { value = 0; }
                if (value > 180) { value = 180; }
                Storage.setValue("watchStartDelayMin", value);
            }
            WatchUi.popView(WatchUi.SLIDE_IMMEDIATE);
            return;
        }

        if (_page == "preview") {
            var plan = cgiqLoadWatchPlan();
            if (plan.error.length() == 0 && id >= 0 &&
                id < plan.points.size()) {
                Storage.setValue("previewCpIndex", id);
                // Go back to the root settings menu.
                WatchUi.popView(WatchUi.SLIDE_IMMEDIATE);
            }
            return;
        }

        if (_page == "jump") {
            var currentPlan = cgiqLoadWatchPlan();
            if (currentPlan.error.length() == 0 && id >= 0 &&
                id < currentPlan.points.size()) {
                next("confirm", id);
            }
            return;
        }

        if (_page == "confirm") {
            if (id == "apply") {
                var validPlan = cgiqLoadWatchPlan();
                if (validPlan.error.length() == 0 && _target >= 0 &&
                    _target < validPlan.points.size()) {
                    // An event sequence prevents reapplying a manually
                    // selected CP every time the data field is opened.
                    Storage.setValue("manualCpIndex", _target);
                    Storage.setValue("manualCpPlanSig", validPlan.signature);
                    // Preserve an explicit pre-start selection through the
                    // first onTimerStart(), which otherwise resets a new run.
                    Storage.setValue("manualCpKeepAtStart", true);
                    var seq = Storage.getValue("manualCpRequestId");
                    if (seq == null) { seq = 0; }
                    Storage.setValue("manualCpRequestId", seq + 1);
                    Storage.setValue("previewCpIndex", -1);
                }
            }
            WatchUi.popView(WatchUi.SLIDE_IMMEDIATE);
        }
    }
}
