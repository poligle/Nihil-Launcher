# Releasing

Releases are APKs on GitHub, built and signed by `.github/workflows/release.yml`
when you push a tag. Android only installs an update over an existing app when
both are signed with **the same key**, so the key is made once and kept forever.

## Once: the key

```sh
nix develop          # keytool comes with the JDK
keytool -genkeypair -v -keystore nihil-release.jks -alias nihil \
  -keyalg RSA -keysize 4096 -validity 10000
```

Keep `nihil-release.jks` and its passwords somewhere safe, with a backup.
Lose them and nobody, you included, can update an installed NIHIL again.
`.gitignore` keeps `*.jks` out of the repository.

Then give the key to the workflow as four repository secrets
(Settings → Secrets and variables → Actions, or with `gh`):

```sh
base64 -w0 nihil-release.jks | gh secret set NIHIL_KEYSTORE_BASE64
gh secret set NIHIL_KEYSTORE_PASSWORD
gh secret set NIHIL_KEY_ALIAS          # nihil
gh secret set NIHIL_KEY_PASSWORD
```

## Every release

1. In `app/build.gradle.kts`, set `nihilVersion`, and `nihilVersionCode` with it: when the
   two disagree, the build stops and says which number goes there.
2. Add the changes to `CHANGELOG.md`, and, for F-Droid, what's new in at most 500
   characters in `fastlane/metadata/android/en-US/changelogs/<nihilVersionCode>.txt` (and
   `es-ES`). Commit.
3. Tag with the same version and push the tag:

   ```sh
   git tag v0.2.0
   git push origin v0.2.0
   ```

The workflow checks that the tag is `nihilVersion` and above the last tag, runs the tests, builds
the release APK and publishes `nihil-launcher-v0.2.0.apk` with its SHA-256.
A tag with a dash, like `v0.2.0-rc.1` (and `nihilVersion = "0.2.0-rc.1"`), comes out
as a pre-release: signed with the same key, so it installs over the last release to
be tried before the real one.

## A signed build on your machine

```sh
NIHIL_KEYSTORE=~/keys/nihil-release.jks \
NIHIL_KEYSTORE_PASSWORD=… NIHIL_KEY_ALIAS=nihil NIHIL_KEY_PASSWORD=… \
./gradlew assembleRelease
```

Without `NIHIL_KEYSTORE`, `assembleRelease` signs with the debug key, as before.

## Moving from a debug install

A NIHIL installed with `./gradlew installDebug` is signed with your machine's
debug key, so a release APK won't install over it. Uninstall it first;
favourites, hidden apps and settings start again from zero.

## F-Droid

`fastlane/metadata/android/` is NIHIL's page in F-Droid: its name, short and full
description in English and Spanish, icon, banner and screenshots. F-Droid reads it at the
newest tag, so a change to it shows with the next release.

Once, to get into F-Droid:

1. Release a version that has `fastlane/`.
2. The pin of your key, from that release's APK (`nix develop` has `apksigner`):

   ```sh
   apksigner verify --print-certs nihil-launcher-vX.Y.Z.apk | sed -n 's/^Signer #1 certificate SHA-256 digest: //p'
   ```

3. Fork [fdroiddata](https://gitlab.com/fdroid/fdroiddata), copy
   `docs/fdroid/com.nihilshell.launcher.yml` to its `metadata/` and fill in the version,
   `nihilVersionCode` and the pin. Check it with fdroidserver (F-Droid's buildserver image,
   `registry.gitlab.com/fdroid/fdroidserver:buildserver`): `fdroid readmeta`,
   `fdroid rewritemeta com.nihilshell.launcher`, `fdroid lint com.nihilshell.launcher` and
   `fdroid build -v -l com.nihilshell.launcher`.
4. Open the merge request.

F-Droid builds NIHIL itself from the tag and compares it with the release's APK
(`Binaries`). When the two are the same but for the signature, a reproducible build, it
publishes yours, signed with your key, and the GitHub and F-Droid APKs update each other.
When they aren't, drop `Binaries` and `AllowedAPKSigningKeys`: F-Droid signs with a key of
its own, and moving between the two takes an uninstall. After that, every tag like
`v0.4.0` reaches F-Droid by itself (`UpdateCheckMode`); a `-rc` doesn't.
