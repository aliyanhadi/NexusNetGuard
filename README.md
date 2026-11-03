# Nexus NetGuard

Android gaming utility: PUBG launcher, per-app background-traffic firewall (VpnService, no root),
ping/jitter meter, DNS benchmark. Optional PHP backend serves the optimization profile.

## Get the APK from GitHub
1. Create a repo and push this folder to the `main` branch.
2. Open the **Actions** tab -> **Build APK** (runs on every push, or click *Run workflow*).
3. When it finishes, download **NexusNetGuard-standard-debug** (no root/Shizuku) and/or **NexusNetGuard-pro-debug** (Shizuku features) under *Artifacts*.
4. Unzip, copy the APK to your phone, allow "Install unknown apps", install.

## How the firewall works
Only the apps you switch on are routed into the VPN tunnel, where their packets are discarded.
All other apps, including the game, never enter the tunnel. Needs the one-time VPN consent dialog.

## Backend (optional)
Import `backend-php/database/schema.sql` into MySQL, deploy `backend-php/` on PHP 8.1+, set env vars
`DB_HOST DB_NAME DB_USER DB_PASS` (and optionally `NEXUS_API_TOKEN`), then set `CONFIG_URL` in `app/build.gradle.kts`.
Without it the app uses built-in defaults.

## Two VPN modes (Android allows only one VPN at a time)
- **Firewall**: blocks the apps you choose; the game is never routed through the app.
- **DNS Boost**: answers every app's DNS lookups (the game's too) from a cache or your fastest resolver. Only DNS
  passes through the app; game packets do not. Turning one mode on turns the other off.
- **BOOST & LAUNCH** starts Firewall mode (if apps are selected and neither mode is running), then opens the game.

## Signed release APK
```bash
keytool -genkeypair -v -keystore release.jks -alias nexus -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 release.jks     # on Windows PowerShell: [Convert]::ToBase64String([IO.File]::ReadAllBytes("release.jks"))
```
Add repo secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`, then run **Build APK** manually
(or push a `v*` tag). Download **NexusNetGuard-standard-release** / **NexusNetGuard-pro-release**. Keep `release.jks` safe: updates must use the same key.

## Telemetry
Opt-in switch on the Hub. Set `TELEMETRY_URL` (and `API_TOKEN` if the server uses `NEXUS_API_TOKEN`) in `app/build.gradle.kts`.

## Two apps
| App | Package | Needs |
|---|---|---|
| Nexus NetGuard | `com.nexus.netguard` | Nothing (VPN consent only) |
| Nexus NetGuard Pro | `com.nexus.netguard.pro` | Shizuku running (adds a Pro tab) |

Pro adds: system-wide Private DNS set in one tap (works alongside the Firewall), Game Mode performance for the game,
and background restrictions for the apps selected in the Firewall tab, with a Restore button. Both can be installed side by side.
Neither app contains anything that touches the game's files, memory or process.
