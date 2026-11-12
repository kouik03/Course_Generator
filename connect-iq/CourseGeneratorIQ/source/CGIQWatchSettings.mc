import Toybox.Application.Properties;
import Toybox.Application.Storage;
import Toybox.WatchUi;
import Toybox.Lang;
import Toybox.System;

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

// Both menu engines call this after a confirmed edit or "Return to live".
// The settings view is system-owned, not the data field's ordinary View.
function cgiqCloseSettings() {
    try {
        WatchUi.popView(WatchUi.SLIDE_IMMEDIATE);
        return true;
    } catch (e) {
        // Garmin may restrict popView on a given firmware. In that case,
        // do NOT call System.exit() (which would terminate the data field).
        // Leave the view in place for a platform-level Back action and
        // log the actual exception so simulator/real hardware can be
        // compared without guessing.
        System.println("CGIQ settings: popView unavailable");
        return false;
    }
}

// Shared by the custom simulator View and native real-device Menu2.
