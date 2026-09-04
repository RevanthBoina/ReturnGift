# Releasing ReturnGift

This repo now assumes a single stable release signing key.

**Release-build lessons and diagnostics live in [`docs/RELEASE_BUILD_DIAGNOSTICS.md`](docs/RELEASE_BUILD_DIAGNOSTICS.md). Read it before every release.** It is the canonical record of the v3.0.x failures and the mandatory protocol: preflight, compile the release Kotlin/Java variants locally, build debug + release, verify the APK signature, and publish exactly one tag/workflow/APK.

Once a public APK is shipped with that key, every later public APK must use the same key or Android will reject in-place upgrades.

## 1. Prepare the stable keystore once

Generate one long-lived release keystore and keep it outside the repo.

Recommended local inputs:

```bash
export KEYSTORE_FILE=/absolute/path/to/returngift-release.keystore
export KEYSTORE_PASSWORD=...
export KEY_ALIAS=returngift-release
export KEY_PASSWORD=...
```

`app/build.gradle.kts` reads these values from either:

1. environment variables, or
2. `local.properties`

Do not commit either the keystore or the secrets.

## 2. Mirror the same key into GitHub Actions

The tag-based release workflow expects these repo secrets:

- `ANDROID_KEYSTORE_B64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

`ANDROID_KEYSTORE_B64` should be the base64-encoded keystore file:

```bash
base64 -w 0 "$KEYSTORE_FILE"
```

**Important**: If these secrets are not set, the workflow will generate a stable CI fallback keystore instead of failing. However, APKs signed with the CI keystore will NOT be compatible with in-place upgrades from production releases. Always set these secrets for production releases.

## 3. Prepare a release

1. Update `versionCode` and `versionName` in `app/build.gradle.kts`
2. Add the changelog entry in `README.md`
3. Build locally first:

```bash
./gradlew :app:assembleRelease
sha256sum app/build/outputs/apk/release/*.apk
```

4. Smoke-test the signed APK on a device
5. Push `main`
6. Push the tag:

```bash
git tag -a vX.Y.Z -m "vX.Y.Z"
git push origin vX.Y.Z
```

The GitHub Actions workflow will then create the GitHub Release, upload the signed APK, and attach `SHA256SUMS.txt`.

## Optional: local upgrade smoke test

To verify that the next public build can upgrade in place over the current signed build, create a temporary local build with the same key and a higher version:

```bash
export RETURNGIFT_VERSION_CODE=15
export RETURNGIFT_VERSION_NAME=0.5.1-upgrade-test
./gradlew --no-daemon :app:assembleRelease -x lintVitalRelease -x lintVitalAnalyzeRelease -x lintVitalReportRelease
```

Then install the signed baseline APK first, followed by the higher-version APK with `adb install -r ...`.

## 4. Known historical limitation

The old public debug-signing path and the later public `v0.5.0` APK were signed with different keys.

That mismatch is already shipped, so Android cannot retroactively upgrade those installs in place without the original lost signing key. For that cohort, the only honest path is:

- show the in-app update prompt
- explain that Android may require a one-time uninstall + reinstall

Stable signing for the public `v0.6.x` line prevents this problem from repeating.
