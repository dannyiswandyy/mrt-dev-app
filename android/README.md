# MRT Tracker (Android)

A small Android app that shows the MRT Train Tracker full screen. The tracker page is bundled inside the app (`app/src/main/assets/index.html`), so trains and timings work offline; the internet is only used for fonts.

## How MRT Dev is built

This folder is the whole Android project. `.github/workflows/build-android-dev.yml` copies it, adds LTA's timetable and the 3D buildings, builds the app and publishes `mrt-dev.apk` as the **dev** pre-release ("MRT Dev …"). It builds when anything in this folder or the workflow changes, or from **Actions → Build MRT Dev app → Run workflow**.

- The app page: `app/src/main/assets/index.html` (edit it directly).
- Timetable intelligence: `app/src/main/assets/mrt-intelligence.js`, loaded by the page.
- Java code: `app/src/main/java/sg/mrttracker/app/`.

Each new build installs over the previous one, because the first build saved a signing key to `signing/mrt-tracker.jks` in the repository. MRT Dev has an orange icon and a DEV badge, and installs next to MRT Tracker without changing it.

## LTA timetable

Every build downloads LTA's official train timetable (GTFS Schedule (Train), LTA DataMall) and packs it into the app, so train times follow the real schedule. Each good download is also saved in the repository as `data/lta-timetable.json`. If LTA's download fails during a build, that saved copy is packed instead, so every version of the app has the timetable. The app's Settings (under Updates) shows which timetable it has and when it was downloaded.

## Live weather (MRT Dev)

The map shows live weather from the National Environment Agency (NEA), via data.gov.sg: the 2-hour forecast, rain measured at rain gauges, temperature, wind and lightning, refreshed every 10 minutes. On the 2D map, rain falls where the gauges measure it, clouds drift over cloudy or showery areas, hazy areas get a tint, and lightning flashes where it's detected or storms are forecast. Tap the weather chip for details, or to turn the weather on the map off.

## Ask tab (chatbot)

The Ask tab answers questions about stations, lines and trains. Without a key it gives built-in answers from the app's own data. To let ChatGPT answer instead:

1. Get an API key at platform.openai.com (API keys), and set a monthly budget limit there.
2. In your repository, open **Settings → Secrets and variables → Actions → New repository secret**.
3. Name it `OPENAI_API_KEY`, paste the key, and save. Then build again.

The key is built into the app scrambled, and the app only ever sends it to OpenAI. Anyone with a copy of the app could still dig it out, which is why the spending limit matters. Each phone can ask ChatGPT up to 60 questions a day; after that, or when offline, the app falls back to built-in answers.

## 3D buildings

When zoomed in on the 2D map, buildings near each station rise up in 3D. Their outlines and heights come from OpenStreetMap (© OpenStreetMap contributors), downloaded while GitHub builds the app and packed into it. GitHub keeps a copy between builds, so OpenStreetMap is only asked again once a month. If the download fails, the last copy is used, and the build never fails because of it. The **3D** button on the map turns them off.

## Build with Android Studio

Open this folder in Android Studio and run **Build → Build APK(s)**. A debug build needs no signing key.

## Updating the tracker

Replace `app/src/main/assets/index.html` and build again. (Inside the workflow file the page is stored compressed, as gzip then base64, because GitHub won't run a workflow file over 500 KB; the workflow file is made from this project, so change the project and remake it.)
