#!/usr/bin/env python3
"""CGIQ v1: offline Course Generator IQ roadbook encoder and strict decoder.

Only Python standard library is required. Output Base64 quartets can be pasted
into the three Connect IQ settings fields on iPhone.
"""
from __future__ import annotations

import argparse
import base64
import binascii
import json
import math
from pathlib import Path
import re
import struct
import sys
import unicodedata

MAGIC = b"CGIQ"
VERSION = 1
MAX_POINTS = 25
MAX_CHARS = 18


def _minutes(value: str | int) -> int:
    if isinstance(value, int):
        return value
    match = re.fullmatch(r"(\d{1,3}):([0-5]\d)", str(value))
    if not match:
        raise ValueError(f"Heure invalide: {value!r}; format HH:MM, par exemple 26:30")
    return int(match.group(1)) * 60 + int(match.group(2))


def _u16(value: int, label: str) -> bytes:
    if not isinstance(value, int) or not (0 <= value <= 65535):
        raise ValueError(f"{label}: valeur hors limites uint16: {value}")
    return struct.pack("<H", value)


def _signed24(value: int, label: str) -> bytes:
    if not (-8388608 <= value <= 8388607):
        raise ValueError(f"{label}: valeur hors limites int24: {value}")
    return (value & 0xFFFFFF).to_bytes(3, "little")


def _ascii_name(value: str) -> bytes:
    normal = unicodedata.normalize("NFKD", str(value))
    normal = normal.encode("ascii", "ignore").decode("ascii").upper().strip()
    normal = "".join(ch if 32 <= ord(ch) <= 126 else " " for ch in normal)
    if not (1 <= len(normal) <= MAX_CHARS):
        raise ValueError(f"Nom vide ou trop long ({MAX_CHARS} max): {value!r}")
    return normal.encode("ascii")


def encode(data: dict) -> bytes:
    points = data.get("points")
    if not isinstance(points, list) or not (1 <= len(points) <= MAX_POINTS):
        raise ValueError(f"Il faut de 1 a {MAX_POINTS} points de roadbook")
    start = _minutes(data["official_start"])
    if not (0 <= start < 1440):
        raise ValueError("official_start doit etre dans la premiere journee (00:00 a 23:59)")

    payload = bytearray(MAGIC + bytes([VERSION, len(points)]) + _u16(start, "depart"))
    last_distance, last_target, last_up, last_down = -1, -1, -1, -1
    for position, point in enumerate(points, 1):
        distance10 = round(float(point["km"]) * 100)
        up, down = int(point["ascent_m"]), int(point["descent_m"])
        target = _minutes(point["target_elapsed"])
        pause = int(point.get("pause_min", 0))
        cutoff = point.get("cutoff")
        if cutoff is None:
            cutoff_min = 65535
        else:
            cutoff_min = _minutes(cutoff)
            if cutoff_min < start:
                cutoff_min += 1440
        if not (distance10 > last_distance and target >= last_target and
                up >= last_up and down >= last_down):
            raise ValueError(f"Point {position}: distance, cible, D+ et D- doivent etre croissants")
        if distance10 < 1 or not (0 <= pause <= 240):
            raise ValueError(f"Point {position}: distance ou pause hors limites")
        if cutoff_min != 65535 and cutoff_min < start:
            raise ValueError(f"Point {position}: barriere anterieure au depart officiel")
        lat = point.get("lat")
        lon = point.get("lon")
        if (lat is None) != (lon is None):
            raise ValueError(f"Point {position}: latitude ET longitude requises pour GPS")
        if lat is None:
            lat10000 = lon10000 = 0
        else:
            if not (-90 <= float(lat) <= 90 and -180 <= float(lon) <= 180):
                raise ValueError(f"Point {position}: coordonnees GPS invalides")
            lat10000 = round(float(lat) * 10000)
            lon10000 = round(float(lon) * 10000)
        name = _ascii_name(point["name"])
        services = str(point.get("services", "")).upper()
        if any(ch not in "WF" for ch in services) or len(set(services)) != len(services):
            raise ValueError(
                f"Point {position}: services acceptes : W=eau, F=nourriture (sans doublon)"
            )
        # Extend spare CGIQ v1 flag bits without breaking older roadbooks:
        # 0 = cutoff after pause, 1 = water, 2 = food.
        flags = (1 if point.get("cutoff_after_pause", True) else 0)
        flags |= 2 if "W" in services else 0
        flags |= 4 if "F" in services else 0

        payload += _u16(distance10, "distance/10m")
        payload += _u16(up, "ascent_m") + _u16(down, "descent_m")
        payload += _u16(target, "target_elapsed")
        payload += bytes([pause])
        payload += _u16(cutoff_min, "cutoff")
        payload += bytes([flags])
        payload += _signed24(lat10000, "latitude")
        payload += _signed24(lon10000, "longitude")
        payload += bytes([len(name)]) + name
        last_distance, last_target, last_up, last_down = distance10, target, up, down

    payload.append((-sum(payload)) % 256)
    if len(payload) > 4096:
        raise ValueError("Roadbook trop long pour le decodeur embarque (4096 octets max)")
    return bytes(payload)


def split_three(encoded: str) -> list[str]:
    if len(encoded) % 4:
        raise ValueError("Base64 non aligne")
    quarters = [encoded[i:i + 4] for i in range(0, len(encoded), 4)]
    n = len(quarters)
    groups = [quarters[:(n + 2) // 3],
              quarters[(n + 2) // 3:(2 * n + 2) // 3],
              quarters[(2 * n + 2) // 3:]]
    result = ["".join(group) for group in groups]
    if "".join(result) != encoded:
        raise AssertionError("Erreur interne de decoupage")
    return result


def decode(raw: bytes) -> dict:
    """Independent Python verifier of the on-watch binary protocol."""
    if len(raw) < 10 or raw[:4] != MAGIC or raw[4] != VERSION:
        raise ValueError("Entete CGIQ v1 invalide")
    if sum(raw) % 256 != 0:
        raise ValueError("Somme de controle invalide")
    count = raw[5]
    if not 1 <= count <= MAX_POINTS:
        raise ValueError("Nombre de points invalide")
    start = int.from_bytes(raw[6:8], "little")
    offset = 8
    points = []

    def read(n: int) -> bytes:
        nonlocal offset
        if offset + n > len(raw) - 1:
            raise ValueError("Trame tronquee")
        chunk = raw[offset:offset + n]
        offset += n
        return chunk

    def u16() -> int:
        return int.from_bytes(read(2), "little")

    def i24() -> int:
        n = int.from_bytes(read(3), "little")
        return n - 0x1000000 if n & 0x800000 else n

    for _ in range(count):
        distance10, up, down, target = u16(), u16(), u16(), u16()
        pause = read(1)[0]
        cutoff = u16()
        flags = read(1)[0]
        lat, lon = i24(), i24()
        name_len = read(1)[0]
        if not (1 <= name_len <= MAX_CHARS):
            raise ValueError("Longueur de nom invalide")
        name = read(name_len).decode("ascii")
        points.append({
            "name": name, "km": distance10 / 100, "ascent_m": up,
            "descent_m": down, "target_elapsed_min": target,
            "pause_min": pause, "cutoff_min": None if cutoff == 65535 else cutoff,
            "cutoff_after_pause": bool(flags & 1),
            "services": ("W" if flags & 2 else "") + ("F" if flags & 4 else ""),
            "lat": lat / 10000,
            "lon": lon / 10000,
        })
    if offset != len(raw) - 1:
        raise ValueError("Octets excedentaires")
    return {"official_start_min": start, "points": points}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("roadbook", type=Path, help="Fichier roadbook JSON")
    parser.add_argument("-o", "--output", type=Path,
                        help="Enregistrer les trois blocs dans un fichier texte")
    args = parser.parse_args()
    try:
        model = json.loads(args.roadbook.read_text(encoding="utf-8"))
        payload = encode(model)
        verified = decode(payload)
        if len(verified["points"]) != len(model["points"]):
            raise AssertionError("Le controle de lecture a echoue")
        encoded = base64.b64encode(payload).decode("ascii")
        blocks = split_three(encoded)
        lines = [
            f"Course Generator IQ v1 - {len(payload)} octets, "
            f"{len(verified['points'])} points, {len(encoded)} caracteres Base64",
            "",
        ]
        for i, block in enumerate(blocks, 1):
            lines += [f"BLOC {i}:", block, ""]
        lines += [
            "Copier chaque ligne Base64 dans le champ Bloc correspondant "
            "dans les parametres Connect IQ.",
            "Si le plan est modifie, remplacer les trois blocs avant le depart.",
        ]
        report = "\n".join(lines) + "\n"
        print(report)
        if args.output:
            args.output.write_text(report, encoding="utf-8")
        return 0
    except (OSError, ValueError, KeyError, TypeError, binascii.Error,
            AssertionError, json.JSONDecodeError) as exc:
        print(f"ERREUR: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
