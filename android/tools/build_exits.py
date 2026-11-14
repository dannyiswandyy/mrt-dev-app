"""Pack every MRT/LRT station exit, and the places near each one, for the station pages and the map.

Runs on GitHub during every build:  python3 tools/build_exits.py <app project folder> [cache folder]
After a fresh download it also writes "osm-fresh" next to the cache folder, so the workflow saves the new copy.

Exits and places come from OpenStreetMap (data (c) OpenStreetMap contributors, ODbL), through the Overpass
API: station entrances (railway=subway_entrance / train_station_entrance, with their exit letter or number
in "ref"), and named places near them (bus interchanges, malls, schools, hospitals, parks, libraries…).
A copy is kept in the cache folder between builds, so OpenStreetMap is only asked again once a month,
or when stations change. If the download fails, the last saved copy is used; with neither, station pages
simply have no exits section. This step never fails the build.

exits.json:
  v: 1, src: attribution, at: "YYYY-MM-DD", key: station set
  x: {station code: [[exit label, x, y, [[place name, kind], ...]], ...]}
     x, y in the map's own metres (x east, y south of central Singapore); each exit is listed under the
     station code nearest to it, and places under the exit nearest to them (up to 4 per exit)
"""
import hashlib, json, math, os, re, shutil, sys, time, urllib.parse, urllib.request

ROOT = sys.argv[1] if len(sys.argv) > 1 else "."
CACHE = sys.argv[2] if len(sys.argv) > 2 else None
ASSETS = os.path.join(ROOT, "app/src/main/assets")
OUT = os.path.join(ASSETS, "exits.json")
SUMMARY = os.environ.get("GITHUB_STEP_SUMMARY")
ENDPOINTS = [u for u in os.environ.get("OVERPASS_URLS", "").split() if u] or [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
    "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
    "https://overpass.private.coffee/api/interpreter"]
RADIUS = 350          # exits are looked for within this many metres of a station
PLACE_R = 200         # a place counts as "near" an exit within this many metres of it
PER_EXIT = 4
BBOX = "1.15,103.59,1.48,104.10"   # all of Singapore (south, west, north, east)
MAX_AGE_DAYS = 30
DEADLINE = time.time() + float(os.environ.get("EXITS_MAX_SECONDS", 8 * 60))
# Same projection as the map (build_map.py): metres east and south of central Singapore.
LAT0, LON0 = 1.3521, 103.8198
KX, KY = 111320 * math.cos(math.radians(LAT0)), 110574
# What a place is, by its OpenStreetMap tags, best first. Places without one of these aren't listed.
KINDS = [
    ("amenity", "bus_station", "Bus interchange"),
    ("shop", "mall", "Mall"), ("building", "retail", "Mall"),
    ("amenity", "hospital", "Hospital"), ("amenity", "clinic", "Clinic"), ("healthcare", "hospital", "Hospital"),
    ("amenity", "university", "University"), ("amenity", "college", "College"), ("amenity", "school", "School"),
    ("amenity", "library", "Library"), ("amenity", "community_centre", "Community club"),
    ("amenity", "townhall", "Civic centre"), ("office", "government", "Government office"),
    ("tourism", "museum", "Museum"), ("tourism", "attraction", "Attraction"), ("tourism", "hotel", "Hotel"),
    ("leisure", "park", "Park"), ("leisure", "stadium", "Stadium"), ("leisure", "sports_centre", "Sports centre"),
    ("amenity", "marketplace", "Market"), ("amenity", "food_court", "Food centre"),
    ("amenity", "cinema", "Cinema"), ("amenity", "theatre", "Theatre"), ("amenity", "place_of_worship", "Place of worship"),
    ("amenity", "police", "Police"), ("amenity", "post_office", "Post office"),
    ("building", "office", "Office"), ("building", "commercial", "Building"), ("building", "public", "Building"),
]
RANK = {(k, v): i for i, (k, v, _) in enumerate(KINDS)}
report = []

def say(line=""):
    print(line)
    report.append(line)

def finish():
    if SUMMARY:
        with open(SUMMARY, "a") as f:
            f.write("## Station exits\n\n" + "\n".join(report) + "\n")
    sys.exit(0)

def stations():
    """Station codes and positions from the map data in the app's page."""
    html = open(os.path.join(ASSETS, "index.html"), encoding="utf-8").read()
    m = re.search(r'const MAP = \{"stations":(\{[^{}]*\})', html)
    return {c: tuple(p) for c, p in json.loads(m.group(1)).items()}

def clusters(st):
    """Station positions joined where they're close together, so each place is asked for once."""
    out = []
    for x, y in sorted(set(st.values())):
        if not any(math.hypot(x - a, y - b) < 150 for a, b in out):
            out.append((x, y))
    return out

def to_latlon(x, y):
    return LAT0 - y / KY, LON0 + x / KX

def to_xy(lat, lon):
    return (lon - LON0) * KX, -(lat - LAT0) * KY

def ask(query, what):
    """One Overpass request, trying each server in turn."""
    data = urllib.parse.urlencode({"data": query}).encode()
    last = None
    for attempt in range(2):
        for url in ENDPOINTS:
            left = DEADLINE - time.time()
            if left < 5:
                raise RuntimeError("out of time")
            try:
                req = urllib.request.Request(url, data=data, headers={"User-Agent": "MRTTracker-build/1.0 (GitHub Actions; station exits)"})
                with urllib.request.urlopen(req, timeout=min(240, left)) as r:
                    j = json.loads(r.read().decode("utf-8"))
                if "elements" in j and not j.get("remark", "").lower().startswith("runtime error"):
                    return j["elements"]
                last = j.get("remark", "no elements")
            except Exception as e:
                last = f"{type(e).__name__}: {e}"
            say(f"  {what}: {url.split('/')[2]} didn't answer ({last}); trying the next one.")
            time.sleep(3 + attempt * 7)
    raise RuntimeError(last)

def overpass_entrances():
    """Every station entrance in Singapore: a small, quick request."""
    return ask(f'[out:json][timeout:120];node["railway"~"^(subway_entrance|train_station_entrance)$"]({BBOX});out tags qt;', "Exits")

def overpass_places():
    """Named places of the kinds in KINDS across Singapore, as single points (centres for areas)."""
    parts = []
    for k in sorted({k for k, _, _ in KINDS}):
        vals = "|".join(sorted({v for kk, v, _ in KINDS if kk == k}))
        parts.append(f'nwr["{k}"~"^({vals})$"]["name"]({BBOX});')
    return ask(f"[out:json][timeout:240];({''.join(parts)});out tags center qt;", "Places")

def exit_label(tags):
    """The exit's letter or number as signed at the station: "A", "B2", "3"…"""
    for v in (tags.get("ref"), tags.get("local_ref"), tags.get("name"), tags.get("name:en")):
        v = (v or "").strip()
        if not v:
            continue
        m = re.match(r"^(?:exit\s*)?([A-Z]?\d{0,2}[A-Z]?)$", v, re.I) or re.search(r"\bexit\s+([A-Z]?\d{0,2}[A-Z]?)\b", v, re.I)
        if m and m.group(1):
            return m.group(1).upper()
    return ""

def kind(tags):
    best = None
    for k, v in tags.items():
        r = RANK.get((k, v))
        if r is not None and (best is None or r < best):
            best = r
    return best

def pack(elements, st):
    seen, exits, places = set(), [], []
    for e in elements:
        key = (e["type"], e["id"])
        if key in seen:
            continue
        seen.add(key)
        tags = e.get("tags") or {}
        lat, lon = (e.get("lat"), e.get("lon")) if e["type"] == "node" else ((e.get("center") or {}).get("lat"), (e.get("center") or {}).get("lon"))
        if lat is None or lon is None:
            continue
        x, y = to_xy(lat, lon)
        if tags.get("railway") in ("subway_entrance", "train_station_entrance"):
            exits.append({"label": exit_label(tags), "x": x, "y": y, "near": []})
        else:
            r = kind(tags)
            name = (tags.get("name:en") or tags.get("name") or "").strip()
            if r is not None and name and not re.search(r"\b(MRT|LRT)\b.*\bstation\b|\bexit\b", name, re.I):
                places.append((r, x, y, name, KINDS[r][2]))
    # each exit goes under the station code nearest to it
    codes = list(st.items())
    by_code = {}
    for ex in exits:
        c, d = min(((c, math.hypot(ex["x"] - p[0], ex["y"] - p[1])) for c, p in codes), key=lambda t: t[1])
        if d <= RADIUS:
            by_code.setdefault(c, []).append(ex)
    # the same exit mapped twice (e.g. two nodes for one stair): keep one
    for c, lst in by_code.items():
        uniq = []
        for ex in sorted(lst, key=lambda e: (e["label"] == "", e["label"])):
            if any(u["label"] == ex["label"] and math.hypot(u["x"] - ex["x"], u["y"] - ex["y"]) < 25 for u in uniq):
                continue
            uniq.append(ex)
        by_code[c] = uniq
    # each place goes under the exit nearest to it (across all stations), if it's close enough
    grid = {}
    for lst in by_code.values():
        for ex in lst:
            grid.setdefault((int(ex["x"] // PLACE_R), int(ex["y"] // PLACE_R)), []).append(ex)
    for r, x, y, name, what in places:
        gx, gy = int(x // PLACE_R), int(y // PLACE_R)
        near = [ex for i in (-1, 0, 1) for j in (-1, 0, 1) for ex in grid.get((gx + i, gy + j), [])]
        if not near:
            continue
        ex, d = min(((ex, math.hypot(ex["x"] - x, ex["y"] - y)) for ex in near), key=lambda t: t[1])
        if d <= PLACE_R:
            ex["near"].append((r, d, name, what))
    out = {}
    for c, lst in by_code.items():
        rows = []
        for ex in lst:
            names, near = set(), []
            for r, d, name, what in sorted(ex["near"], key=lambda t: (t[0] > 2, t[1], t[0])):
                if name.lower() in names:
                    continue
                names.add(name.lower())
                near.append([name, what])
                if len(near) >= PER_EXIT:
                    break
            rows.append([ex["label"], round(ex["x"]), round(ex["y"]), near])
        rows.sort(key=lambda r: (r[0] == "", re.sub(r"\d+", lambda m: m.group(0).zfill(3), r[0])))
        out[c] = rows
    return out

def use(path, why):
    os.makedirs(ASSETS, exist_ok=True)
    shutil.copyfile(path, OUT)
    j = json.load(open(OUT))
    say(f"{why}: {sum(len(v) for v in j['x'].values()):,} exits at {len(j['x'])} stations, downloaded {j['at']}.")

def main():
    st = stations()
    pts = clusters(st)
    key = hashlib.sha1(json.dumps(sorted((c, round(p[0], -1), round(p[1], -1)) for c, p in st.items())).encode()).hexdigest()[:12]
    cached = os.path.join(CACHE, "exits.json") if CACHE else None
    old = None
    if cached and os.path.exists(cached):
        try:
            old = json.load(open(cached))
        except Exception:
            old = None
    if old and old.get("v") == 1 and old.get("key") == key:
        age = (time.time() - time.mktime(time.strptime(old["at"], "%Y-%m-%d"))) / 86400
        if age < MAX_AGE_DAYS:
            use(cached, "✅ Using the saved copy")
            return
    say(f"Downloading station exits and nearby places across Singapore from OpenStreetMap ({len(pts)} station areas)…")
    failed = 0
    try:
        elements = overpass_entrances()
    except Exception as e:
        say(f"⚠️ The exits didn't download ({e}).")
        if old:
            use(cached, "⚠️ Keeping the saved copy")
        else:
            say("Station pages will have no exits this time.")
        return
    try:
        elements += overpass_places()
    except Exception as e:
        failed = 1
        say(f"⚠️ The nearby places didn't download ({e}).")
        if old:
            use(cached, "⚠️ Keeping the saved copy")
            return
    x = pack(elements, st)
    if not x:
        say("⚠️ No exits came back, so station pages will have no exits this time.")
        if os.path.exists(OUT):
            os.remove(OUT)
        return
    j = {"v": 1, "src": "© OpenStreetMap contributors", "at": time.strftime("%Y-%m-%d"), "key": key, "x": x}
    os.makedirs(ASSETS, exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(j, f, separators=(",", ":"), ensure_ascii=False)
    if CACHE and not failed:
        os.makedirs(CACHE, exist_ok=True)
        shutil.copyfile(OUT, cached)
        open(os.path.join(os.path.dirname(os.path.abspath(CACHE)), "osm-fresh"), "w").write(j["at"])
    n_ex = sum(len(v) for v in x.values())
    n_pl = sum(len(r[3]) for v in x.values() for r in v)
    no_label = sum(1 for v in x.values() for r in v if not r[0])
    say(f"✅ {n_ex:,} exits at {len(x)} stations, with {n_pl:,} nearby places ({os.path.getsize(OUT) / 1e3:.0f} KB)"
        + (f"; {no_label} exits have no letter in OpenStreetMap" if no_label else "")
        + ("; the nearby places are missing this time." if failed else "."))

if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        say(f"⚠️ The exits step hit an error ({type(e).__name__}: {e}). Station pages will have no exits.")
    finish()
