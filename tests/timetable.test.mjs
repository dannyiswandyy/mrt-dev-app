import assert from "node:assert/strict";
import test from "node:test";
import { loadTimetable } from "./load-timetable.mjs";
import expected from "./expected-timetable.json" with { type: "json" };

// The app page; MRT_INDEX can point at another copy.
const htmlPath = process.env.MRT_INDEX || new URL("../android/app/src/main/assets/index.html", import.meta.url).pathname;

const tt = loadTimetable(htmlPath);
const LINES = ["NSL", "EWL", "CG", "NEL", "CCL", "DTL", "TEL"];
const DAY_NAMES = ["weekday", "saturday", "sunday", "publicHoliday", "holidayEve"];

function close(actual, want) {
  assert.ok(Number.isFinite(actual), `headway ${actual} is not a number`);
  assert.ok(Math.abs(actual - want) < 0.00015, `headway ${actual} is not ${want}`);
}

test("the page exposes every line the check covers", () => {
  for (const code of LINES) assert.ok(tt.LINES[code], code);
});

test("day type: weekday, Saturday, Sunday, public holiday, and holiday eve", () => {
  for (const name of DAY_NAMES) {
    const spec = expected.days[name];
    assert.equal(tt.dayOf(spec), spec.day, name);
  }
  assert.equal(tt.dayBase("ext:2026-11-07:sat"), "sat");
});

for (const code of Object.keys(expected.lines)) {
  test(`${code} trains in service and headway for every day and time band`, () => {
    const L = tt.LINES[code];
    for (const name of DAY_NAMES) {
      const day = tt.dayOf(expected.days[name]);
      const weekend = tt.dayBase(day) !== "wd";
      const samples = expected.lines[code][name];
      const roster = L.shuttle ? L.shuttle.trains : tt.countsFor(L, day);
      // Copy out of the page's realm so the comparison is of the numbers, not the array object.
      const bands = [];
      for (const pair of roster) bands.push([pair[0], pair[1]]);
      assert.deepEqual(bands, samples.map(s => [s.from, s.count]), `${code} ${name} counts`);
      for (const sample of samples) {
        let trains = bands[0][1];
        for (const [from, count] of bands) if (sample.from >= from) trains = count;
        assert.equal(trains, sample.count, `${code} ${name} at minute ${sample.from}`);
        close(tt.headway(L, sample.t, weekend, code, 0, day), sample.headway);
      }
    }
  });
}

test("Downtown Line keeps its usual gaps: 2.5, 4.5 and 7 minutes", () => {
  const L = tt.LINES.DTL;
  assert.equal(L.trains, undefined, "DTL has no roster table, so it must not use a trains-in-service list");
  assert.equal(L.peak, 2.5);
  assert.equal(L.off, 4.5);
  assert.equal(L.late, 7);
  // Minute of the day, and the gap on a weekday. Other day types use 4.5 except late, which is 7.
  const bands = [
    [360, 4.5],
    [420, 2.5],
    [569, 2.5],
    [570, 4.5],
    [720, 4.5],
    [1020, 2.5],
    [1199, 2.5],
    [1200, 4.5],
    [1379, 4.5],
    [1380, 7],
    [1410, 7],
  ];
  for (const name of DAY_NAMES) {
    const day = tt.dayOf(expected.days[name]);
    const weekend = tt.dayBase(day) !== "wd";
    for (const [t, weekdayGap] of bands) {
      const want = t >= 23 * 60 ? 7 : weekend ? 4.5 : weekdayGap;
      assert.equal(tt.headway(L, t, weekend, "DTL", 0, day), want, `${name} minute ${t}`);
    }
  }
});
