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
        self.assertIn('setPage(_page == "browse" ? "details" : "confirm")', self.ui)
        self.assertIn('if (_page == "confirm")', self.ui)
        self.assertIn('if (_cursor == 0 && _target >= 0', self.ui)
        self.assertIn('Storage.setValue("manualCpPlanSig", _plan.signature)', self.ui)
        self.assertIn("requestPlan == _plan.signature", self.field)
        self.assertIn('Storage.setValue("manualCpRequestId"', self.ui)
        self.assertIn('Storage.setValue("manualCpAppliedId", req)', self.field)

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
