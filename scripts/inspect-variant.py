#!/usr/bin/env python3
"""Print a built variant's merged-manifest surface and network-capable classes.

Usage: scripts/inspect-variant.py core|relay [--build]
  --build  runs ./gradlew assemble<Variant>Debug first.

Reports (from build outputs only, nothing is installed anywhere):
  - uses-permission entries in the merged manifest (flags INTERNET)
  - services, receivers, providers, activities
  - classes in the debug APK dex files that reference network APIs, split into
    InboxIQ's own classes and library classes
Exit status 1 if the core variant has INTERNET or any com.inboxiq network class.
"""
import re, subprocess, sys, zipfile, xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
A = "{http://schemas.android.com/apk/res/android}"
NET = re.compile(rb"L(okhttp3/|com/squareup/okhttp/|okio/|java/net/(HttpURLConnection|URLConnection|Socket|ServerSocket|DatagramSocket|URL);|javax/net/ssl/|android/net/http/|org/apache/http/|android/net/(ConnectivityManager|Network);)[A-Za-z0-9_/$]*;?")

def main():
    if len(sys.argv) < 2 or sys.argv[1] not in ("core", "relay"):
        sys.exit(__doc__)
    v = sys.argv[1]; V = v.capitalize()
    if "--build" in sys.argv:
        subprocess.check_call([str(ROOT / "gradlew"), f"assemble{V}Debug", "-q"], cwd=ROOT)
    mf = ROOT / f"app/build/intermediates/merged_manifests/{v}Debug/process{V}DebugManifest/AndroidManifest.xml"
    apk = ROOT / f"app/build/outputs/apk/{v}/debug/app-{v}-debug.apk"
    if not mf.exists() or not apk.exists():
        sys.exit(f"missing build outputs for {v}; run with --build")
    root = ET.parse(mf).getroot()
    perms = sorted(e.get(A + "name") for e in root.iter("uses-permission"))
    print(f"=== variant: {v}\n--- permissions")
    for p in perms: print("  " + p)
    for tag in ("activity", "service", "receiver", "provider"):
        print(f"--- {tag}s")
        for e in sorted(root.iter(tag), key=lambda e: e.get(A + "name")):
            print("  " + e.get(A + "name") + ("  [exported]" if e.get(A + "exported") == "true" else ""))
    own, lib = set(), set()
    with zipfile.ZipFile(apk) as z:
        for n in z.namelist():
            if n.endswith(".dex"):
                data = z.read(n)
                for m in NET.finditer(data):
                    lib.add(m.group(0).decode().rstrip(";"))
    # InboxIQ-owned classes: scan compiled classes for network API references.
    cls_dirs = list((ROOT / "app/build/tmp/kotlin-classes").glob(f"{v}Debug")) + \
               list((ROOT / "app/build/intermediates/javac").glob(f"{v}Debug/**/classes"))
    for d in cls_dirs:
        for f in d.rglob("*.class"):
            if NET.search(f.read_bytes()):
                own.add(str(f.relative_to(d)).removesuffix(".class").replace("/", "."))
    print("--- InboxIQ classes referencing network APIs (compiled app classes)")
    for c in sorted(own): print("  " + c)
    if not own: print("  (none)")
    pk = {}
    for c in lib:
        parts = c[1:].split("/")
        key = "/".join(parts[:3] if parts[0] in ("com", "org") else parts[:2])
        pk[key] = pk.get(key, 0) + 1
    print(f"--- network-API classes present in the APK dex, by package ({len(lib)} total, transitive libraries included)")
    for k, n in sorted(pk.items()): print(f"  {k}  ({n})")
    bad = v == "core" and ("android.permission.INTERNET" in perms or bool(own))
    print("--- verdict")
    print("  INTERNET permission:", "PRESENT" if "android.permission.INTERNET" in perms else "absent")
    print("  relay package classes in APK:", "yes" if any(b"Lcom/inboxiq/app/mcp/" in zipfile.ZipFile(apk).read(n) for n in zipfile.ZipFile(apk).namelist() if n.endswith(".dex")) else "no")
    sys.exit(1 if bad else 0)

main()
