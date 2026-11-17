from pathlib import Path
import json
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path.cwd()
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
failures = []
checks = {}

def check(name, condition, detail=""):
    checks[name] = bool(condition)
    if not condition:
        failures.append({"check": name, "detail": detail})

xml_files = sorted((ROOT / "app" / "src" / "main").rglob("*.xml"))
for path in xml_files:
    try:
        ET.parse(path)
    except ET.ParseError as exc:
        failures.append({"check": "xml:" + str(path.relative_to(ROOT)), "detail": str(exc)})
        checks["xml:" + str(path.relative_to(ROOT))] = False
    else:
        checks["xml:" + str(path.relative_to(ROOT))] = True

manifest_path = ROOT / "app/src/main/AndroidManifest.xml"
manifest = ET.parse(manifest_path).getroot()
permissions = {node.get(ANDROID_NS + "name") for node in manifest.findall("uses-permission")}
features = {node.get(ANDROID_NS + "name"): node.get("required", "true") for node in manifest.findall("uses-feature")}
application = manifest.find("application")
activities = application.findall("activity") if application is not None else []
services = application.findall("service") if application is not None else []
providers = application.findall("provider") if application is not None else []

check("manifest_root_is_manifest", manifest.tag == "manifest")
check("manifest_internet_permission", "android.permission.INTERNET" in permissions)
check("manifest_network_state_permission", "android.permission.ACCESS_NETWORK_STATE" in permissions)
check("manifest_camera_permission", "android.permission.CAMERA" in permissions)
check("manifest_microphone_permission", "android.permission.RECORD_AUDIO" in permissions)
check("manifest_notifications_permission", "android.permission.POST_NOTIFICATIONS" in permissions)
check("manifest_foreground_service_permissions", {
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_CAMERA",
    "android.permission.FOREGROUND_SERVICE_MICROPHONE"
}.issubset(permissions))
check("camera_and_microphone_not_required_features", features.get("android.hardware.camera") == "false" and features.get("android.hardware.microphone") == "false")
check("single_exported_launcher_activity", len([a for a in activities if a.get(ANDROID_NS + "name") == ".MainActivity" and a.get(ANDROID_NS + "exported") == "true"]) == 1)
check("firebase_messaging_service_is_declared", any(s.get(ANDROID_NS + "name") == ".MyFirebaseMessagingService" and s.get(ANDROID_NS + "exported") == "false" for s in services))
check("call_foreground_service_declared", any(s.get(ANDROID_NS + "name") == ".telegram.TelegramCallForegroundService" and s.get(ANDROID_NS + "foregroundServiceType") == "microphone|camera" for s in services))
check("file_provider_declared", any(p.get(ANDROID_NS + "name") == "androidx.core.content.FileProvider" for p in providers))
check("cleartext_http_disabled", application is not None and application.get(ANDROID_NS + "usesCleartextTraffic") == "false")
check("activity_resizes_for_keyboard", any(a.get(ANDROID_NS + "name") == ".MainActivity" and a.get(ANDROID_NS + "windowSoftInputMode") == "adjustResize" for a in activities))

json_files = sorted(
    p for p in ROOT.rglob("*.json")
    if "node_modules" not in p.parts and ".gradle" not in p.parts and "build" not in p.parts
)
for path in json_files:
    try:
        json.loads(path.read_text(encoding="utf-8"))
    except Exception as exc:
        failures.append({"check": "json:" + str(path.relative_to(ROOT)), "detail": str(exc)})
        checks["json:" + str(path.relative_to(ROOT))] = False
    else:
        checks["json:" + str(path.relative_to(ROOT))] = True

print(json.dumps({"xml_files": len(xml_files), "json_files": len(json_files), "checks": checks, "failures": failures}, ensure_ascii=False, indent=2))
if failures:
    sys.exit(1)
