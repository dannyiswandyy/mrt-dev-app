"""Pack every MRT/LRT station exit, and the places near each one, for the station pages and the map.

Runs on GitHub during every build:  python3 tools/build_exits.py <app project folder> [cache folder]
After a fresh download it also writes "osm-fresh" next to the cache folder, so the workflow saves the new copy.

Exits come from LTA's official list of MRT and LRT station exits ("Train Station Exit Point", published on
LTA DataMall and data.gov.sg). If neither can be downloaded, the station entrances mapped on OpenStreetMap
(railway=subway_entrance / train_station_entrance, with their letter or number in "ref") are used instead.
The places near each exit (bus interchanges, malls, schools, hospitals, parks, libraries…) always come
from OpenStreetMap (data (c) OpenStreetMap contributors, ODbL), through the Overpass API.
A copy is kept in the cache folder between builds, so OpenStreetMap is only asked again once a month,
or when stations change. If the download fails, the last saved copy is used; with neither, station pages
simply have no exits section. This step never fails the build.

exits.json:
  v: 2, src: attribution for the places, exsrc: where the exits came from, at: "YYYY-MM-DD", key: station set
  x: {station code: [[exit label, x, y, [[place name, kind], ...]], ...]}
     x, y in the map's own metres (x east, y south of central Singapore); each exit is listed under the
     station code nearest to it, and places under the exit nearest to them (up to 4 per exit)
"""
import hashlib, io, json, math, os, re, shutil, struct, sys, time, urllib.parse, urllib.request, zipfile

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
LTA_ZIP = "https://datamall.lta.gov.sg/content/dam/datamall/datasets/Geospatial/TrainStationExit.zip"
GOV_DATASET = "d_b39d3a0871985372d7e1637193335da5"     # data.gov.sg: "LTA MRT Station Exit (GEOJSON)"
GOV_POLL = f"https://api-open.data.gov.sg/v1/public/api/datasets/{GOV_DATASET}/poll-download"
STATION_R = 500       # an exit without a matching station name goes to the nearest station within this
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
    return ask(f'[out:json][timeout:120];node["railway"~"^(subway_entrance|train_station_entrance)$"]({BBOX});out body qt;', "Exits")

def overpass_places():
    """Named places of the kinds in KINDS across Singapore, as single points (centres for areas)."""
    parts = []
    for k in sorted({k for k, _, _ in KINDS}):
        vals = "|".join(sorted({v for kk, v, _ in KINDS if kk == k}))
        parts.append(f'nwr["{k}"~"^({vals})$"]["name"]({BBOX});')
    return ask(f"[out:json][timeout:240];({''.join(parts)});out tags center qt;", "Places")

# ---- LTA's official exits ----

def get(url, timeout=90):
    req = urllib.request.Request(url, headers={"User-Agent": "MRTTracker-build/1.0 (GitHub Actions; station exits)"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()

def svy21_to_latlon(E, N):
    """SVY21 (Singapore's grid, EPSG:3414) to latitude/longitude: inverse transverse Mercator on WGS84."""
    a, f = 6378137.0, 1 / 298.257223563
    lat0, lon0, k0, FE, FN = math.radians(1 + 22 / 60), math.radians(103 + 50 / 60), 1.0, 28001.642, 38744.572
    e2 = 2 * f - f * f; e4, e6 = e2 * e2, e2 ** 3; ep2 = e2 / (1 - e2)
    def M(p):
        return a * ((1 - e2 / 4 - 3 * e4 / 64 - 5 * e6 / 256) * p - (3 * e2 / 8 + 3 * e4 / 32 + 45 * e6 / 1024) * math.sin(2 * p)
                    + (15 * e4 / 256 + 45 * e6 / 1024) * math.sin(4 * p) - (35 * e6 / 3072) * math.sin(6 * p))
    mu = (M(lat0) + (N - FN) / k0) / (a * (1 - e2 / 4 - 3 * e4 / 64 - 5 * e6 / 256))
    e1 = (1 - math.sqrt(1 - e2)) / (1 + math.sqrt(1 - e2))
    p1 = (mu + (3 * e1 / 2 - 27 * e1 ** 3 / 32) * math.sin(2 * mu) + (21 * e1 ** 2 / 16 - 55 * e1 ** 4 / 32) * math.sin(4 * mu)
          + (151 * e1 ** 3 / 96) * math.sin(6 * mu) + (1097 * e1 ** 4 / 512) * math.sin(8 * mu))
    C1, T1 = ep2 * math.cos(p1) ** 2, math.tan(p1) ** 2
    N1 = a / math.sqrt(1 - e2 * math.sin(p1) ** 2)
    R1 = a * (1 - e2) / (1 - e2 * math.sin(p1) ** 2) ** 1.5
    D = (E - FE) / (N1 * k0)
    lat = p1 - (N1 * math.tan(p1) / R1) * (D ** 2 / 2 - (5 + 3 * T1 + 10 * C1 - 4 * C1 ** 2 - 9 * ep2) * D ** 4 / 24
                                          + (61 + 90 * T1 + 298 * C1 + 45 * T1 ** 2 - 252 * ep2 - 3 * C1 ** 2) * D ** 6 / 720)
    lon = lon0 + (D - (1 + 2 * T1 + C1) * D ** 3 / 6 + (5 - 2 * C1 + 28 * T1 - 3 * C1 ** 2 + 8 * ep2 + 24 * T1 ** 2) * D ** 5 / 120) / math.cos(p1)
    return math.degrees(lat), math.degrees(lon)

def read_dbf(b):
    n, hlen, rlen = struct.unpack("<IHH", b[4:12])
    fields, o = [], 32
    while b[o] != 0x0D:
        name = b[o:o + 11].split(b"\0")[0].decode("latin-1"); fields.append((name, b[o + 16])); o += 32
    rows = []
    for i in range(n):
        r = b[hlen + i * rlen: hlen + (i + 1) * rlen]
        if not r or r[0:1] == b"*":
            rows.append(None); continue
        row, p = {}, 1
        for name, ln in fields:
            row[name] = r[p:p + ln].decode("utf-8", "replace").strip(); p += ln
        rows.append(row)
    return rows

def read_shp_points(b):
    pts, o = [], 100
    while o + 8 <= len(b):
        ln = struct.unpack(">i", b[o + 4:o + 8])[0] * 2
        c = b[o + 8:o + 8 + ln]
        t = struct.unpack("<i", c[0:4])[0] if len(c) >= 4 else 0
        pts.append(struct.unpack("<2d", c[4:20]) if t in (1, 11, 21) and len(c) >= 20 else None)
        o += 8 + ln
    return pts

def from_lta_zip():
    """LTA DataMall's exit layer: a shapefile in SVY21."""
    z = zipfile.ZipFile(io.BytesIO(get(LTA_ZIP)))
    shp = next(n for n in z.namelist() if n.lower().endswith(".shp"))
    dbf = next(n for n in z.namelist() if n.lower().endswith(".dbf"))
    pts, rows = read_shp_points(z.read(shp)), read_dbf(z.read(dbf))
    out = []
    for p, r in zip(pts, rows):
        if not p or not r:
            continue
        lat, lon = svy21_to_latlon(p[0], p[1]) if p[0] > 1000 else (p[1], p[0])
        name = next((r[k] for k in r if k.upper().startswith("STN") or k.upper().startswith("STATION")), "")
        code = next((r[k] for k in r if "EXIT" in k.upper()), "")
        out.append((name, code, lat, lon))
    return out

def from_data_gov():
    """data.gov.sg's copy, as GeoJSON in latitude/longitude."""
    j = json.loads(get(GOV_POLL))
    url = (j.get("data") or {}).get("url")
    if not url:
        raise RuntimeError(j.get("errorMsg") or "no download link")
    g = json.loads(get(url, 120))
    out = []
    for f in g.get("features", []):
        pr, geo = f.get("properties") or {}, f.get("geometry") or {}
        if geo.get("type") != "Point":
            continue
        if "Description" in pr:   # KML-style: the attributes are in an HTML table
            pr = dict(re.findall(r"<th>\s*([^<]+?)\s*</th>\s*<td>\s*([^<]*?)\s*</td>", pr["Description"]))
        name = next((v for k, v in pr.items() if k.upper().startswith(("STATION", "STN"))), "")
        code = next((v for k, v in pr.items() if "EXIT" in k.upper()), "")
        lon, lat = geo["coordinates"][:2]
        out.append((name, code, lat, lon))
    return out

def official_exits():
    """LTA's exits as (station name, exit, lat, lon), and where they came from; None if neither source works."""
    for fn, label in ((from_lta_zip, "LTA DataMall"), (from_data_gov, "data.gov.sg (LTA)")):
        try:
            rows = [r for r in fn() if 1.1 < r[2] < 1.5 and 103.5 < r[3] < 104.2]
            if len(rows) > 200:
                say(f"✅ {len(rows)} exits from {label}.")
                return rows, label
            say(f"⚠️ {label} sent only {len(rows)} exits, so it wasn't used.")
        except Exception as e:
            say(f"⚠️ Exits from {label} didn't download ({type(e).__name__}: {e}).")
    return None, None

def station_names():
    """App station name for each code, from tools/lines.json."""
    try:
        L = json.load(open(os.path.join(ROOT, "tools/lines.json"), encoding="utf-8"))
        return {s[0]: s[1] for l in L.values() for s in l.get("stations", [])}
    except Exception:
        return {}

def simple(name):
    name = re.sub(r"\b(MRT|LRT)\b|\bSTATION\b|\bINTERCHANGE\b", " ", str(name or ""), flags=re.I)
    return re.sub(r"[^a-z0-9]", "", name.lower())

def official_to_elements(rows):
    """LTA's exits in the same shape as OpenStreetMap entrances, with the station's name kept for matching."""
    out = []
    for i, (name, code, lat, lon) in enumerate(rows):
        lab = re.sub(r"^\s*exit\s*", "", str(code or ""), flags=re.I).strip().upper()
        out.append({"type": "node", "id": -1 - i, "lat": lat, "lon": lon,
                    "tags": {"railway": "subway_entrance", "ref": lab, "_station": name}})
    return out

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
            ex = {"label": exit_label(tags), "x": x, "y": y, "near": []}
            if "_station" in tags:
                ex["station"] = tags["_station"]
            exits.append(ex)
        else:
            r = kind(tags)
            name = (tags.get("name:en") or tags.get("name") or "").strip()
            if r is not None and name and not re.search(r"\b(MRT|LRT)\b.*\bstation\b|\bexit\b", name, re.I):
                places.append((r, x, y, name, KINDS[r][2]))
    # each exit goes under its own station (by name, for LTA's exits), or else the station code nearest to it
    codes = list(st.items())
    names = station_names()
    by_name = {}
    for c, p in codes:
        by_name.setdefault(simple(names.get(c, "")), []).append((c, p))
    by_code = {}
    for ex in exits:
        cands = by_name.get(simple(ex.get("station", ""))) if ex.get("station") else None
        limit = STATION_R * 2 if cands else (STATION_R if ex.get("station") is not None else RADIUS)
        c, d = min(((c, math.hypot(ex["x"] - p[0], ex["y"] - p[1])) for c, p in (cands or codes)), key=lambda t: t[1])
        if d <= limit:
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
    if old and old.get("v") == 2 and old.get("key") == key:
        age = (time.time() - time.mktime(time.strptime(old["at"], "%Y-%m-%d"))) / 86400
        if age < MAX_AGE_DAYS:
            use(cached, "✅ Using the saved copy")
            return
    rows, exsrc = official_exits()
    say(f"Downloading {'nearby places' if rows else 'station exits and nearby places'} across Singapore from OpenStreetMap ({len(pts)} station areas)…")
    failed = 0
    if rows:
        elements = official_to_elements(rows)
    else:
        exsrc = "OpenStreetMap"
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
        if old and not rows:
            use(cached, "⚠️ Keeping the saved copy")
            return
    n_in = sum(1 for e in elements if (e.get("tags") or {}).get("railway") in ("subway_entrance", "train_station_entrance"))
    say(f"{n_in:,} exits ({exsrc}) and {len(elements) - n_in:,} nearby places (OpenStreetMap) to match up.")
    x = pack(elements, st)
    if not x:
        say("⚠️ No exits came back, so station pages will have no exits this time.")
        if os.path.exists(OUT):
            os.remove(OUT)
        return
    j = {"v": 2, "src": "© OpenStreetMap contributors", "exsrc": exsrc, "at": time.strftime("%Y-%m-%d"), "key": key, "x": x}
    os.makedirs(ASSETS, exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(j, f, separators=(",", ":"), ensure_ascii=False)
    if CACHE and not failed and exsrc != "OpenStreetMap":
        os.makedirs(CACHE, exist_ok=True)
        shutil.copyfile(OUT, cached)
        open(os.path.join(os.path.dirname(os.path.abspath(CACHE)), "osm-fresh"), "w").write(j["at"])
    names = station_names()
    missing = sorted({names[c] for c in st if c in names} - {names[c] for c in x if c in names})
    if missing:
        say(f"Stations with no exits: {', '.join(missing)}.")
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
