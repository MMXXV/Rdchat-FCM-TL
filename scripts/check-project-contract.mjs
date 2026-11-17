import fs from "node:fs";
import path from "node:path";

const root = process.cwd();
const read = (file) => fs.readFileSync(path.join(root, file), "utf8");
const parseJson = (file) => {
  try { return JSON.parse(read(file)); }
  catch (error) { throw new Error(file + " não contém JSON válido: " + error.message); }
};
const failures = [];
const checks = {};
const check = (name, condition, detail = "") => {
  checks[name] = !!condition;
  if (!condition) failures.push({ check: name, detail });
};

const firebaseRc = parseJson(".firebaserc");
const firebaseConfig = parseJson("firebase.json");
const googleServices = parseJson("app/google-services.json");
const firestoreIndexes = parseJson("firestore.indexes.json");
const workerPackage = parseJson("rdchat-fcm-worker/package.json");

const html = read("app/src/main/assets/index.html");
const gradle = read("app/build.gradle.kts");
const manifest = read("app/src/main/AndroidManifest.xml");
const main = read("app/src/main/java/com/example/rdchat/MainActivity.kt");
const worker = read("rdchat-fcm-worker/src/index.js");
const rules = read("firestore.rules");

const expectedProject = "rdchat-firebasev1";
const appId = (gradle.match(/applicationId\\s*=\\s*"([^"]+)"/) || [])[1] || "";
const webProject = (html.match(/projectId\\s*:\\s*'([^']+)'/) || [])[1] || "";
const webAuthDomain = (html.match(/authDomain\\s*:\\s*'([^']+)'/) || [])[1] || "";
const webSenderId = (html.match(/messagingSenderId\\s*:\\s*'([^']+)'/) || [])[1] || "";
const ruleFields = ["profileMediaVersion", "profileMediaType", "profileMediaName"];
const googleClients = (googleServices.client || []).map((client) => client.client_info?.android_client_info?.package_name);
const projectId = googleServices.project_info?.project_id;
const senderId = googleServices.project_info?.project_number;
const firebaseRulesPath = firebaseConfig.firestore?.rules;
const assetHost = (main.match(/WORKER_DOMAIN\\s*=\\s*"([^"]+)"/) || [])[1] || "";
const appUrl = (main.match(/APP_URL\\s*=\\s*"https:\\/\\/([^/]+)\\/assets\\/index\\.html"/) || [])[1] || "";
const assetPrefixHost = (main.match(/ASSET_PREFIX\\s*=\\s*"https:\\/\\/([^/]+)\\/assets\\/"/) || [])[1] || "";

check("firebaserc_points_to_expected_project", firebaseRc.projects?.default === expectedProject, String(firebaseRc.projects?.default));
check("firebase_json_uses_firestore_rules", firebaseRulesPath === "firestore.rules", String(firebaseRulesPath));
check("google_services_project_matches", projectId === expectedProject, String(projectId));
check("web_sdk_project_matches", webProject === expectedProject, String(webProject));
check("web_auth_domain_matches", webAuthDomain === expectedProject + ".firebaseapp.com", String(webAuthDomain));
check("web_sender_id_matches_native_config", webSenderId === String(senderId), webSenderId + " != " + senderId);
check("android_package_registered_in_google_services", googleClients.includes(appId), appId);
check("webview_asset_loader_host_matches_app_url", !!assetHost && assetHost === appUrl && appUrl === assetPrefixHost, [assetHost, appUrl, assetPrefixHost].join(" / "));
check("native_bridge_exposes_camera_mic_permission_request", main.includes("requestCameraAndMicrophonePermissions") && main.includes("Manifest.permission.CAMERA") && main.includes("Manifest.permission.RECORD_AUDIO"));
check("manifest_has_internet", manifest.includes('android.permission.INTERNET'));
check("manifest_has_camera_and_microphone", manifest.includes('android.permission.CAMERA') && manifest.includes('android.permission.RECORD_AUDIO'));
check("manifest_has_notifications", manifest.includes('android.permission.POST_NOTIFICATIONS'));
check("gradle_uses_firebase_messaging", gradle.includes("libs.firebase.messaging"));
check("worker_declares_firebase_service_account", read(".github/workflows/deploy-fcm-worker.yml").includes("FIREBASE_SERVICE_ACCOUNT"));
check("firestore_rules_allow_worker_profile_metadata", ruleFields.every((field) => rules.includes("'" + field + "'")));
check("worker_profile_upload_writes_same_metadata", ruleFields.every((field) => worker.includes(field)));
check("google_services_contains_clients", Array.isArray(googleServices.client) && googleServices.client.length > 0);
check("firestore_indexes_has_valid_structure", Array.isArray(firestoreIndexes.indexes) && firestoreIndexes.fieldOverrides !== undefined);
check("worker_package_has_check_script", typeof workerPackage.scripts?.check === "string");

console.log(JSON.stringify({ checks, failures }, null, 2));
if (failures.length) process.exitCode = 1;
