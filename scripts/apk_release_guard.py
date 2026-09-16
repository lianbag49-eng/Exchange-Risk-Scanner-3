"""Verify same-signer, same-package forward updates before replacing an APK.

Uses the Android SDK's cryptographic verifier. Failure preserves the baseline;
this script never signs, installs, changes keys, or removes application data.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess


def parse_certificate(text: str) -> str:
    values = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$", text, re.M)
    if len(values) != 1:
        raise ValueError("single_verified_signer_required")
    return values[0].lower()


def parse_package(text: str) -> dict:
    match = re.search(r"^package: name='([A-Za-z0-9_.]+)' versionCode='(\d+)'", text, re.M)
    if not match:
        raise ValueError("package_metadata_unavailable")
    return {"package": match[1], "versionCode": int(match[2])}


def evaluate(baseline: dict, candidate: dict) -> dict:
    reasons = []
    if candidate["package"] != baseline["package"] or candidate["package"] != "com.example.riskscanner":
        reasons.append("package_mismatch")
    if candidate["certificateSha256"] != baseline["certificateSha256"]:
        reasons.append("signing_certificate_mismatch")
    if candidate["versionCode"] <= baseline["versionCode"]:
        reasons.append("version_not_increased")
    return {"compatible": not reasons, "reasons": reasons, "baseline": baseline, "candidate": candidate}


def inspect(path: Path, tools: Path) -> dict:
    if not path.is_file():
        raise ValueError("apk_file_missing")
    output = subprocess.run([str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(path.resolve())],
                            check=True, capture_output=True, text=True, timeout=45).stdout
    metadata = subprocess.run([str(tools / "aapt"), "dump", "badging", str(path.resolve())],
                              check=True, capture_output=True, text=True, timeout=30).stdout
    return {**parse_package(metadata), "certificateSha256": parse_certificate(output),
            "apkSha256": hashlib.sha256(path.read_bytes()).hexdigest()}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, default=Path("downloads/Exchange-Risk-Scanner.apk"))
    parser.add_argument("--candidate", type=Path, default=Path("android-app/app/build/outputs/apk/debug/app-debug.apk"))
    parser.add_argument("--report", type=Path, default=Path("apk-update-compatibility.json"))
    args = parser.parse_args()
    report = {"compatible": False, "reasons": ["verification_unavailable"]}
    try:
        sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
        if not sdk:
            raise ValueError("android_sdk_unavailable")
        tools = Path(sdk) / "build-tools" / "35.0.0"
        report = evaluate(inspect(args.baseline, tools), inspect(args.candidate, tools))
    except (OSError, ValueError, subprocess.SubprocessError):
        # Never treat missing verification, malformed metadata, or a bad signature as compatible.
        pass
    args.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    output = os.environ.get("GITHUB_OUTPUT")
    if output:
        with open(output, "a", encoding="utf-8") as stream:
            stream.write("compatible=" + str(report["compatible"]).lower() + "\n")
    print(json.dumps(report))
    if not report["compatible"]:
        print("::warning::APK publication held. Preserve the existing application and download; signing/version compatibility is not verified.")
    return 0  # Build/test success and release eligibility are intentionally separate.


if __name__ == "__main__":
    raise SystemExit(main())
