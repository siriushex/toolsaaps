"""Measure APK contents and verify the icon-only shrinking boundary."""

import argparse
from collections import defaultdict
import json
from pathlib import Path
import re
import struct
from zipfile import ZipFile


def dex_classes(data):
    if data[:4] != b"dex\n" or len(data) < 112:
        raise ValueError("invalid dex header")

    def integer(offset):
        return struct.unpack_from("<I", data, offset)[0]

    strings, string_offset = integer(56), integer(60)
    types, type_offset = integer(64), integer(68)
    count, class_offset = integer(96), integer(100)

    def string(index):
        if index >= strings:
            raise ValueError("invalid string index")
        offset = integer(string_offset + 4 * index)
        for _ in range(5):
            value = data[offset]
            offset += 1
            if not value & 128:
                break
        else:
            raise ValueError("invalid string length")
        return data[offset:data.index(0, offset)].decode("ascii")

    result = set()
    for index in range(count):
        type_index = integer(class_offset + 32 * index)
        if type_index >= types:
            raise ValueError("invalid class type")
        result.add(string(integer(type_offset + 4 * type_index)))
    return result


def verify_usage(text, classes, expected):
    blocks = {}
    current = None
    for line in text.splitlines():
        if not line or line.startswith("#"):
            continue
        if not line[0].isspace():
            current = line.removesuffix(":")
            blocks[current] = []
        elif current is None:
            raise ValueError("invalid R8 removal report")
        else:
            blocks[current].append(line.strip())
    icons, temporary, bridges = 0, 0, 0
    for name, members in blocks.items():
        descriptor = "L" + name.replace(".", "/") + ";"
        if name.startswith("androidx.compose.material.icons."):
            icons += descriptor not in classes
        elif (re.fullmatch(r"(?:androidx\.|kotlinx\.)[\w.$]+\$\$InternalSyntheticLambda\$[\w$]+", name)
              and descriptor not in expected):
            # R8 reports temporary desugaring artifacts that never existed in the input.
            temporary += 1
        elif (name.startswith(("androidx.", "kotlinx.coroutines.debug.internal.AgentPremain"))
              and descriptor in classes and members and all(re.fullmatch(
                  r"public static synthetic .+ \$r8\$lambda\$[^\s(]+\(.*\)", member) for member in members)):
            bridges += len(members)
        else:
            raise ValueError("shrinking crossed the Material icon boundary")
    return icons, temporary, bridges


def inspect(apk, compiled_dirs=(), usage=None, max_bytes=None):
    groups = defaultdict(int)
    classes = set()
    with ZipFile(apk) as archive:
        for entry in archive.infolist():
            is_dex = entry.filename.endswith(".dex")
            group = "dex" if is_dex else entry.filename.split("/")[0]
            groups[group] += entry.compress_size
            if is_dex:
                classes.update(dex_classes(archive.read(entry)))
    expected = set()
    for directory in compiled_dirs:
        if not directory.is_dir():
            raise ValueError("compiled class directory missing")
        for path in directory.rglob("*.class"):
            expected.add("L" + path.relative_to(directory).as_posix()[:-6] + ";")
    if compiled_dirs and (len(expected) < 1000 or expected - classes):
        raise ValueError("compiled application classes missing from APK")
    removed, temporary, bridges = 0, 0, 0
    if usage is not None:
        removed, temporary, bridges = verify_usage(usage.read_text(), classes, expected)
        if removed < 1000:
            raise ValueError("unused Material icon library was not removed")
    size = apk.stat().st_size
    if max_bytes is not None and size > max_bytes:
        raise ValueError("APK exceeds download size budget")
    return {
        "apk_bytes": size,
        "compressed_groups": dict(sorted(groups.items(), key=lambda item: -item[1])),
        "defined_classes": len(classes),
        "retained_app_classes": len(expected),
        "icon_classes": sum(name.startswith("Landroidx/compose/material/icons/") for name in classes),
        "pruned_icon_classes": removed,
        "temporary_desugaring_classes": temporary,
        "temporary_desugaring_bridges": bridges,
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--compiled-dir", type=Path, action="append", default=[])
    parser.add_argument("--usage", type=Path)
    parser.add_argument("--max-bytes", type=int)
    args = parser.parse_args()
    print(json.dumps(inspect(args.apk, args.compiled_dir, args.usage, args.max_bytes)))
