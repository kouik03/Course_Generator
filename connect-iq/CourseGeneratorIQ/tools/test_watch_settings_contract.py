#!/usr/bin/env python3
"""Source-level safety contracts for Garmin on-device settings.

These tests are NOT a Monkey C compilation; compile with the Garmin SDK
before testing on hardware.
"""
from pathlib import Path
import unittest

PROJECT = Path(__file__).resolve().parents[1]


class OnDeviceSettingsContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.field = (PROJECT / "source" / "CourseGeneratorIQ.mc").read_text()
        cls.menu = (PROJECT / "source" / "CGIQWatchSettings.mc").read_text()

    def test_watch_menu_is_a_separate_garmin_settings_flow(self):
        self.assertIn("function getSettingsView()", self.field)
        self.assertIn('new CGIQWatchMenu("main", -1)', self.field)
        self.assertIn("extends WatchUi.Menu2", self.menu)
        self.assertIn("extends WatchUi.Menu2InputDelegate", self.menu)

    def test_base64_values_are_never_menu_items_on_the_watch(self):
        # The menu necessarily READS Block1/2/3 to find CP names. It must
        # never expose those long strings as an on-device editor.
        self.assertIn('Properties.getValue("Block1")', self.menu)
        self.assertNotIn('new WatchUi.MenuItem("Bloc 1"', self.menu)
        self.assertNotIn('new WatchUi.MenuItem("Block1"', self.menu)
        self.assertNotIn("TextPicker", self.menu)

    def test_browsing_does_not_mutate_the_real_progress(self):
        preview = self.field.split("function drawPointPreview(", 1)[1].split(
            "function onUpdate(", 1
        )[0]
        self.assertNotIn("_index =", preview)
        self.assertNotIn("Storage.setValue(", preview)
        self.assertIn('Storage.setValue("previewCpIndex", id)', self.menu)
        self.assertIn('Storage.setValue("previewCpIndex", -1)', self.menu)

    def test_manual_retarget_requires_confirmation_and_plan_identity(self):
        self.assertIn('next("confirm", id)', self.menu)
        self.assertIn('if (id == "apply")', self.menu)
        self.assertIn('Storage.setValue("manualCpPlanSig", validPlan.signature)', self.menu)
        self.assertIn("requestPlan == _plan.signature", self.field)
        self.assertIn('Storage.setValue("manualCpRequestId"', self.menu)
        self.assertIn('Storage.setValue("manualCpAppliedId", req)', self.field)

    def test_wave_delay_override_and_phone_fallback(self):
        self.assertIn('Storage.getValue("watchStartDelayMin")', self.menu)
        self.assertIn('Storage.deleteValue("watchStartDelayMin")', self.menu)
        self.assertIn('Properties.getValue("StartDelay")', self.menu)
        self.assertIn("_delayMin = cgiqWatchStartDelay()", self.field)


if __name__ == "__main__":
    unittest.main()
