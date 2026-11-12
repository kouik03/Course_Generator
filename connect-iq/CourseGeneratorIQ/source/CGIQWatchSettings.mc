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

// The device UI is now implemented in CGIQDeviceSettings.mc as a plain
// WatchUi.View plus BehaviorDelegate. These helpers are shared with the
// activity data field and the on-watch configuration view.
