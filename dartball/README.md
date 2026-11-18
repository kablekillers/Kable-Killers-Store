# Dartball Scorebook

`scorebook.html` is the whole app. Any change to this folder on `main` runs
**Build Dartball Scorebook** (`.github/workflows/build-dartball.yml`), which:

1. wraps the page in a small Android app and signs it with the `APP_KEYSTORE` secret,
2. publishes `DartballScorebook-v2.N.apk` as a release in this repo,
3. points the 🎯 Dartball Scorebook card on the store page at the new release.

Every build must be signed with the same `APP_KEYSTORE`, or updates won't install
over earlier versions.
