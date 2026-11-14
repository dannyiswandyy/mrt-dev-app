# MRT Dev: shared sightings (Cloudflare Worker)

When you use **Spot this train** with a set number, MRT Dev can send the sighting here, and every phone with
shared sightings turned on sees it: the train card shows "601/602 · 3 spotters · last 14:18 at Woodlands", and
**rare-train alerts** tell you when a set you're watching is out today.

What's kept, for 3 days: line, train slot, set, station, time, direction, model, and a random id the app makes
for each phone (to count spotters and limit how much one phone sends). No names, accounts or locations.

## Set it up (about 10 minutes, free)

You already have a Cloudflare account for the website's relay, so this is the same steps again.

### 1. A KV namespace for the sightings
Cloudflare dashboard → **Storage & Databases** → **KV** → **Create** → name it `mrt-spots`.

### 2. The Worker
1. **Workers & Pages** → **Create** → **Create Worker** → name it `mrt-spots` → **Deploy**.
2. **Edit code** → delete everything → paste the whole of `worker-spots/worker.js` → **Deploy**.
3. Note its address, e.g. `https://mrt-spots.YOUR-NAME.workers.dev`.

### 3. Connect the KV and add the key
Worker → **Settings** → **Bindings** → **Add** → **KV namespace**: variable name `SPOTS`, namespace `mrt-spots`.

Worker → **Settings** → **Variables and Secrets** → **Add**:

| Type | Name | Value |
|---|---|---|
| Secret | `SPOTS_KEY` | any long random password you make up (e.g. 30 letters and numbers) |

Click **Deploy**.

### 4. Turn it on in MRT Dev
Spotter's log → **Shared sightings** → paste the Worker's address and the same `SPOTS_KEY` → turn on
**Share my sightings**. Do the same on any other phone that should share.

## Check it

```
curl -H "X-Spots-Key: YOUR-KEY" https://mrt-spots.YOUR-NAME.workers.dev/sightings
```

should answer `{"day":"…","items":[]}`. A wrong key answers `{"error":"Wrong key"}`.

## Notes

- The key is in the app's settings on each phone, so anyone you give it to can send sightings. Change `SPOTS_KEY`
  (and the app setting) if it gets out.
- Limits: 200 sightings per phone per day. The free plan allows 1,000 KV writes a day (2 per sighting).
- This doesn't send anything to SGTrains.
