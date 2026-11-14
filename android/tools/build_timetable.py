"""Download LTA's official train timetable (GTFS Schedule (Train), LTA DataMall) and pack it for the app.

Runs on GitHub during every build:  python3 tools/build_timetable.py <app project folder> [saved copy]

Writes app/src/main/assets/timetable.json, which the app uses for exact train times. If anything
goes wrong (no connection, unexpected format) it writes no timetable and the app falls back to
estimates, so the build never fails because of this step. A report of what matched is written to
the GitHub job summary.

With a saved copy (the workflow keeps one in the repository, data/lta-timetable.json), every good
download is also saved there, and when LTA's download fails the last good timetable is packed instead,
so every version of the app has LTA's timetable.

timetable.json:
  feed:     {source, built, files}
  services: [[service_id, "1111100" (Mon..Sun), start "YYYYMMDD", end "YYYYMMDD"], ...]
  except:   {"YYYYMMDD": [[service index, 1 = added | 2 = removed], ...]}
  lines:    {NSL: {pats: [[first station index, step +1/-1, [arrive s], [depart s], destination index], ...],
                   trips: [[start s since midnight, pattern index, service index], ...]}, ...}
            The destination is the station the trip is headed for (its headsign), so special-bound trains
            that end partway along the line show where they end; -1 when it's simply where the run ends,
            -2 for a full loop back to where it started.
"""
import csv, hashlib, html, io, json, os, re, shutil, sys, time, urllib.request, zipfile

ROOT = sys.argv[1] if len(sys.argv) > 1 else "."
OUT = os.path.join(ROOT, "app/src/main/assets/timetable.json")
SAVED = sys.argv[2] if len(sys.argv) > 2 else None
LINES = json.load(open(os.path.join(ROOT, "tools/lines.json")))
SUMMARY = os.environ.get("GITHUB_STEP_SUMMARY")
API = "https://datamall2.mytransport.sg/ltaodataservice/GTFSScheduleTrain"
report = []

def say(line=""):
    print(line)
    report.append(line)

def use_saved():
    """LTA's download didn't work: pack the last good timetable instead, if there is one."""
    if not SAVED or not os.path.exists(SAVED):
        say("\nThere's no saved timetable to fall back on yet, so this version of the app uses estimates.")
        return False
    try:
        j = json.load(open(SAVED))
        os.makedirs(os.path.dirname(OUT), exist_ok=True)
        shutil.copyfile(SAVED, OUT)
        ends = max((s[3] for s in j.get("services", [])), default="")
        say(f"\n✅ Packed the last good timetable instead: downloaded {j['feed']['built']}, {len(j['lines'])} lines"
            + (f", covering dates up to {ends[:4]}-{ends[4:6]}-{ends[6:]}" if ends and ends < "2999" else "") + ".")
        if ends and ends < time.strftime("%Y%m%d"):
            say("⚠️ That timetable's dates have passed, so the app uses estimates until LTA's download works again.")
        return True
    except Exception as e:
        say(f"\n⚠️ The saved timetable couldn't be read ({e}), so this version of the app uses estimates.")
        return False

def keep_copy(res):
    """Save a good download to the repository copy, unless it's the same timetable as last time."""
    if not SAVED:
        return
    try:
        strip = lambda j: json.dumps({k: v for k, v in j.items() if k != "feed"}, sort_keys=True)
        old = json.load(open(SAVED)) if os.path.exists(SAVED) else None
        if old and strip(old) == strip(json.loads(json.dumps(res))):
            say("Same timetable as the saved copy.")
            return
        os.makedirs(os.path.dirname(SAVED) or ".", exist_ok=True)
        shutil.copyfile(OUT, SAVED)
        say("Saved a copy to the repository (data/lta-timetable.json), for any build where LTA's download fails.")
    except Exception as e:
        say(f"⚠️ Couldn't keep a copy of the timetable ({e}).")

def finish(ok):
    if not ok:
        if os.path.exists(OUT):
            os.remove(OUT)
        use_saved()
    if SUMMARY:
        with open(SUMMARY, "a") as f:
            f.write("## LTA train timetable\n\n" + "\n".join(report) + "\n")
    sys.exit(0)

# ---------- the LTA key ----------
def legacy_lta_key():
    """Read the old in-repo key only as a compatibility fallback."""
    g = open(os.path.join(ROOT, "app/build.gradle")).read()
    m = re.search(r"LTA_KEY_X',\s*'\"([0-9a-f]+)\"'", g)
    if not m:
        return ""
    mask = hashlib.sha256(b"sg.mrttracker.app").digest()
    hx = m.group(1)
    key = bytes(int(hx[i:i + 2], 16) ^ mask[(i // 2) % 32] for i in range(0, len(hx), 2)).decode()
    print("::add-mask::" + key)
    return key

def lta_key():
    # Prefer the GitHub Actions secret.
    env_key = os.environ.get("LTA_ACCOUNT_KEY", "").strip()
    if env_key:
        print("::add-mask::" + env_key)
        return env_key
    return legacy_lta_key()
def http(url, headers=None, tries=3):
    h = {
        "User-Agent": "MRT-Tracker-Dev/1.0",
        "Accept": "application/json",
        "Cache-Control": "no-cache",
    }
    h.update(headers or {})
    for attempt in range(tries):
        try:
            req = urllib.request.Request(url, headers=h)
            with urllib.request.urlopen(req, timeout=60) as r:
                return r.read()
        except Exception as e:
            if attempt == tries - 1:
                raise
            time.sleep(3 * (attempt + 1))

def find_link(o):
    """Extract the fresh signed GTFS URL from LTA's JSON or Atom/XML response."""
    if isinstance(o, bytes):
        raw = o.decode("utf-8", "replace")
    elif isinstance(o, str):
        raw = o
    else:
        raw = ""

    # DataMall currently returns an Atom/XML feed for these GTFS endpoints in
    # some responses, even when JSON is requested. Search the raw response for
    # the actual HTTPS URL rather than depending on one response shape.
    if raw:
        raw = html.unescape(raw)
        matches = re.findall(r"https://[^\\s<>\"']*gtfs[^\\s<>\"']*", raw, re.I)
        if matches:
            return matches[0].strip()

    if isinstance(o, dict):
        for v in o.values():
            r = find_link(v)
            if r:
                return r
    elif isinstance(o, list):
        for v in o:
            r = find_link(v)
            if r:
                return r
    return None

# ---------- station matching ----------
def norm(s):
    s = s.lower().replace("&", "and")
    s = re.sub(r"\([^)]*\)", " ", s)
    s = re.sub(r"\b(mrt|lrt)\b|\bstation\b|\binterchange\b|\bplatform\b.*$", " ", s)
    return re.sub(r"[^a-z0-9]", "", s)

NAME_IDX, CODE_IDX = {}, {}         # normalised name / code -> [(line, index)]
for line, L in LINES.items():
    for i, (code, name) in enumerate(L["stations"]):
        NAME_IDX.setdefault(norm(name), []).append((line, i))
        CODE_IDX.setdefault(code.upper(), []).append((line, i))
CODE_ALIASES = {"CE1": "CC34", "CE2": "CC33"}
CODE_RE = re.compile(r"\b(NS|EW|CG|NE|CC|CE|DT|TE|BP|SE|SW|PE|PW)\s?(\d{1,2})\b|\b(STC|PTC)\b", re.I)

def stop_candidates(stop, stops):
    """All (line, index) this GTFS stop could be, from station codes first, then names."""
    texts = [stop.get("stop_code", ""), stop.get("stop_id", ""), stop.get("stop_name", "")]
    parent = stops.get(stop.get("parent_station") or "")
    if parent:
        texts += [parent.get("stop_code", ""), parent.get("stop_id", ""), parent.get("stop_name", "")]
    found = []
    for t in texts:
        for m in CODE_RE.finditer(t or ""):
            code = (m.group(1) + m.group(2)).upper() if m.group(1) else m.group(3).upper()
            code = CODE_ALIASES.get(code, code)
            found += CODE_IDX.get(code, [])
    names = [parent.get("stop_name", "")] if parent else []
    names.append(stop.get("stop_name", ""))
    for n in names:
        found += NAME_IDX.get(norm(n or ""), [])
    return sorted(set(found))

MAX_STEP = 2      # stops next to each other on a trip are at most this far apart in the app's station list

def legs_for(seq, line):
    """Split one trip's stops into runs that move one way along `line`. seq: [(cands_on_line, arr, dep)].
    A run also breaks where two stops in a row aren't neighbours in the app's list, such as where the
    Circle Line's loop joins (Promenade and Bayfront), so no run is drawn across the whole line."""
    legs, cur = [], None
    for cands, arr, dep in seq:
        if cur is None:
            cur = {"rows": [(cands, arr, dep)], "idx": [], "dir": 0}
            continue
        if not cur["idx"]:
            prev = cur["rows"][-1][0]
            pairs = [(abs(a - b), a, b) for a in prev for b in cands if a != b]
            if not pairs or min(pairs)[0] > MAX_STEP:
                cur = {"rows": [(cands, arr, dep)], "idx": [], "dir": 0}
                continue
            _, a, b = min(pairs)
            cur["rows"] = [cur["rows"][-1], (cands, arr, dep)]
            cur["idx"] = [a, b]
            cur["dir"] = 1 if b > a else -1
            continue
        last, d = cur["idx"][-1], cur["dir"]
        ahead = [x for x in cands if (x - last) * d > 0]
        nxt = min(ahead, key=lambda x: abs(x - last)) if ahead else None
        if nxt is not None and abs(nxt - last) <= MAX_STEP:
            cur["rows"].append((cands, arr, dep))
            cur["idx"].append(nxt)
        else:                                   # the train turns (or jumps): start a new run from the last stop
            legs.append(cur)
            prow = cur["rows"][-1]
            cur = {"rows": [prow], "idx": [], "dir": 0}
            # a station can appear twice on a line (Bukit Panjang on the BP loop): pick the closest pair
            pairs = [(abs(a - b), a, b) for a in prow[0] for b in cands if b != a]
            if pairs and min(pairs)[0] <= MAX_STEP:
                _, a, b = min(pairs)
                cur["rows"].append((cands, arr, dep))
                cur["idx"] = [a, b]
                cur["dir"] = 1 if b > a else -1
            else:
                cur = {"rows": [(cands, arr, dep)], "idx": [], "dir": 0}
    if cur and cur["idx"]:
        legs.append(cur)
    return legs

def headsign_idx(trip, line, legs):
    """Where a trip is headed, as a station index on its line: the trip's headsign if it names a station
    on the line, else (for a trip that turns or wraps, so its runs end before it does) its last stop.
    -1: where each run ends. -2: a trip that goes round and back to where it started (a full loop)."""
    h = headsign_station(trip, line, legs)
    first = legs[0]["idx"][0]
    if len(legs) > 1 and h >= 0 and (h == first or LINES[line]["stations"][h][0] == LINES[line]["stations"][first][0]):
        return -2
    return h

def headsign_station(trip, line, legs):
    hs = (trip.get("trip_headsign") or "").strip()
    if hs:
        hs = re.sub(r"^\s*(to|towards|for)\s+", "", hs, flags=re.I)
        found = []
        for m in CODE_RE.finditer(hs):
            code = (m.group(1) + m.group(2)).upper() if m.group(1) else m.group(3).upper()
            found += [i for l, i in CODE_IDX.get(CODE_ALIASES.get(code, code), []) if l == line]
        if not found:
            found = [i for l, i in NAME_IDX.get(norm(CODE_RE.sub(" ", hs)), []) if l == line]
        if found:
            last = legs[-1]["idx"][-1]
            return min(found, key=lambda i: abs(i - last))       # a hub listed twice: the one nearest the trip's end
    return legs[-1]["idx"][-1] if len(legs) > 1 else -1

def secs(t):
    h, m, s = (t or "").strip().split(":")
    return int(h) * 3600 + int(m) * 60 + int(s)

def main():
    local = os.environ.get("MRT_GTFS_ZIP")      # for testing with a file instead of downloading
    if local:
        data = open(local, "rb").read()
        z = zipfile.ZipFile(io.BytesIO(data))
        return process(z, data)
    key = lta_key()
    if not key:
        say("⚠️ No LTA key found, so the timetable was skipped.")
        finish(False)
    # GTFS Schedule returns a short-lived pre-signed S3 URL. The URL is tied to
    # the exact object/signature, so if S3 answers 403/404 we must request a
    # completely fresh URL rather than retrying the old signed URL.
    def fetch_meta(active_key):
        api_url = API + "?_=" + str(time.time_ns())
        return http(api_url, {"AccountKey": active_key, "Accept": "application/json"})

    try:
        meta = fetch_meta(key)
    except Exception as api_error:
        say(f"⚠️ LTA GTFS Schedule API request failed: {api_error}")
        fallback = legacy_lta_key() if os.environ.get("LTA_ACCOUNT_KEY", "").strip() else ""
        if fallback and fallback != key:
            try:
                say("⚠️ Retrying the schedule API with the compatibility key.")
                meta = fetch_meta(fallback)
                key = fallback
                say("✅ Compatibility-key request succeeded.")
            except Exception as fallback_error:
                say(f"⚠️ Compatibility-key retry also failed: {fallback_error}")
                finish(False)
        else:
            finish(False)

    data = None
    last_error = None
    for attempt in range(1, 4):
        try:
            link = find_link(meta)
            if not link:
                preview = meta.decode("utf-8", "replace")[:500].replace("\\n", " ")
                say("LTA metadata did not contain a detectable GTFS URL. Response preview: " + re.sub(r'(?i)(X-Amz-[^=]+)=([^&\\s<]+)', r'\\1=[REDACTED]', preview))
                raise ValueError("LTA returned no GTFS download link")
            say(f"LTA returned a temporary GTFS Schedule download link (attempt {attempt}/3); downloading it immediately.")
            # Do not add query parameters or rewrite/URL-decode the signed URL.
            # The signature covers the exact query string generated by LTA.
            data = http(link, {"Accept": "*/*", "User-Agent": "MRT-Tracker-Dev/1.0"})
            z = zipfile.ZipFile(io.BytesIO(data))
            say("✅ Fresh signed GTFS Schedule URL downloaded successfully.")
            break
        except Exception as download_error:
            last_error = download_error
            if attempt >= 3:
                break
            say(f"⚠️ Signed GTFS download failed ({download_error}); requesting a fresh LTA URL.")
            try:
                meta = fetch_meta(key)
            except Exception as refresh_error:
                last_error = refresh_error
                say(f"⚠️ Fresh LTA schedule-link request also failed: {refresh_error}")

    if data is None:
        say(f"⚠️ LTA returned the schedule metadata, but all fresh signed GTFS downloads failed: {last_error}")
        say("This does not fall back to a guessed/static ZIP URL; the next build will retry LTA with a newly generated signed link.")
        finish(False)
    return process(z, data)

def process(z, data):
    def table(name):
        n = next((x for x in z.namelist() if x.split("/")[-1].lower() == name), None)
        if not n:
            return []
        txt = z.read(n).decode("utf-8-sig", "replace")
        return list(csv.DictReader(io.StringIO(txt)))

    say(f"Downloaded **{len(data) // 1024} KB** from LTA DataMall.")
    files = sorted(x.split("/")[-1] for x in z.namelist())
    say("Files: " + ", ".join(f"`{f}`" for f in files))
    say("")
    say("<details><summary>First lines of each file</summary>\n")
    for n in z.namelist():
        if n.endswith(".txt"):
            head = z.read(n).decode("utf-8-sig", "replace").splitlines()[:3]
            say(f"**{n}**\n```\n" + "\n".join(h[:300] for h in head) + "\n```")
    say("</details>\n")

    stops = {s["stop_id"]: s for s in table("stops.txt")}
    trips = {t["trip_id"]: t for t in table("trips.txt")}
    freqs = {}
    for f in table("frequencies.txt"):
        freqs.setdefault(f["trip_id"], []).append(f)
    st_by_trip = {}
    for r in table("stop_times.txt"):
        st_by_trip.setdefault(r["trip_id"], []).append(r)
    cal = table("calendar.txt")
    cdates = table("calendar_dates.txt")

    services, svc_index = [], {}
    def svc(sid):
        if sid not in svc_index:
            svc_index[sid] = len(services)
            services.append([sid, "0000000", "19000101", "29991231"])
        return svc_index[sid]
    for c in cal:
        i = svc(c["service_id"])
        days = "".join("1" if c.get(d, "0").strip() == "1" else "0"
                       for d in ("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"))
        services[i][1:] = [days, c.get("start_date", "19000101").strip(), c.get("end_date", "29991231").strip()]
    exc = {}
    for c in cdates:
        exc.setdefault(c["date"].strip(), []).append([svc(c["service_id"]), int(c.get("exception_type", "1") or 1)])

    stop_cands = {sid: stop_candidates(s, stops) for sid, s in stops.items()}
    unmatched_stops = sorted({stops[sid].get("stop_name", sid) for sid, c in stop_cands.items()
                              if not c and stops[sid].get("location_type", "0") in ("", "0")})

    out = {ln: {"pats": [], "trips": []} for ln in LINES}
    pat_idx = {ln: {} for ln in LINES}
    per_line = {ln: 0 for ln in LINES}
    skipped = []

    for tid, rows in st_by_trip.items():
        if tid not in trips:
            continue
        rows.sort(key=lambda r: int(r["stop_sequence"]))
        cands = [stop_cands.get(r["stop_id"], []) for r in rows]
        # which line covers most of this trip's stops?
        score = {}
        for cs in cands:
            for ln in {c[0] for c in cs}:
                score[ln] = score.get(ln, 0) + 1
        if not score:
            skipped.append((tid, "no stops matched"))
            continue
        line = max(score, key=lambda l: (score[l], -len(LINES[l]["stations"])))
        if score[line] < 2 or score[line] < 0.9 * len(rows):
            skipped.append((tid, f"only {score[line]}/{len(rows)} stops on {line}"))
            continue
        seq = []
        for r, cs in zip(rows, cands):
            on = sorted(i for l, i in cs if l == line)
            if not on:
                continue
            try:
                arr = secs(r.get("arrival_time") or r.get("departure_time"))
                dep = secs(r.get("departure_time") or r.get("arrival_time"))
            except Exception:
                continue
            seq.append((on, arr, dep))
        sid = svc(trips[tid]["service_id"])
        trip_t0 = seq[0][2] if seq else 0
        starts_extra = []
        for f in freqs.get(tid, []):
            a, b, h = secs(f["start_time"]), secs(f["end_time"]), int(f["headway_secs"])
            starts_extra += list(range(a, b, max(30, h)))
        legs = legs_for(seq, line)
        head = headsign_idx(trips[tid], line, legs) if legs else -1
        for leg in legs:
            idx, d = leg["idx"], leg["dir"]
            t0 = leg["rows"][0][2]
            times = {}
            for (cs, arr, dep), i in zip(leg["rows"], idx):
                times[i] = (arr - t0, dep - t0)
            times[idx[0]] = (0, 0)
            span = list(range(idx[0], idx[-1] + d, d))
            known = [i for i in span if i in times]
            arrs, deps = [], []
            for i in span:
                if i in times:
                    a, b = times[i]
                else:                           # not listed (shouldn't happen): interpolate
                    lo = max(k for k in known if (k - i) * d < 0)
                    hi = min(k for k in known if (k - i) * d > 0)
                    frac = abs(i - lo) / abs(hi - lo)
                    a = b = round(times[lo][1] + frac * (times[hi][0] - times[lo][1]))
                arrs.append(a)
                deps.append(max(a, b))
            key = (idx[0], d, tuple(arrs), tuple(deps), head)
            if key not in pat_idx[line]:
                pat_idx[line][key] = len(out[line]["pats"])
                out[line]["pats"].append([idx[0], d, arrs, deps, head])
            p = pat_idx[line][key]
            if starts_extra:
                for s0 in starts_extra:         # frequency-based trips: one run per interval
                    out[line]["trips"].append([s0 + (t0 - trip_t0), p, sid])
            else:
                out[line]["trips"].append([t0, p, sid])
            per_line[line] += 1

    for ln in out:
        out[ln]["trips"].sort()
    lines_ok = [ln for ln in out if out[ln]["trips"]]

    def special(ln):
        """Runs headed somewhere other than the end of the line (special-bound trains). Not counted on
        loops, where most runs are headed back round to where they started."""
        if ln in ("CCL", "BP", "SE", "SW", "PE", "PW"):
            return "–"
        last = len(LINES[ln]["stations"]) - 1
        ends = [p[4] if p[4] >= 0 else -1 if p[4] == -2 else p[0] + p[1] * (len(p[2]) - 1) for p in out[ln]["pats"]]
        ends = [e if e >= 0 else (last if p[1] > 0 else 0) for e, p in zip(ends, out[ln]["pats"])]
        return sum(1 for t in out[ln]["trips"] if ends[t[1]] != (last if out[ln]["pats"][t[1]][1] > 0 else 0))
    say("| Line | Train runs | Stop patterns | Special-bound runs |")
    say("|---|---|---|---|")
    for ln in LINES:
        say(f"| {ln} | {len(out[ln]['trips'])} | {len(out[ln]['pats'])} | {special(ln)} |")
    say("")
    say(f"Service calendars: {len(services)} · dates with exceptions: {len(exc)}")
    if unmatched_stops:
        say(f"\n⚠️ Stops not matched to a station ({len(unmatched_stops)}): " + ", ".join(unmatched_stops[:40]))
    if skipped:
        say(f"\n⚠️ Trips skipped: {len(skipped)} of {len(st_by_trip)}. Examples: " +
            "; ".join(f"`{t}` ({why})" for t, why in skipped[:8]))
    if not lines_ok:
        say("\n⚠️ No lines could be matched to the app's stations.")
        finish(False)

    feed = {"source": "LTA DataMall, GTFS Schedule (Train)", "built": time.strftime("%Y-%m-%d", time.gmtime()),
            "lines": lines_ok}
    res = {"feed": feed, "services": services, "except": exc,
           "lines": {ln: out[ln] for ln in lines_ok}}
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w") as f:
        json.dump(res, f, separators=(",", ":"))
    say(f"\n✅ Timetable packed for {len(lines_ok)} lines ({os.path.getsize(OUT) // 1024} KB). "
        f"Lines without data use estimates: {', '.join(l for l in LINES if l not in lines_ok) or 'none'}.")
    keep_copy(res)
    finish(True)

if __name__ == "__main__":
    try:
        main()
    except SystemExit:
        raise
    except Exception as e:
        say(f"⚠️ The timetable step hit an error ({type(e).__name__}: {e}).")
        finish(False)
