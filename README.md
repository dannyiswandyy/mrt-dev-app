# MRT Dev

Private Android app for Danny's own testing. It is separate from the public MRT Tracker: `applicationId` `sg.mrttracker.app.dev`, shown on the phone as **MRT Dev**.

`.github/workflows/build-android-dev.yml` is the whole project. On each run, GitHub Actions writes the Android project, builds `mrt-dev.apk`, and publishes it as the **dev** pre-release in this repository. The workflow runs when that file is pushed, or from **Actions → Run workflow**.

## What it's for

- **DEV / sandbox** — a pretend clock and fake crowds, disruptions, and weather, so features can be tried at any time. Test mode is not saved.
- **Driving mode** — drive a train on the app's routes.
- **Live-cam spotting** — the Live cam train-spotting panel watches the RailCam stream and can correct North-South Line times.
- **Google Routes timing compare** — compares the next LTA timetable departure with Google Maps transit routing (Routes API).

## Actions secrets

Set these under **Settings → Secrets and variables → Actions**. Names only; do not commit values.

| Secret | Required to build | What it does |
| --- | --- | --- |
| `GOOGLE_MAPS_API_KEY` | No | Google Routes API key for the timing compare. Without it, that compare stays off. |
| `ANTHROPIC_API_KEY` | No | Lets the Ask tab use Claude. Without it, the Ask tab uses built-in answers. |
| `RELEASES_TOKEN` | No | If set, a dev build removes any MRT Dev package from the public `mrt-tracker-releases` repo. Dev builds are not published there. The build still succeeds when this secret is unset. |

Publishing the `mrt-dev.apk` pre-release in **this** repository uses the built-in `GITHUB_TOKEN`, not a secret you add.

`signing/mrt-tracker.jks` is the app signing key. Later builds reuse it so a new apk installs over the existing app. The workflow creates the key on the first run if the file is missing.
