# Network Scan Sender

Android app that scans barcodes/QR codes and sends them over WiFi to a PC server, which types them into any active input field.

## Stack
- **Android app**: Kotlin + Jetpack Compose + ML Kit + CameraX + Room + AdMob
- **PC server**: Python (`pc_server.py`) — runs on the user's local computer

## Project structure
- `app/src/main/` — Android app source (Kotlin)
- `pc_server.py` — Python server for the PC side
- `.github/workflows/build-release.yml` — GitHub Actions CI for Release APK

## Building

### Release APK via GitHub Actions
Push to `main` or `master` and the workflow runs automatically.
Download the APK from **Actions → build-release → Artifacts**.

Required GitHub Secrets:
| Secret | Value |
|--------|-------|
| `STORE_PASSWORD` | Keystore store password |
| `KEY_PASSWORD` | Keystore key password |

Keystore file: `app/my-upload-key.jks` (already in repo)

### Local build (Android Studio)
```bash
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk
```

## Ad configuration (`app/src/main/java/com/example/ui/components/AdConfig.kt`)
- `FORCE_PRODUCTION_ADS = false` — production ads show automatically in release builds via `!BuildConfig.DEBUG`
- Debug builds use Google's safe test ad IDs

## PC Server (`pc_server.py`)
```bash
pip install pyautogui   # optional — enables auto-typing
python pc_server.py
```
Runs on port 5000. Scan the QR code shown in the terminal with the app to connect.

## User preferences
- Keep existing project structure and stack
