// MRT Dev: shared sightings (Cloudflare Worker). See README.md for setup.
//
// The app sends a sighting when someone uses "Spot this train" with a set number (and has shared sightings turned
// on), and asks for today's sightings every couple of minutes. Each sighting is one KV entry whose details are in
// the entry's metadata, so a day's sightings come back with one list call. Entries expire after 3 days.
//
//   POST /sightings   {line, slot, set, st, at, dir, model, device}      -> {ok: true}
//   GET  /sightings?day=YYYY-MM-DD                                       -> {day, items: [{line, slot, set, n, last}]}
//
// Every request needs the header X-Spots-Key, equal to the SPOTS_KEY secret. No names or locations are kept:
// "device" is a random id the app makes, used to count different spotters and to limit how much one phone sends.

const LINES = new Set(["NSL", "EWL", "CG", "NEL", "CCL", "DTL", "TEL", "BP", "SE", "SW", "PE", "PW", "SEN"]);
const TTL = 3 * 86400;
const PER_DEVICE_PER_DAY = 200;

const json = (o, status = 200) => new Response(JSON.stringify(o), {status, headers: {"content-type": "application/json", "cache-control": "no-store"}});
const sgDay = ms => new Date(ms + 8 * 3600e3).toISOString().slice(0, 10);
const clean = (v, max) => String(v == null ? "" : v).replace(/[\u0000-\u001f<>]/g, "").trim().slice(0, max);

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    if (!env.SPOTS || !env.SPOTS_KEY) return json({error: "The worker isn't set up yet (SPOTS KV and SPOTS_KEY)."}, 500);
    if (req.headers.get("X-Spots-Key") !== env.SPOTS_KEY) return json({error: "Wrong key"}, 403);
    if (url.pathname !== "/sightings") return json({error: "Not found"}, 404);

    if (req.method === "POST") {
      let b;
      try { b = await req.json(); } catch (e) { return json({error: "Not JSON"}, 400); }
      const now = Date.now(), at = Number(b.at);
      const s = {
        line: clean(b.line, 4), slot: clean(b.slot, 4), set: clean(b.set, 16), st: clean(b.st, 40),
        dir: clean(b.dir, 40), model: clean(b.model, 20), at, device: clean(b.device, 32)
      };
      if (!LINES.has(s.line)) return json({error: "line"}, 400);
      if (!/^\d{1,4}$/.test(s.slot)) return json({error: "slot"}, 400);
      if (!/^[0-9A-Za-z/ -]{1,16}$/.test(String(b.set == null ? "" : b.set).trim())) return json({error: "set"}, 400);   // checked as sent, before cleaning
      if (!Number.isFinite(at) || Math.abs(at - now) > 12 * 3600e3) return json({error: "time"}, 400);
      if (!/^[a-z0-9]{8,32}$/.test(s.device)) return json({error: "device"}, 400);
      const day = sgDay(at);
      // a simple daily limit per phone
      const mine = await env.SPOTS.list({prefix: `d:${day}:${s.device}:`, limit: PER_DEVICE_PER_DAY});
      if (mine.keys.length >= PER_DEVICE_PER_DAY) return json({error: "Too many sightings today"}, 429);
      const id = `${now.toString(36)}${Math.random().toString(36).slice(2, 8)}`;
      await env.SPOTS.put(`s:${day}:${id}`, "", {expirationTtl: TTL, metadata: s});
      await env.SPOTS.put(`d:${day}:${s.device}:${id}`, "", {expirationTtl: TTL});
      return json({ok: true});
    }

    if (req.method === "GET") {
      const day = /^\d{4}-\d{2}-\d{2}$/.test(url.searchParams.get("day") || "") ? url.searchParams.get("day") : sgDay(Date.now());
      const groups = new Map();
      let cursor;
      for (let page = 0; page < 20; page++) {          // up to 20,000 sightings a day
        const r = await env.SPOTS.list({prefix: `s:${day}:`, cursor});
        for (const k of r.keys) {
          const s = k.metadata; if (!s || !s.line) continue;
          const key = `${s.line}|${s.slot}|${s.set}`;
          let g = groups.get(key);
          if (!g) groups.set(key, g = {line: s.line, slot: s.slot, set: s.set, devices: new Set(), last: null});
          g.devices.add(s.device);
          if (!g.last || s.at > g.last.at) g.last = {at: s.at, st: s.st, dir: s.dir, model: s.model};
        }
        if (r.list_complete) break;
        cursor = r.cursor;
      }
      const items = [...groups.values()].map(g => ({line: g.line, slot: g.slot, set: g.set, n: g.devices.size, last: g.last}));
      return json({day, items});
    }

    return json({error: "Method"}, 405);
  }
};
