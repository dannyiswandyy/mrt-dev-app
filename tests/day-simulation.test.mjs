// Runs a whole day of trains with LTA's saved timetable (data/lta-timetable.json) and checks the train engine:
// - no line ever shows the same train twice (same id),
// - no MRT line shows two trains with the same train slot at once (one slot is one physical train),
// - a train that reaches the end of its trip and has another trip from there stays on the map and turns round,
//   instead of vanishing and coming back as a "new" train.
// The page's script runs in node, as in load-timetable.mjs, up to the start of the UI.
import assert from "node:assert/strict";
import test from "node:test";
import fs from "node:fs";
import vm from "node:vm";

const htmlPath = process.env.MRT_INDEX || new URL("../android/app/src/main/assets/index.html", import.meta.url).pathname;
const ttPath = new URL("../data/lta-timetable.json", import.meta.url).pathname;

function loadPage() {
  const html = fs.readFileSync(htmlPath, "utf8");
  const start = html.indexOf("<script>"), end = html.indexOf("</script>", start);
  const script = html.slice(start + "<script>".length, end);
  const cut = script.indexOf("let current = (location.hash");
  if (cut < 0) throw new Error("Could not find the end of the timetable logic in the app page.");
  const raw = fs.readFileSync(ttPath, "utf8");
  const MrtApp = { getTimetable: () => raw };
  const sandbox = { window: { MrtApp }, MrtApp, console };
  sandbox.globalThis = sandbox;
  vm.createContext(sandbox);
  vm.runInContext(script.slice(0, cut) + `;globalThis.__x = {trainsOn, SIM, sgNow, LINES, ttTrips, ttLinks, ttSlotOf, TT};`, sandbox);
  const X = sandbox.__x;
  X.SIM.time = true; X.SIM.speed = 0; X.SIM.realBase = Date.now();
  return X;
}

const X = loadPage();
// a weekday inside the saved timetable's dates: the first Tuesday on or after the day it was downloaded
const built = Date.parse((X.TT && X.TT.feed && X.TT.feed.built || "2026-11-17").slice(0, 10) + "T00:00:00+08:00");
let day = built; while (new Date(day + 8 * 3600e3).getUTCDay() !== 2) day += 864e5;
const START = day + 5 * 3600e3, HOURS = 20, STEP = 60;      // 5am to 1am, every minute
const MRT = ["NSL", "EWL", "CG", "NEL", "CCL", "DTL", "TEL"];

const problems = {dupId: [], dupSlot: [], vanished: []};
let checked = 0;
for (const code of Object.keys(X.LINES)) {
  let prev = null;
  for (let t = 0; t < HOURS * 3600; t += STEP) {
    X.SIM.base = START + t * 1000;
    const now = X.sgNow(), list = X.trainsOn(code, now, true);
    checked++;
    const ids = new Set();
    for (const tr of list) { if (ids.has(tr.id)) problems.dupId.push(`${code} ${now.clock} ${tr.id}`); ids.add(tr.id); }
    if (MRT.includes(code)) {
      const slots = new Map();
      for (const tr of list) {
        const s = tr.ref && X.ttSlotOf(tr.ref.tid); if (!s) continue;
        if (slots.has(s)) problems.dupSlot.push(`${code} ${now.clock} slot ${s}: ${slots.get(s)} and ${tr.ref.tid}`);
        slots.set(s, tr.ref.tid);
      }
    }
    const tt = X.ttTrips(code, now.iso), links = tt ? X.ttLinks(tt) : null;
    if (prev && links) for (const p of prev) {
      if (ids.has(p.id) || !p.ref || !p.atStation) continue;
      const r = p.ref, endIdx = r.p.f + r.p.d * (r.p.arr.length - 1);
      if (Math.abs(p.idx - endIdx) > 0.01 || !links.next.get(r)) continue;       // not at its end, or its train goes off to the depot
      if (!list.some(x => x.prev === p.id)) problems.vanished.push(`${code} ${now.clock} ${r.tid}`);
    }
    prev = list;
  }
}

test("a whole weekday of trains was simulated", () => {
  assert.ok(checked > 1000, `only ${checked} line-minutes checked`);
});
test("no line shows the same train twice", () => {
  assert.deepEqual(problems.dupId.slice(0, 5), [], `${problems.dupId.length} times`);
});
test("no MRT line shows two trains with the same train slot at once", () => {
  assert.deepEqual(problems.dupSlot.slice(0, 5), [], `${problems.dupSlot.length} times`);
});
test("trains turn round at the end of the line instead of vanishing", () => {
  assert.deepEqual(problems.vanished.slice(0, 5), [], `${problems.vanished.length} times`);
});
