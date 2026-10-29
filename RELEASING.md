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

1. In `app/build.gradle.kts`, raise `versionCode` by one and set `versionName`.
2. Add the changes to `CHANGELOG.md` and commit.
3. Tag with the same version and push the tag:

   ```sh
   git tag v0.2.0
   git push origin v0.2.0
   ```

The workflow checks that the tag matches `versionName`, runs the tests, builds
the release APK and publishes `nihil-launcher-v0.2.0.apk` with its SHA-256.

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
