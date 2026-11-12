#!/usr/bin/env python3
"""Run with: python3 -m unittest discover -s tools -p 'test_*.py' -v"""
import base64
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).parent))
from encode_roadbook import encode, decode, split_three


class RoadbookTests(unittest.TestCase):
    def setUp(self):
        self.model = {
            "official_start": "07:30",
            "points": [
                {"name": "ravito A", "km": 15.25, "ascent_m": 300,
                 "descent_m": 210, "target_elapsed": "02:21",
                 "pause_min": 20, "cutoff": "10:45", "lat": 43.76543,
                 "lon": 4.35523},
                {"name": "Arrivée", "km": 87.9, "ascent_m": 2269,
                 "descent_m": 2361, "target_elapsed": "16:53",
                 "pause_min": 0, "cutoff": "26:45",
                 "cutoff_after_pause": False},
            ],
        }

    def test_round_trip(self):
        payload = encode(self.model)
        restored = decode(payload)
        self.assertEqual(restored["official_start_min"], 450)
        self.assertEqual(restored["points"][0]["name"], "RAVITO A")
        self.assertEqual(restored["points"][0]["km"], 15.25)
        self.assertEqual(restored["points"][1]["cutoff_min"], 1605)
        self.assertEqual(restored["points"][1]["cutoff_after_pause"], False)

    def test_exact_simulator_base64_and_padding(self):
        # The exact three 40-character blocks reported by the simulator user.
        # They contain two legal '=' padding characters at the end.
        blocks = [
            "Q0dJUQEDwgH0AbQAZAAwAAUIAgEAAAAAAAAHUjEg",
            "REVNT0wEzAFeAXMACFMCAQAAAAAAAAdSMiBERU1P",
            "CAfQArICyAAArQIAAAAAAAAACEZJTiBERU1Ppg==",
        ]
        self.assertEqual([len(b) for b in blocks], [40, 40, 40])
        base64_text = "".join(blocks)
        self.assertTrue(base64_text.endswith("=="))
        payload = base64.b64decode(base64_text, validate=True)
        self.assertEqual(len(payload), 88)
        self.assertEqual(sum(payload) % 256, 0)
        self.assertEqual(decode(payload)["official_start_min"], 450)
        self.assertEqual(len(decode(payload)["points"]), 3)
        self.assertEqual(
            encode(__import__("json").loads(
                (Path(__file__).parent / "demo-roadbook.json").read_text(
                    encoding="utf-8"
                )
            )),
            payload,
        )

    def test_three_blocks_join_exactly(self):
        payload = encode(self.model)
        encoded = base64.b64encode(payload).decode("ascii")
        blocks = split_three(encoded)
        self.assertEqual("".join(blocks), encoded)
        self.assertTrue(all(len(part) % 4 == 0 for part in blocks))
        self.assertEqual(decode(base64.b64decode("".join(blocks)))["points"][0]["ascent_m"], 300)

    def test_checksum_rejects_corruption(self):
        payload = bytearray(encode(self.model))
        payload[15] ^= 0x20
        with self.assertRaises(ValueError):
            decode(bytes(payload))

    def test_ordering_is_enforced(self):
        self.model["points"][1]["km"] = 10
        with self.assertRaises(ValueError):
            encode(self.model)

    def test_missing_coordinate_rejected(self):
        del self.model["points"][0]["lon"]
        with self.assertRaises(ValueError):
            encode(self.model)

    def test_day_rollover(self):
        self.model["points"][1]["cutoff"] = "02:45"
        result = decode(encode(self.model))
        self.assertEqual(result["points"][1]["cutoff_min"], 1605)


if __name__ == "__main__":
    unittest.main()
