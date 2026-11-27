# KK Installer (phone app)

Android phone app that does what the PC installer does, over Wi-Fi, with no PC:
connect to Fire TV / Fire Stick and onn / Google TV boxes with ADB, install APKs
(from the Kable Killers store, a link, or a file on the phone), uninstall / force stop /
clear / disable apps, a remote control with typing, screenshots and an ADB shell.

- `app/src/main/assets/index.html` – the whole screen (UI).
- `app/src/main/java/.../adb/` – the ADB-over-Wi-Fi engine (same as `adb connect IP:5555`).
- `app/src/main/java/.../MainActivity.java` – wraps the page and gives it ADB, downloads and file picking.

Any change to this folder on `main` runs **Build KK Installer**
(`.github/workflows/build-installer.yml`), which signs the APK with the `APP_KEYSTORE`
secret, publishes `KK-Installer-v1.N.apk` as a release, and points the 🛠️ card on the
store page at it.

On the TV: Fire TV → Developer Options → ADB Debugging ON. onn / Google TV →
Developer options → USB debugging ON. First connection shows "Allow USB debugging?"
on the TV — tick "Always allow from this computer".
