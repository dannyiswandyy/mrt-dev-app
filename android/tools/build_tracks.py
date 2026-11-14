"""RTS/Sentosa OSM track builder."""
import json, math, os, re, sys, time, urllib.parse, urllib.request

ROOT = sys.argv[1] if len(sys.argv) > 1 else "."
CACHE = sys.argv[2] if len(sys.argv) > 2 else None
ASSETS = os.path.join(ROOT, "app/src/main/assets")
OUT = os.path.join(ASSETS, "tracks.json")
SUMMARY = os.environ.get("GITHUB_STEP_SUMMARY")
ENDPOINTS = [u for u in os.environ.get("OVERPASS_URLS", "").split() if u] or [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
    "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
    "https://overpass.private.coffee/api/interpreter"]
DEADLINE = time.time() + float(os.environ.get("TRACKS_MAX_SECONDS", 3 * 60))
# Same projection as the map (build_map.py): metres east and south of central Singapore.
LAT0, LON0 = 1.3521, 103.8198
KX, KY = 111320 * math.cos(math.radians(LAT0)), 110574
# line: (route in the app's station order, the same route the other way, how to find it by name if the ids change)
ROUTES = {
    "SEN": (2353581, 9663249, '["type"="route"]["route"="monorail"]["name"~"Sentosa Express"](1.22,103.80,1.27,103.85)'),
    "RTS": (19927885, 19927886, '["type"="route"]["ref"="RTS"](1.40,103.70,1.50,103.82)'),
}
TRACK_ROLES = {"", "forward", "backward", "main"}
NAMES = {"SEN": "Sentosa Express", "RTS": "RTS Link"}
NEAR = 600            # a station must be within this many metres of the track
report = []

def say(line=""):
    print(line)
    report.append(line)

def finish():
    if SUMMARY:
        with open(SUMMARY, "a") as f:
            f.write("## RTS Link and Sentosa Express tracks\n\n" + "\n".join(report) + "\n")
    sys.exit(0)

def to_xy(lat, lon):
    return (lon - LON0) * KX, -(lat - LAT0) * KY

def dist(a, b):
    return math.hypot(a[0] - b[0], a[1] - b[1])

def app_lines():
    """The app's stations for each line here (in order) and where they are on its map."""
    html = open(os.path.join(ASSETS, "index.html"), encoding="utf-8").read()
    m = re.search(r'const MAP = \{"stations":(\{[^{}]*\})', html)
    pos = json.loads(m.group(1))
    out = {}
    for code in ROUTES:
        lm = re.search(r'\n  ' + code + r':\{name:"[^"]+"[^\n]*?stations:\[(.*?)\]\}', html)
        seq = re.findall(r'\["([A-Z]+\d*[A-Z]?)","', lm.group(1)) if lm else []
        if len(seq) >= 2 and all(c in pos for c in seq):
            out[code] = [(c, tuple(pos[c])) for c in seq]
    return out

def overpass():
    ids = ",".join(str(i) for a, b, _ in ROUTES.values() for i in (a, b))
    by_name = "".join(f"relation{q};" for _, _, q in ROUTES.values())
    query = f"[out:json][timeout:90];(relation(id:{ids});{by_name});out geom;"
    data = urllib.parse.urlencode({"data": query}).encode()
    last = None
    for attempt in range(2):
        for url in ENDPOINTS:
            left = DEADLINE - time.time()
            if left < 5:
                raise RuntimeError(f"out of time ({last})")
            try:
                req = urllib.request.Request(url, data=data, headers={"User-Agent": "MRTTracker-build/1.0 (GitHub Actions; RTS Link and Sentosa Express tracks)"})
                with urllib.request.urlopen(req, timeout=min(120, left)) as r:
                    j = json.loads(r.read().decode("utf-8"))
                if "elements" in j and not j.get("remark", "").lower().startswith("runtime error"):
                    return j["elements"]
                last = j.get("remark", "no elements")
            except Exception as e:
                last = f"{type(e).__name__}: {e}"
            time.sleep(2 + attempt * 6)
    raise RuntimeError(last)

def track_of(rel):
    """The route's track as one line of map points, joining its ways end to end in the route's order."""
    ways = [[to_xy(p["lat"], p["lon"]) for p in m["geometry"] if p]
            for m in rel.get("members", [])
            if m.get("type") == "way" and m.get("role", "") in TRACK_ROLES and len(m.get("geometry") or []) >= 2]
    line = []
    for i, w in enumerate(ways):
        if not line:
            line = list(w)
            continue
        options = [(dist(line[-1], w[0]), line, w), (dist(line[-1], w[-1]), line, w[::-1])]
        if i == 1:      # the first way's direction is only known once the second one is joined on
            options += [(dist(line[0], w[0]), line[::-1], w), (dist(line[0], w[-1]), line[::-1], w[::-1])]
        gap, base, nxt = min(options, key=lambda o: o[0])
        line = base + (nxt[1:] if gap < 1 else nxt)
    return line

def along(line):
    cum = [0.0]
    for i in range(1, len(line)):
        cum.append(cum[-1] + dist(line[i - 1], line[i]))
    return cum

def place(line, cum, p):
    """How far along the track the point nearest to p is, and how far p is from the track."""
    best = (1e18, 0.0)
    for i in range(len(line) - 1):
        a, b = line[i], line[i + 1]
        dx, dy = b[0] - a[0], b[1] - a[1]
        L2 = dx * dx + dy * dy
        t = 0 if L2 == 0 else max(0, min(1, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / L2))
        d = dist(p, (a[0] + t * dx, a[1] + t * dy))
        if d < best[0]:
            best = (d, cum[i] + t * math.sqrt(L2))
    return best[1], best[0]

def simplify(pts, tol):
    if len(pts) < 3:
        return pts
    a, b = pts[0], pts[-1]
    dx, dy = b[0] - a[0], b[1] - a[1]
    L = math.hypot(dx, dy) or 1e-9
    dmax, idx = 0, 0
    for i in range(1, len(pts) - 1):
        d = abs(dy * pts[i][0] - dx * pts[i][1] + b[0] * a[1] - b[1] * a[0]) / L
        if d > dmax:
            dmax, idx = d, i
    if dmax <= tol:
        return [a, b]
    return simplify(pts[: idx + 1], tol)[:-1] + simplify(pts[idx:], tol)

def cut(line, stations):
    """One path per pair of neighbouring stations, along the track; None if the track doesn't fit."""
    cum = along(line)
    at = []
    for code, p in stations:
        s, d = place(line, cum, p)
        if d > NEAR:
            return None, f"{code} is {d:.0f} m from the track"
        at.append(s)
    if any(b <= a for a, b in zip(at, at[1:])):
        return None, "the stations aren't in order along the track"
    paths = []
    for (ca, A), (cb, B), sa, sb in zip(stations, stations[1:], at, at[1:]):
        pts = [A] + [line[i] for i in range(len(line)) if sa < cum[i] < sb] + [B]
        length = sum(dist(pts[i - 1], pts[i]) for i in range(1, len(pts)))
        if length > 2.5 * dist(A, B) + 400:
            return None, f"the track from {ca} to {cb} is {length:.0f} m, far longer than the stations are apart"
        pts = simplify(pts, 4)
        paths.append([v for q in pts for v in (round(q[0]), round(q[1]))])
    return paths, f"{len(line)} track points, {sum(len(p) // 2 for p in paths)} kept"

def build(elements, lines):
    rels = {e["id"]: e for e in elements if e.get("type") == "relation"}
    paths, used = {}, {}
    for code, (fwd, back, _) in ROUTES.items():
        if code not in lines:
            say(f"⚠️ {code}: the app's page has no stations for this line, so it's skipped.")
            continue
        names = NAMES[code]
        # the route in station order first, then the other way, then anything found by name
        tries = [r for r in (rels.get(fwd), rels.get(back)) if r]
        tries += [r for i, r in rels.items() if i not in (fwd, back) and r.get("tags", {}).get(
            "name" if code == "SEN" else "ref", "").startswith("Sentosa Express" if code == "SEN" else "RTS")]
        why = "OpenStreetMap has no route for it"
        for r in tries:
            line = track_of(r)
            if len(line) < 2:
                why = f"relation {r['id']} has no track"
                continue
            got, why = cut(line, lines[code])
            if got is None:
                got, why2 = cut(line[::-1], lines[code])      # the route the other way round
                why = why if got is None else why2
            if got is not None:
                paths[code], used[code] = got, r["id"]
                say(f"✅ {names}: track from OpenStreetMap relation {r['id']} ({why}).")
                break
        else:
            say(f"⚠️ {names}: {why}, so this download can't be used for it.")
    return paths, used

def main():
    try:
        lines = app_lines()
        cached = os.path.join(CACHE, "tracks.json") if CACHE else None
        old = None
        if cached and os.path.exists(cached):
            try:
                old = json.load(open(cached))
            except Exception:
                old = None
        try:
            say("Downloading the RTS Link and Sentosa Express routes from OpenStreetMap…")
            paths, used = build(overpass(), lines)
        except Exception as e:
            paths, used = {}, {}
            say(f"⚠️ OpenStreetMap couldn't be reached ({e}).")
        if paths and old:
            # a line OpenStreetMap didn't give a usable track for this time keeps its saved one
            for code, segs in old.get("paths", {}).items():
                if code not in paths and code in lines and len(segs) == len(lines[code]) - 1:
                    paths[code], used[code] = segs, old.get("rel", {}).get(code)
                    say(f"Keeping the {code} track saved on {old.get('at', '?')}.")
        for code in lines:
            if paths and code not in paths:
                say(f"{NAMES[code]} stays as straight lines this time.")
        if paths:
            j = {"v": 1, "src": "© OpenStreetMap contributors", "at": time.strftime("%Y-%m-%d"), "rel": used, "paths": paths}
            if cached and (not old or old.get("paths") != paths or old.get("rel") != used):
                os.makedirs(CACHE, exist_ok=True)
                json.dump(j, open(cached, "w"), separators=(",", ":"))
                open(os.path.join(os.path.dirname(os.path.abspath(CACHE)), "osm-fresh"), "w").write("tracks\n")
            elif old:
                j["at"] = old.get("at", j["at"])
            json.dump(j, open(OUT, "w"), separators=(",", ":"))
            return
        if cached and os.path.exists(cached):
            j = json.load(open(cached))
            json.dump(j, open(OUT, "w"), separators=(",", ":"))
            say(f"Using the tracks saved on {j.get('at', '?')} instead.")
        else:
            say("No saved tracks either, so both lines stay as straight lines this time.")
    except Exception as e:
        say(f"⚠️ The tracks step hit an error ({type(e).__name__}: {e}). Both lines stay as straight lines.")

main()
finish()
