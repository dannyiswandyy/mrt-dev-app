import fs from "node:fs";
import vm from "node:vm";

// The service day the expected headways were measured on. Stations that open
// later (Thomson-East Coast stage 5) stay closed, so the trip length stays put.
const FROZEN = Date.parse("2026-11-12T04:00:00Z");

// Run the timetable half of the embedded page. Stop before the UI, which needs a browser.
export function loadTimetable(htmlPath) {
  const html = fs.readFileSync(htmlPath, "utf8");
  const start = html.indexOf("<script>");
  const end = html.indexOf("</script>", start);
  if (start < 0 || end < 0) throw new Error("The app page has no script.");
  const script = html.slice(start + "<script>".length, end);
  const cut = script.indexOf("let current = (location.hash");
  if (cut < 0) throw new Error("Could not find the end of the timetable logic in the app page.");
  const src = script.slice(0, cut) + `
    globalThis.__tt = {
      LINES, headway, countsFor, dayOf, dayBase, shuttleTrains, isPeak, SIM, sgNow
    };
  `;
  const sandbox = { window: {}, console };
  sandbox.globalThis = sandbox;
  vm.createContext(sandbox);
  vm.runInContext(src, sandbox, { filename: "index.html" });
  const tt = sandbox.__tt;
  tt.SIM.time = true;
  tt.SIM.speed = 0;
  tt.SIM.base = FROZEN;
  tt.SIM.realBase = Date.now();
  return tt;
}
