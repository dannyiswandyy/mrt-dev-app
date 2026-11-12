# MRT Dev

Private Android app for Danny's own testing. It is separate from the public MRT Tracker: `applicationId` `sg.mrttracker.app.dev`, shown on the phone as **MRT Dev**.

`.github/workflows/build-android-dev.yml` is the whole project. On each run, GitHub Actions writes the Android project, builds `mrt-dev.apk`, and publishes it as the **dev** pre-release in this repository. The workflow runs when that file is pushed, or from **Actions → Run workflow**.

## What it's for

- **DEV / sandbox** — a pretend clock and fake crowds, disruptions, and weather, so features can be tried at any time. Test mode is not saved.
- **Driving mode** — drive a train on the app's routes.
- **Live-cam spotting** — the Live cam train-spotting panel watches the RailCam stream and can correct North-South Line times.
- **Google Routes timing compare** — compares the next LTA timetable departure with Google Maps transit routing (Routes API).

## Train counts and timetable estimates

When LTA's timetable isn't available for a line, the app estimates trains from how many are out at each time of day. These counts come from the operators' **train duty rosters**. Each run is counted by its duty: FD = full day (morning to 8pm), HD = half day (to 2pm), (AM) = 7–9.30am peak, (PM) = 5–8pm peak, (OP) = between the peaks, and LN = late night. Depot transfers aren't counted.

| Line | Weekday | Saturday | Sunday | Public holiday |
| --- | --- | --- | --- | --- |
| North-South | 12 Nov 2026 | 31 Oct 2026 | 1 Nov 2026 | 9 Nov 2026 |
| East-West | 12 Nov 2026 | 31 Oct 2026 | 1 Nov 2026 | 9 Nov 2026 |
| Changi Airport branch | same every day: 2 trains, 3 in the 5–8pm peak | | | |
| North East | 12 Nov 2026 | 31 Oct 2026 | 1 Nov 2026 | 9 Nov 2026 (also extended-service nights, 7 Nov 2026) |
| Thomson-East Coast | 12 Nov 2026 | 31 Oct 2026 | 1 Nov 2026 | 9 Nov 2026 (extended-service nights: 7 Nov 2026) |
| Circle | 12 Nov 2026 (both loops and the Dhoby Ghaut service, counted along the whole line) | 31 Oct 2026 loops (short shuttles not counted) | 1 Nov 2026 loops | 9 Nov 2026 loops; extended-service nights: 7 Nov 2026 loops |

- **Train run number roster sheet:** the [NSEWL roster sheet](https://docs.google.com/spreadsheets/d/1O65RS9SMggfmGiQh7gCNQU-1l7l1sV0q85euH3gIAwQ/edit) gives the same weekday totals. It also supplies the short trips:
  - About one in 7 North-South Line trains in the weekday morning peak turn back at Yishun.
  - Outside the peaks, every other East-West Line train towards Tuas Link ends at Joo Koon.
- **Downtown Line:** only partial run lists so far, so it keeps its usual gaps.
- **Thomson-East Coast Sundays and public holidays:** those rosters have no late-night marks. Full-day trains run until the last trains, and PM trains stop around 8pm.
- **Updating counts:** the counts and short trips live in `NSL_TRAINS`, `EWL_TRAINS`, `CG_TRAINS`, `NEL_TRAINS`, `TEL_TRAINS`, `CCL_TRAINS` and `SHORT_PEAK` in the app page.
- **Extended-service nights:** these are in `EXT_DAYS` and `EXT_LAST`. Add the date and each line's later last trains.

The public MRT Tracker app and website use the same counts.

## Actions secrets

Set these under **Settings → Secrets and variables → Actions**. Names only; do not commit values.

| Secret | Required to build | What it does |
| --- | --- | --- |
| `GOOGLE_MAPS_API_KEY` | No | Google Routes API key for the timing compare. Without it, that compare stays off. |
| `ANTHROPIC_API_KEY` | No | Lets the Ask tab use Claude. Without it, the Ask tab uses built-in answers. |
| `RELEASES_TOKEN` | No | If set, a dev build removes any MRT Dev package from the public `mrt-tracker-releases` repo. Dev builds are not published there. The build still succeeds when this secret is unset. |

Publishing the `mrt-dev.apk` pre-release in **this** repository uses the built-in `GITHUB_TOKEN`, not a secret you add.

`signing/mrt-tracker.jks` is the app signing key. Later builds reuse it so a new apk installs over the existing app. The workflow creates the key on the first run if the file is missing.
