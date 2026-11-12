import Toybox.Application;
import Toybox.WatchUi;
import Toybox.Graphics;
import Toybox.Activity;
import Toybox.Lang;

class CourseGeneratorIQApp extends Application.AppBase {
    function initialize() {
        AppBase.initialize();
    }

    function getInitialView() {
        return [ new CourseGeneratorIQField() ];
    }
}

class CourseGeneratorIQField extends WatchUi.DataField {
    var _elapsedMs = 0;
    var _distanceM = 0.0;

    function initialize() {
        DataField.initialize();
    }

    function compute(info as Activity.Info) as Void {
        if (info.timerTime != null) {
            _elapsedMs = info.timerTime;
        }
        if (info.distance != null) {
            _distanceM = info.distance;
        }
    }

    function onUpdate(dc as Graphics.Dc) as Void {
        var width = dc.getWidth();
        var height = dc.getHeight();
        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_BLACK);
        dc.clear();
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);

        var seconds = (_elapsedMs / 1000).toNumber();
        var hours = (seconds / 3600).toNumber();
        var minutes = ((seconds % 3600) / 60).toNumber();
        var clock = hours.format("%02d") + ":" + minutes.format("%02d");

        dc.drawText(width / 2, height * 0.18, Graphics.FONT_SMALL,
                    "COURSE GENERATOR IQ", Graphics.TEXT_JUSTIFY_CENTER);
        dc.drawText(width / 2, height * 0.38, Graphics.FONT_LARGE,
                    clock, Graphics.TEXT_JUSTIFY_CENTER);
        dc.drawText(width / 2, height * 0.60, Graphics.FONT_MEDIUM,
                    (_distanceM / 1000.0).format("%.2f") + " km",
                    Graphics.TEXT_JUSTIFY_CENTER);
        dc.drawText(width / 2, height * 0.82, Graphics.FONT_XTINY,
                    "SIMULATOR TEST", Graphics.TEXT_JUSTIFY_CENTER);
    }
}
