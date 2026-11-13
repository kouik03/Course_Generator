#!/usr/bin/env python3
"""Source-level safety checks for Course Generator IQ (native Garmin Menu2).

These tests do NOT compile Monkey C or validate rendered simulator pixels.
"""
from pathlib import Path
import re
import unittest

PROJECT = Path(__file__).resolve().parents[1]


class OnWatchContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        source = PROJECT / "source"
        cls.field = (source / "CourseGeneratorIQ.mc").read_text()
        cls.native = (source / "CGIQNativeMenu.mc").read_text()
        cls.helpers = (source / "CGIQWatchSettings.mc").read_text()
        cls.jungle = (PROJECT / "monkey.jungle").read_text()
        cls.runner = (PROJECT / "run-cqiq.sh").read_text()

    def test_only_native_menu2_remains(self):
        self.assertEqual(self.field.count("function getSettingsView()"), 1)
        self.assertIn("new CGIQNativeMenu()", self.field)
        self.assertIn("new CGIQNativeMenuDelegate(menu)", self.field)
        self.assertIn("extends WatchUi.Menu2", self.native)
        self.assertIn("extends WatchUi.Menu2InputDelegate", self.native)
        self.assertIn('setTitle("COURSE GENERATOR IQ");', self.native)
        self.assertNotIn("CGIQDeviceSettingsView", self.field)
        self.assertNotIn("CGIQDeviceSettingsDelegate", self.field)
        self.assertNotIn("cgiqSimulatorSettings", self.field)
        self.assertNotIn("cgiqNativeSettings", self.field)
        self.assertFalse((PROJECT / "source" / "CGIQDeviceSettings.mc").exists())
        self.assertFalse((PROJECT / "monkey.native.jungle").exists())
        self.assertNotIn("base.excludeAnnotations", self.jungle)

    def test_two_independent_build_slots_share_same_native_menu(self):
        self.assertIn('BUILD_SLOT="${1:-main}"', self.runner)
        self.assertIn("main|alternate)", self.runner)
        self.assertIn('JUNGLE="$PROJECT/monkey.jungle"', self.runner)
        self.assertIn('CourseGeneratorIQ-${BUILD_SLOT}.prg', self.runner)
        self.assertIn('-f "$JUNGLE"', self.runner)
        self.assertNotIn("monkey.native.jungle", self.runner)
        self.assertNotIn("MENU_MODE", self.runner)

    def test_launcher_reexecs_after_git_pull_before_using_build_paths(self):
        # A sync can delete an old Jungle file while the original Bash
        # process still holds stale variables. Re-read the updated script.
        self.assertIn('if [[ "${CGIQ_POST_PULL:-0}" != "1" ]]; then',
                      self.runner)
        self.assertIn('exec env CGIQ_POST_PULL=1 bash "$SCRIPT_DIR/run-cqiq.sh" "$@"',
                      self.runner)
        self.assertLess(
            self.runner.index('git -C "$REPO_ROOT" pull --ff-only'),
            self.runner.index('exec env CGIQ_POST_PULL=1 bash'),
        )
        self.assertLess(
            self.runner.index('exec env CGIQ_POST_PULL=1 bash'),
            self.runner.index('[[ -f "$JUNGLE" ]]'),
        )

    def test_base64_input_is_phone_only(self):
        self.assertIn('Properties.getValue("Block1")', self.helpers)
        self.assertIn('Properties.getValue("Block2")', self.helpers)
        self.assertIn('Properties.getValue("Block3")', self.helpers)
        self.assertNotIn("TextPicker", self.native)
        self.assertNotIn('setValue("Block1"', self.native)

    def test_native_menu_has_wave_preview_and_confirmed_manual_cp(self):
        for label in (
            '"Depart en vague"',
            '"Consulter CP"',
            '"Choisir CP suivant"',
            '"Retour au suivi"',
        ):
            self.assertIn(label, self.native)
        self.assertIn('add("Confirmer", "Recaler la cible", 5000)', self.native)
        self.assertIn('Storage.setValue("manualCpPlanSig", _menu._plan.signature)',
                      self.native)
        self.assertIn('Storage.setValue("manualCpRequestId", sequence + 1)',
                      self.native)
        self.assertIn('Storage.setValue("previewCpIndex", _menu._target)',
                      self.native)
        self.assertIn('Storage.setValue("previewCpIndex", -1)', self.native)
        self.assertIn('Storage.deleteValue("watchStartDelayMin")', self.native)
        self.assertIn("cgiqCloseSettings();", self.native)
        self.assertIn("WatchUi.popView(WatchUi.SLIDE_IMMEDIATE)", self.helpers)
        self.assertNotIn("System.exit()", self.native)

    def test_watch_preview_does_not_touch_live_target(self):
        preview = self.field.split("function drawPointPreview(", 1)[1].split(
            "function onUpdate(", 1
        )[0]
        self.assertNotIn("_index =", preview)
        self.assertNotIn("Storage.setValue(", preview)
        self.assertIn('Storage.getValue("previewCpIndex")', self.field)
        self.assertIn('Storage.getValue("manualCpPlanSig")', self.field)
        self.assertIn("requestPlan == _plan.signature", self.field)
        self.assertIn('Storage.setValue("manualCpAppliedId", req)', self.field)

    def test_manual_next_cp_anchors_progress_at_last_confirmed_cp(self):
        self.assertIn('var previousM = target == 0 ? 0.0 :', self.field)
        self.assertIn('_plan.points[target - 1].distanceM;', self.field)
        self.assertIn('if (target < _index || routeBeforeM < previousM)', self.field)
        self.assertIn('_offsetM = previousM - _rawDistanceM;', self.field)
        self.assertIn('var isPassed = km <= routeM;', self.field)
        self.assertNotIn('var isPassed = i < _index', self.field)
        # FIN is the next CP; previous R2 at 11 km on an 18 km demo.
        self.assertAlmostEqual(11 / 18, 0.6111111111111112)
        self.assertEqual(18 - 11, 7)

    def test_wave_delay_still_falls_back_to_phone_settings(self):
        self.assertIn('Storage.getValue("watchStartDelayMin")', self.helpers)
        self.assertIn('Properties.getValue("StartDelay")', self.helpers)
        self.assertIn('_delayMin = cgiqWatchStartDelay()', self.field)

    def test_main_screen_rows_have_separated_caption_and_value_lanes(self):
        row_names = (
            "headerY", "nameY", "numberY", "progressY", "marginY",
            "paceTitleY", "paceValueY", "descentTitleY",
            "descentValueY", "footerY",
        )
        expression = re.compile(
            r"var (" + "|".join(row_names) +
            r") = \(height \* (0\.\d+)\)\.toNumber\(\);"
        )
        positions = {name: float(value)
                     for name, value in expression.findall(self.field)}
        self.assertEqual(set(row_names), set(positions))
        values = [positions[name] for name in row_names]
        self.assertEqual(values, sorted(values))
        self.assertGreater(positions["paceTitleY"] - positions["marginY"], .07)
        self.assertGreater(positions["paceValueY"] - positions["paceTitleY"], .06)
        self.assertGreater(positions["descentTitleY"] - positions["paceValueY"], .08)
        self.assertGreater(positions["descentValueY"] - positions["descentTitleY"], .05)
        self.assertLess(positions["footerY"], .88)
        self.assertIn("roundColumnText(dc, width * 0.285, nameY", self.field)
        self.assertIn("roundColumnText(dc, width * 0.718, nameY", self.field)
        self.assertIn("fontForSlot(dc, Graphics.FONT_LARGE,", self.field)

    def test_progress_bar_has_blue_rings_and_white_current_center(self):
        self.assertIn("dc.setColor(0x354B67, Graphics.COLOR_TRANSPARENT)",
                      self.field)
        self.assertIn("dc.setColor(0x66C1F4, Graphics.COLOR_TRANSPARENT)",
                      self.field)
        self.assertIn("dc.fillCircle(markerX, y, 7);", self.field)
        self.assertIn("dc.fillCircle(markerX, y, 4);", self.field)
        self.assertIn("dc.fillCircle(hereX, y, 9);", self.field)
        self.assertIn("dc.fillCircle(hereX, y, 6);", self.field)
        self.assertIn("dc.fillCircle(hereX, y, 4);", self.field)
        self.assertIn("dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT)",
                      self.field)


if __name__ == "__main__":
    unittest.main()
