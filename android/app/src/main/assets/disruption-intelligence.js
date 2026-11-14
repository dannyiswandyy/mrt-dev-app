/* Disruption intelligence: LTA GTFS Realtime Service Alerts layered over the fixed timetable.
   This never changes the timetable; it only explains active operational alerts. */
(function(){
  const ALIAS = {CE1:"CC34", CE2:"CC33"};
  const norm = s => ALIAS[String(s||"").toUpperCase()] || String(s||"").toUpperCase();
  function activeAlerts(code){
    const st = window.rtState;
    if (!st || !Array.isArray(st.alerts) || !st.alerts.length) return [];
    const L = window.LINES && window.LINES[code], stations = L && L.stations || [];
    const names = new Set(stations.map(x => String(x[0] || "").toUpperCase()));
    return st.alerts.filter(a => {
      const routes = Array.isArray(a.routes) ? a.routes.map(x => String(x||"").toUpperCase()) : [];
      if (routes.includes(code) || (code === "CG" && routes.includes("CGL")) || routes.includes("CEL") && code === "CCL") return true;
      const stops = Array.isArray(a.stops) ? a.stops.map(norm) : [];
      return stops.some(s => names.has(s));
    });
  }
  function stationNames(code, a){
    const L = window.LINES && window.LINES[code], stations = L && L.stations || [];
    const wanted = new Set((a.stops || []).map(norm));
    return stations.filter(x => wanted.has(String(x[0]||"").toUpperCase())).map(x => x[1]).slice(0,8);
  }
  window.mrtActiveDisruptions = activeAlerts;
  window.mrtDisruptionHTML = function(code){
    const aa = activeAlerts(code);
    if (!aa.length) return "";
    const rows = aa.slice(0,4).map(a => {
      const ns = stationNames(code, a);
      const where = ns.length ? " · " + ns.join(", ") : "";
      const text = a.text || "";
      return `<div class="rt-dis"><b>${esc(a.head || "Service disruption")}</b><span>${esc(text || "LTA has an active service alert.")}${esc(where)}</span></div>`;
    }).join("");
    return `<div class="rt-dis-wrap"><div class="hsub">LTA disruption</div>${rows}</div>`;
  };
  window.mrtDisruptionSnapshot = function(){
    const st = window.rtState;
    return {at: st && st.at || 0, alerts: st && st.alerts || []};
  };
})();