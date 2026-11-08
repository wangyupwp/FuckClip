"""Sign an unsigned FuckClip release APK using the AppMarket key and rotation proof."""

import argparse
import os
from pathlib import Path
import re
import subprocess
import tempfile


OLD_CERT = "d4e9323321f13e9c9096c2a907accff49c72c8c232bc85fd7643adb2c51fef5a"
NEW_CERT = "4a5c8f131326a4a31a724c0946ea168fd9d17de321db47b8ea48cd5a24f6ca08"


def run_signer(apksigner, *args):
    return subprocess.check_output([apksigner, *args], text=True)


def fingerprints(report):
    return re.findall(r"certificate sha-256 digest: ([0-9a-f]{64})", report.lower())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apksigner", required=True, type=Path)
    parser.add_argument("--lineage", required=True, type=Path, help="Path to the signing rotation proof")
    parser.add_argument("unsigned_apk", type=Path)
    parser.add_argument("signed_apk", type=Path)
    args = parser.parse_args()

    if not args.lineage.is_file():
        parser.error(f"Signing rotation proof not found: {args.lineage}")
    required = ("KEYSTORE_FILE", "KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD")
    missing = [name for name in required if not os.environ.get(name)]
    if missing:
        parser.error("Missing signing environment variables: " + ", ".join(missing))
    if args.unsigned_apk.resolve() == args.signed_apk.resolve():
        parser.error("Input and output APKs must differ")

    # The app supports API 29+. A v3-only APK can use the new key on every
    # supported version without supplying the compromised key for v1/v2.
    args.signed_apk.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=args.signed_apk.parent) as work_dir:
        signed = Path(work_dir) / "signed.apk"
        run_signer(
            args.apksigner, "sign", "--ks", os.environ["KEYSTORE_FILE"],
            "--ks-key-alias", os.environ["KEY_ALIAS"],
            "--ks-pass", "env:KEYSTORE_PASSWORD", "--key-pass", "env:KEY_PASSWORD",
            "--lineage", args.lineage, "--rotation-min-sdk-version", "28",
            "--v1-signing-enabled", "false", "--v2-signing-enabled", "false",
            "--v3-signing-enabled", "true", "--v4-signing-enabled", "false",
            "--out", signed, args.unsigned_apk,
        )
        signature = run_signer(args.apksigner, "verify", "--verbose", "--print-certs", signed)
        if fingerprints(signature) != [NEW_CERT]:
            raise SystemExit("APK signer does not match the AppMarket release certificate")
        proof = run_signer(args.apksigner, "lineage", "--in", signed, "--print-certs")
        if fingerprints(proof) != [OLD_CERT, NEW_CERT]:
            raise SystemExit("APK does not contain the expected signing lineage")
        signed.replace(args.signed_apk)
    print(f"Verified rotated release APK: {args.signed_apk}")


if __name__ == "__main__":
    main()
