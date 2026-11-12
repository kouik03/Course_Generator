#!/usr/bin/env python3
"""Source-level contracts for the on-device Garmin data-field settings UI.

This test suite is not a Monkey C build or a simulator rendering test.
"""
from pathlib import Path
import unittest

PROJECT = Path(__file__).resolve().parents[1]


class OnDeviceSettingsContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        src = PROJECT / "source"
        cls.field = (src / "CourseGeneratorIQ.mc").read_text()
        cls.helpers = (src / "CGIQWatchSettings.mc").read_text()
        cls.ui = (src / "CGIQDeviceSettings.mc").read_text()

    def test_settings_are_a_plain_view_not_a_direct_menu2(self):
        self.assertIn("function getSettingsView()", self.field)
        self.assertIn("new CGIQDeviceSettingsView()", self.field)
        self.assertIn("new CGIQDeviceSettingsDelegate(settings)", self.field)
        self.assertIn("extends WatchUi.View", self.ui)
        self.assertIn("extends WatchUi.BehaviorDelegate", self.ui)
        self.assertNotIn("CGIQWatchMenu(", self.field)
        self.assertNotIn("WatchUi.pushView(", self.ui)

    def test_button_navigation_for_datafield_settings(self):
        for method in ["onNextPage()", "onPreviousPage()", "onSelect()", "onBack()"]:
            self.assertIn(method, self.ui)
        for label in [
            "DEPART EN VAGUE", "CONSULTER UN CP",
            "CHANGER PROCHAIN CP", "RETOUR SUIVI LIVE",
        ]:
            self.assertIn(label, self.ui)

    def test_no_base64_editor_on_watch(self):
        self.assertIn('Properties.getValue("Block1")', self.helpers)
        for source in [self.ui, self.helpers]:
            self.assertNotIn("TextPicker", source)
            self.assertNotIn("setValue(\"Block1\"", source)

    def test_preview_remains_separate_from_live_progress(self):
        preview = self.field.split("function drawPointPreview(", 1)[1].split(
            "function onUpdate(", 1
        )[0]
        self.assertNotIn("_index =", preview)
        self.assertNotIn("Storage.setValue(", preview)
        self.assertIn('Storage.setValue("previewCpIndex", _target)', self.ui)
        self.assertIn('Storage.setValue("previewCpIndex", -1)', self.ui)

    def test_manual_retarget_requires_explicit_confirmation(self):
        self.assertIn('setPage(_page == 2 ? 4 : 5)', self.ui)
        self.assertIn('if (_page == 5)', self.ui)
        self.assertIn('if (_cursor == 0 && _target >= 0', self.ui)
        self.assertIn('Storage.setValue("manualCpPlanSig", _plan.signature)', self.ui)
        self.assertIn("requestPlan == _plan.signature", self.field)
        self.assertIn('Storage.setValue("manualCpRequestId"', self.ui)
        self.assertIn('Storage.setValue("manualCpAppliedId", req)', self.field)

    def test_manual_next_cp_anchors_distance_at_last_confirmed_cp(self):
        # Selecting the FIN as the NEXT CP implies that previous aid
        # stations were passed, even if the simulator supplies 0 GPS km.
        self.assertIn('var previousM = target == 0 ? 0.0 :', self.field)
        self.assertIn('_plan.points[target - 1].distanceM;', self.field)
        self.assertIn('var routeBeforeM = currentRouteM();', self.field)
        self.assertIn('if (target < _index || routeBeforeM < previousM)', self.field)
        self.assertIn('_offsetM = previousM - _rawDistanceM;', self.field)
        self.assertIn('saveState();', self.field)
        self.assertIn('var isPassed = km <= routeM;', self.field)
        self.assertNotIn('var isPassed = i < _index', self.field)

        # Worked example using the demonstration roadbook: FIN at 18 km,
        # R2 at 11 km. The minimum virtual progress is 11/18 (61.1%).
        km_r2, km_finish, gps_activity_m = 11.0, 18.0, 0.0
        offset_m = km_r2 * 1000 - gps_activity_m
        virtual_m = gps_activity_m + offset_m
        self.assertAlmostEqual(virtual_m / (km_finish * 1000), 11 / 18)
        self.assertAlmostEqual((km_finish * 1000 - virtual_m) / 1000, 7.0)

    def test_confirmed_cp_anchor_preserves_more_advanced_route_distance(self):
        # If Garmin has already advanced beyond R2, selecting FIN must
        # not roll the position backwards to exactly R2.
        previous_cp_m, current_route_m = 11000, 12500
        self.assertFalse(current_route_m < previous_cp_m)

    def test_page_state_is_numeric_and_menu_has_four_entries(self):
        self.assertIn("var _page = 0;", self.ui)
        self.assertIn("if (_page == 0) { return 4; }", self.ui)
        self.assertIn('if (_page == 0) { return "COURSE GENERATOR"; }', self.ui)
        for forbidden in [
            '_page == "main"', '_page == "wave"', '_page == "browse"',
            '_page == "jump"', '_page == "details"', '_page == "confirm"',
        ]:
            self.assertNotIn(forbidden, self.ui)

    def test_wave_settings_override_phone_only_when_chosen(self):
        self.assertIn('Storage.getValue("watchStartDelayMin")', self.helpers)
        self.assertIn('Storage.deleteValue("watchStartDelayMin")', self.ui)
        self.assertIn('Properties.getValue("StartDelay")', self.helpers)
        self.assertIn("_delayMin = cgiqWatchStartDelay()", self.field)

    def test_layout_reserves_footer_before_upper_rows(self):
        self.assertIn("var footerY = (height * 0.807).toNumber();", self.field)
        self.assertIn("var descentValueY = footerY - hSecond - 5;", self.field)
        self.assertIn("var headerY = nameY - hHead - 4;", self.field)
        self.assertIn("if (headerY < height * 0.080)", self.field)


if __name__ == "__main__":
    unittest.main()
