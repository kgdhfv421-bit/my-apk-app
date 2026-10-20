# APKForge — HTML / Website → Android APK

Web app + build server. Features: Gmail sign-in, 10-step app wizard (content, display, permissions, icon, splash,
WebView, AdMob, push, update), live build console, signed APK, **Push Notifications dashboard**
(package name + password), **App Update system** ("Update Required" screen with Download button).

## Run
```bash
docker build -t apkforge .
docker run -p 3000:3000 -v apkforge-data:/data \
  -e PUBLIC_URL=https://YOUR-DOMAIN \          # URL phones use to reach this server (baked into every APK)
  -e GOOGLE_CLIENT_ID=xxxx.apps.googleusercontent.com \
  -e FIREBASE_SERVICE_ACCOUNT=/data/firebase-admin.json \
  -e FIREBASE_WEB_CONFIG='{"apiKey":"...","appId":"1:123:android:abc","projectId":"my-proj","senderId":"123"}' \
  apkforge
```
Without Docker: install JDK 17, Gradle 8.7, Android SDK (platform 34, build-tools 34), then `cd server && npm i && node server.js`.
Local testing without Google: add `-e DEV_LOGIN=1` (email-only login — never in production).

### One-time setup
1. **Google sign-in**: Google Cloud Console → Credentials → OAuth client ID (Web). Add your domain to *Authorized JavaScript origins*. Put the ID in `GOOGLE_CLIENT_ID`.
2. **Push (FCM)**: Firebase console → create project → add an Android app (any package) → copy apiKey / appId / projectId / senderId into `FIREBASE_WEB_CONFIG`; Project settings → Service accounts → generate key → `FIREBASE_SERVICE_ACCOUNT`. Leave the API key unrestricted (all built apps share this one Firebase project).
3. HTTPS is required for Google sign-in (except localhost).

## How the two special features work
**Push**: wizard auto-generates the package name from the app name (`com.apkforge.<name>`, editable) and asks for a push password.
Installed apps register their FCM token with the server under that package name. Open **Push** → enter package name + password → send title/message/image to all or one device.

**Update Required**: every build is registered per package name. Build again with the **same package name and a higher version code** →
every phone running an older build of that package shows the blocking *Update Required* screen (installed vs available version, notes, 64-bit chip, size)
with *Download Update*; it downloads the APK with progress and launches the installer. "Force update" can be turned off (shows "Maybe later").
Updates only install over the old app because all your apps are signed with **your account's own keystore** (kept in `data/keystores` — back it up!).
The Updates page lets you edit notes, toggle force, and roll back to an older release.

## Layout
- `web/index.html` — the whole frontend (vanilla JS)
- `server/server.js` — API, build runner, push, update endpoints
- `android-template/` — Gradle WebView shell; the server copies it per build, writes `assets/config.json` + your HTML, and runs `gradle assembleRelease`

## Status
Written and syntax-checked; the UI was smoke-tested in headless Chromium against a mocked API.
The Gradle/Android build, FCM delivery and Google sign-in need a real environment (Android SDK, Firebase and Google credentials) and have **not** been run yet — expect to fix small compile errors on the first build.
