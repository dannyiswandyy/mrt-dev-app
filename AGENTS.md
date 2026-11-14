# Rules for AI helpers working on MRT Dev

Danny works on this app with more than one AI (Claude, ChatGPT, Grok). Read this before changing anything, so you don't undo each other's work.

## Before you change anything

1. **Get the newest commit first.** Another AI may have pushed a minute ago. Pull or fetch `main` and build on top of it.
2. **Never force-push** and never reset `main`. If your push is rejected, fetch, put your change on top of the new commits, and push again.
3. **Check that no "Build MRT Dev app" run is in progress** (Actions tab). Each build replaces the **dev** release, so overlapping builds can leave an older build as the release.

## Where things live

Everything is plain files now: nothing is packed, and the build doesn't change the page.

| What | Where |
| --- | --- |
| The app page (all screens, map, trains, Live cam, disruptions…) | `android/app/src/main/assets/index.html` |
| Timetable intelligence | `android/app/src/main/assets/mrt-intelligence.js`, loaded by the page with `<script src="mrt-intelligence.js">` (keep that tag as the page's last line) |
| Android project (Java, manifest, Gradle, build tools) | `android/` (Java in `android/app/src/main/java/sg/mrttracker/app/`) |
| Build | `.github/workflows/build-android-dev.yml`: copies `android/`, adds LTA's timetable and the 3D buildings, builds, publishes the **dev** release |
| Saved LTA timetable | `data/lta-timetable.json` (the build keeps it up to date; don't edit it) |
| LTA timetable updates inside the app | `android/app/src/main/java/sg/mrttracker/app/Timetable.java` (every 6 hours or so the app downloads LTA's timetable and packs it like `android/tools/build_timetable.py`; change both together) |
| App signing key | `signing/mrt-tracker.jks` (keep it, or new builds won't install over the old app) |
| Tests | `tests/`, run by `timetable-check.yml`, `legacy-map-smoke-test.yml` and `rules-check.yml` |

## Changing the app

Edit the files in `android/` directly. Don't add steps to the workflow that change `index.html` during the build (find-and-replace, extra `<script>` blocks, cards that rebuild themselves), and don't add diagnostic steps that print parts of the page: read the file in the repository instead. The **Rules check** goes red if a build step touches the page.

Things to know about the page:

- Its security rules (the `Content-Security-Policy` meta tag) only allow frames from `https://sg.mrttracker.app`. A direct YouTube `<iframe>` is blocked. To show a YouTube video, use the Live cam player (`camSrc` / `camMount`); new camera angles go in `CAM_ANGLES`.
- Trains come from `trainsOn()` and arrival times from `nextArrivals()`. Disruptions are handled in `lineCuts()` / `cutTrain()`, so trains turn back at a closed stretch.
- `alertState` is declared with `let` partway down the script, so code that runs earlier must read it inside `try { … } catch`.

## Before you push

1. Run the rules check: `python3 tests/rules_check.py`. It checks the page's scripts and `mrt-intelligence.js` for syntax errors, that the page still has `lineCuts` / `cutTrain` and `CAM_ANGLES`, that every Python script inside the workflows runs, and that workflow files are valid YAML and under 480 KB.
2. Run the timetable tests: `node --test tests/timetable.test.mjs`.

## After you push

- A push that changes anything in `android/` or `build-android-dev.yml` starts a build by itself. Check **Rules check**, **Timetable checks** and **Legacy map smoke test** are green too.
- Wait for the build to finish and check that the **dev** release shows the new version (`MRT Dev YYYY.MM.DD-HHMM-dev`) before telling Danny it's ready.

## Commit messages

Say what changed for Danny, in plain words: "Live cam: add the Woodlands angle", not "fix patch v3". Say which AI you are in the commit or its trailer, so it's clear who did what.
