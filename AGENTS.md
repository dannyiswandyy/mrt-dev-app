# Rules for AI helpers working on MRT Dev

Danny works on this app with more than one AI (Claude, ChatGPT, Grok). Read this before changing anything, so you don't undo each other's work.

## Before you change anything

1. **Get the newest commit first.** Another AI may have pushed a minute ago. Pull or fetch `main` and build on top of it.
2. **Never force-push** and never reset `main`. If your push is rejected, fetch, put your change on top of the new commits, and push again.
3. **Check that no "Build MRT Dev app" run is in progress** (Actions tab). Each build replaces the **dev** release, so overlapping builds can leave an older build as the release.

## Where things live

| What | Where |
| --- | --- |
| The app page (all screens, map, trains, Live cam, disruptions…) | `data/mrt-dev-page.b64`, which is `index.html` gzipped, then base64 |
| Android project (Java, manifest, build tools) | written by `.github/workflows/build-android-dev.yml` |
| Timetable intelligence | `data/mrt-intelligence.js`, added to the page by the "Add timetable intelligence" step |
| Tests | `tests/`, run by `timetable-check.yml` and `legacy-map-smoke-test.yml` |

## Changing the app page

Edit the page itself. Don't add new steps to the workflow that patch `index.html` during the build (find-and-replace, extra `<script>` blocks before `</body>`, cards that rebuild themselves). Patches like that break whenever the page changes, fight each other, and are hard to undo. A few older patch steps are still in the workflow: leave them alone unless Danny asks you to change them.

Unpack, edit, pack:

```bash
base64 -d data/mrt-dev-page.b64 | gunzip > /tmp/index.html
# edit /tmp/index.html
gzip -9 -n -c /tmp/index.html | base64 -w0 > data/mrt-dev-page.b64
base64 -d data/mrt-dev-page.b64 | gunzip | cmp - /tmp/index.html   # must print nothing
```

Things to know about the page:

- Its security rules (the `Content-Security-Policy` meta tag) only allow frames from `https://sg.mrttracker.app`. A direct YouTube `<iframe>` is blocked. To show a YouTube video, use the Live cam player (`camSrc` / `camMount`); new camera angles go in `CAM_ANGLES`.
- Trains come from `trainsOn()` and arrival times from `nextArrivals()`. Disruptions are handled in `lineCuts()` / `cutTrain()`, so trains turn back at a closed stretch.
- `alertState` is declared with `let` partway down the script, so code that runs earlier must read it inside `try { … } catch`.

## Before you push

1. Check the script has no syntax errors, for example by pulling the `<script>` out of the page and running `node --check` on it.
2. Run the timetable tests:
   ```bash
   MRT_INDEX=/tmp/index.html node --test tests/timetable.test.mjs
   ```
3. If you changed the workflow, check it's still valid YAML and under 480 KB. GitHub refuses workflow files of 500 KB or more.

## After you push

- A push that changes `build-android-dev.yml` starts a build by itself. A push that only changes `data/` doesn't, so start one from **Actions → Build MRT Dev app → Run workflow**.
- Wait for the build to finish and check that the **dev** release shows the new version (`MRT Dev YYYY.MM.DD-HHMM-dev`) before telling Danny it's ready.

## Commit messages

Say what changed for Danny, in plain words: "Live cam: add the Woodlands angle", not "fix patch v3". Say which AI you are in the commit or its trailer, so it's clear who did what.
