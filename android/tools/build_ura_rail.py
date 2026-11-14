#!/usr/bin/env python3
"""Download the current URA Master Plan rail-line GeoJSON for the Dev map.

The source is data.gov.sg's public dataset:
d_6615da031916f656152eafa013748d73
We keep only RAIL_TYPE=MRT and write a compact GeoJSON asset. Failure is
non-fatal: the app keeps its existing SG Rail Data geometry.
"""
import json, os, sys, urllib.request

DATASET = "d_e8bf3cff62f97300817d1fdcce382584"
API = f"https://api-open.data.gov.sg/v1/public/api/datasets/{DATASET}/poll-download"

def main():
    if len(sys.argv) != 2:
        raise SystemExit("usage: build_ura_rail.py <android-dir>")
    android = sys.argv[1]
    out = os.path.join(android, "app", "src", "main", "assets", "ura-rail-geometry.geojson")
    try:
        req = urllib.request.Request(API, headers={"User-Agent": "MRT-Dev/geometry"})
        with urllib.request.urlopen(req, timeout=30) as r:
            meta = json.load(r)
        if meta.get("code") != 0:
            raise RuntimeError(meta.get("errMsg", "data.gov.sg request failed"))
        url = meta["data"]["url"]
        with urllib.request.urlopen(url, timeout=60) as r:
            fc = json.load(r)
        features = []
        for f in fc.get("features", []):
            p = f.get("properties") or {}
            if p.get("RAIL_TYPE") != "MRT":
                continue
            g = f.get("geometry") or {}
            if g.get("type") == "LineString" and len(g.get("coordinates", [])) >= 2:
                features.append({"type":"Feature","properties":{"RAIL_TYPE":"MRT"},"geometry":g})
        if not features:
            raise RuntimeError("URA dataset returned no MRT LineStrings")
        compact = {"type":"FeatureCollection","name":"URA_MP25_MRT_RAIL","features":features}
        os.makedirs(os.path.dirname(out), exist_ok=True)
        with open(out, "w", encoding="utf-8") as fp:
            json.dump(compact, fp, separators=(",", ":"))
        print(f"URA MRT geometry: {len(features)} LineStrings -> {out}")
    except Exception as e:
        print(f"::warning::URA geometry download failed: {e}")
        if os.path.exists(out):
            print("Using the previously packed URA geometry.")
        else:
            # Valid empty asset keeps the app loader simple.
            os.makedirs(os.path.dirname(out), exist_ok=True)
            with open(out, "w", encoding="utf-8") as fp:
                json.dump({"type":"FeatureCollection","features":[]}, fp)

if __name__ == "__main__":
    main()
