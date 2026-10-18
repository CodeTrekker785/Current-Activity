#!/usr/bin/env python3
"""Linux CLI companion for inspecting Android APK DEX instructions.

Requires Python 3.10+ and Androguard. This is static analysis: it does not execute
the APK and its register-flow hints are intraprocedural heuristics.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import zipfile
from pathlib import Path
from typing import Any, Iterable

REGISTER_RE = re.compile(r"\bv\d+\b|\bp\d+\b")
def instruction_text(instruction: Any) -> str:
    """Normalize Androguard instruction output without depending on repr()."""
    try:
        operands = instruction.get_output()
    except Exception:
        operands = str(instruction)
    name = str(instruction.get_name())
    operands = "" if operands is None else str(operands).strip()
    return name if not operands else name + " " + operands


def iter_dex_methods(dex_objects: Iterable[Any]):
    for dex_index, dex in enumerate(dex_objects):
        for cls in dex.get_classes():
            class_name = str(cls.get_name())
            for method in cls.get_methods():
                yield dex_index, class_name, method


def scan_apk(apk_path: Path, search: str | None, method_filter: str | None,
             limit: int, include_flow: bool) -> dict[str, Any]:
    if not apk_path.is_file():
        raise FileNotFoundError("APK file not found: " + str(apk_path))
    if apk_path.suffix.lower() != ".apk":
        raise ValueError("Input must be an .apk file")
    if not zipfile.is_zipfile(apk_path):
        raise ValueError("The input is not a valid ZIP/APK archive")

    try:
        from androguard.misc import AnalyzeAPK
    except ImportError as exc:
        raise RuntimeError(
            "Androguard is missing. Install it with: python3 -m pip install -r "
            "tools/linux/requirements.txt"
        ) from exc

    apk, dex_objects, _analysis = AnalyzeAPK(str(apk_path))
    if not isinstance(dex_objects, (list, tuple)):
        dex_objects = [dex_objects]

    try:
        permissions = sorted(str(p) for p in apk.get_permissions())
    except Exception:
        permissions = []

    result: dict[str, Any] = {
        "file": str(apk_path),
        "package": str(apk.get_package() or ""),
        "version_name": str(apk.get_androidversion_name() or ""),
        "version_code": str(apk.get_androidversion_code() or ""),
        "permissions": permissions,
        "dex_count": len(dex_objects),
        "classes": [],
        "methods_scanned": 0,
        "instructions_scanned": 0,
        "matches": [],
        "value_flow": [],
        "truncated": False,
    }
    needle = search.casefold() if search else None
    method_needle = method_filter.casefold() if method_filter else None
    total = 0

    for dex_index, class_name, method in iter_dex_methods(dex_objects):
        method_name = str(method.get_name())
        descriptor = str(method.get_descriptor())
        signature = class_name + "->" + method_name + descriptor
        if method_needle and method_needle not in signature.casefold():
            continue
        result["classes"].append(class_name)
        result["methods_scanned"] += 1
        try:
            code = method.get_code()
            if code is None:
                continue
            instructions = list(code.get_bc().get_instructions())
        except Exception:
            continue

        last_defs: dict[str, str] = {}
        byte_offset = 0
        for ins in instructions:
            offset = byte_offset
            try:
                byte_offset += int(ins.get_length())
            except Exception:
                byte_offset += 1
            opcode = str(ins.get_name())
            rendered = instruction_text(ins)
            line = {
                "dex": dex_index,
                "class": class_name,
                "method": method_name,
                "signature": signature,
                "offset": offset,
                "opcode": opcode,
                "instruction": rendered,
            }
            result["instructions_scanned"] += 1
            if needle and needle not in (signature + " " + rendered).casefold():
                # Search results only; flow still tracks instructions before a match.
                pass
            else:
                if needle or search is None:
                    result["matches"].append(line)
                    total += 1
                    if total >= limit:
                        result["truncated"] = True
                        break

            if include_flow:
                registers = REGISTER_RE.findall(rendered)
                dest = registers[0] if registers else None
                if opcode.startswith("move-result") and offset > 0:
                    previous = instructions[offset - 1]
                    prev_opcode = str(previous.get_name())
                    if prev_opcode.startswith("invoke-") or prev_opcode.startswith("filled-new-array"):
                        producer = instruction_text(previous)
                        result["value_flow"].append({
                            **line,
                            "detail": "result register receives result of: " + producer,
                        })
                if opcode.startswith("const") or opcode.startswith("move") or opcode.startswith("sget") or opcode.startswith("iget") or opcode.startswith("aget") or opcode.startswith("new-"):
                    if dest:
                        detail = "definition of " + dest
                        if opcode.startswith("const"):
                            detail += " loaded from literal: " + rendered
                        elif opcode.startswith("move-result"):
                            detail += " loaded from preceding result"
                        elif opcode.startswith("move") and len(registers) > 1:
                            source = registers[1]
                            detail += " copied from " + source
                            if source in last_defs:
                                detail += " (last visible definition: " + last_defs[source] + ")"
                        result["value_flow"].append({**line, "detail": detail})
                        last_defs[dest] = rendered

        if result["truncated"]:
            break

    result["classes"] = sorted(set(result["classes"]))
    return result


def print_text(report: dict[str, Any], include_flow: bool) -> None:
    print("APK:", report["file"])
    print("Package:", report["package"] or "(unknown)")
    print("Version:", report["version_name"] or "(unknown)",
          "code=" + (report["version_code"] or "(unknown)"))
    print("DEX files:", report["dex_count"])
    print("Permissions:", len(report["permissions"]))
    for permission in report["permissions"]:
        print("  " + permission)
    print("\nClasses:", len(report["classes"]))
    for name in report["classes"]:
        print("  " + name)
    print("\nMethods scanned:", report["methods_scanned"])
    print("Instructions scanned:", report["instructions_scanned"])
    print("\nInstruction matches:", len(report["matches"]))
    for item in report["matches"]:
        print("[{}] {} @{:04x}: {}".format(
            item["class"], item["signature"], item["offset"], item["instruction"]))
    if include_flow:
        print("\nRegister/value-flow hints:", len(report["value_flow"]))
        for item in report["value_flow"]:
            print("[{}] {} @{:04x}: {} | {}".format(
                item["class"], item["signature"], item["offset"],
                item["instruction"], item["detail"]))
    if report["truncated"]:
        print("\nOutput limit reached; refine --search/--method or raise --limit.")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Inspect Android APK metadata, DEX instructions, and register/value-flow hints on Linux."
    )
    parser.add_argument("apk", type=Path, help="Path to the APK to inspect")
    parser.add_argument("--search", help="Case-insensitive search across method signatures and instructions")
    parser.add_argument("--method", help="Only inspect methods whose signature contains this text")
    parser.add_argument("--limit", type=int, default=5000, help="Maximum instruction matches to print (default: 5000)")
    parser.add_argument("--flow", action="store_true", help="Include heuristic register/value-flow hints")
    parser.add_argument("--json", action="store_true", help="Write the report as JSON")
    args = parser.parse_args(argv)
    if args.limit < 1:
        parser.error("--limit must be at least 1")
    try:
        report = scan_apk(args.apk, args.search, args.method, args.limit, args.flow)
    except (FileNotFoundError, ValueError, RuntimeError) as exc:
        print("error:", exc, file=sys.stderr)
        return 2
    except Exception as exc:
        print("error: APK analysis failed: " + str(exc), file=sys.stderr)
        return 1
    if args.json:
        print(json.dumps(report, indent=2))
    else:
        print_text(report, args.flow)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
