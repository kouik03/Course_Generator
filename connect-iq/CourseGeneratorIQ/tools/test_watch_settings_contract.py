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
        self.assertIn("roundColumnText(dc, width * 0.303, nameY", self.field)
        self.assertIn("roundColumnText(dc, width * 0.715, nameY", self.field)
        self.assertIn("var numberFont = Graphics.FONT_LARGE;", self.field)
        self.assertIn("function drawPrimaryMetric(", self.field)

    def test_compact_cp_header_and_checkpoint_cut_row(self):
        # First row = CP code followed by real service pictograms.
        # Second row = name at left, official CUT clock at right.
        self.assertIn("function drawCheckpointHeader(", self.field)
        self.assertIn("drawCheckpointHeader(dc, headerY,", self.field)
        self.assertIn("nameY - headerY - 5, cpTitle,", self.field)
        self.assertIn('goal >= 0 ? "CUT " + cutoffClock : "SANS CUT"', self.field)
        self.assertIn("var topColW = width * 0.42;", self.field)
        self.assertIn("roundColumnText(dc, width * 0.303, nameY", self.field)
        self.assertIn("roundColumnText(dc, width * 0.715, nameY", self.field)
        self.assertIn("var headerY = (height * 0.105).toNumber();", self.field)
        self.assertIn("var nameY = (height * 0.182).toNumber();", self.field)
        self.assertNotIn('function drawServices(', self.field)

    def test_five_icon_slots_are_reserved_without_invented_services(self):
        self.assertIn("var serviceBits = [2, 4, 8, 16, 32];", self.field)
        self.assertIn("var iconCapacity = serviceBits.size();", self.field)
        self.assertIn("if ((flags & serviceBits[i]) != 0)", self.field)
        self.assertIn("if ((flags & serviceBits[j]) != 0)", self.field)
        self.assertIn("drawCheckpointSymbol(dc, cursor + step / 2, centerY, j);",
                      self.field)
        self.assertIn("function drawCheckpointSymbol(", self.field)
        self.assertIn("if (kind == 0)", self.field)
        self.assertIn("if (kind == 1)", self.field)
        self.assertIn("if (kind == 2)", self.field)
        self.assertIn("if (kind == 3)", self.field)
        self.assertIn("if (kind == 4)", self.field)
        self.assertIn("var cpFont = fontForSlot(dc, Graphics.FONT_MEDIUM, maxHeight);",
                      self.field)

    def test_top_slot_respects_round_screen_chord(self):
        # For 454x454 Fenix 9 Pro, even a 5-icon first row must fit
        # inside the upper chord rather than drawing against the bezel.
        import math
        size = 454
        radius = size * .468
        header_y = size * .105
        measured_small_font_h = 32
        dy = abs(header_y + measured_small_font_h / 2 - size / 2)
        chord = 2 * math.sqrt(radius * radius - dy * dy) - 14
        # Conservative stand-in for a four-character CP label width.
        four_char_cp_width = 86
        five_icon_group_width = four_char_cp_width + 12 + 25 * 5
        self.assertGreater(chord, five_icon_group_width)
        # There must be a gap before the subsequent name/CUT row.
        self.assertGreater((.182 - .105) * size, measured_small_font_h + 2)

    def test_primary_metric_pairs_unit_with_number(self):
        # Primary metric numerals and units must share measured horizontal
        # placement. Independent fixed-column labels created the oversized
        # gaps visible in the 13 November Fenix 9 Pro screenshot.
        self.assertIn("function drawPrimaryMetric(", self.field)
        self.assertIn("var totalW = numberW + gap + unitW;", self.field)
        self.assertIn("var left = centerX - totalW / 2;", self.field)
        self.assertIn("var unitY = y + numberH - unitH;", self.field)
        self.assertIn("dc.drawText(left + numberW + gap, unitY,", self.field)
        self.assertIn('"km", width * 0.425,', self.field)
        self.assertIn('"h" : "",', self.field)
        self.assertEqual(self.field.count("drawPrimaryMetric(dc, width *"), 2)
        self.assertNotIn('captionFont, "km", width * 0.12', self.field)
        self.assertNotIn('captionFont, "h", width * 0.10', self.field)

    def test_primary_metrics_have_room_to_fit_large_fonts(self):
        self.assertIn("var numberY = (height * 0.283).toNumber();",
                      self.field)
        self.assertIn("var progressY = (height * 0.457).toNumber();",
                      self.field)
        self.assertIn("var marginY = (height * 0.494).toNumber();",
                      self.field)
        # At 454 device pixels the number row has almost 80 px
        # before the bar, while the gap from bar to margin is 17 px.
        self.assertGreater((.457 - .283) * 454 - 4, 70)
        self.assertGreater((.494 - .457) * 454, 12)

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
