(()=>{"use strict";
  /*
   * Engineering-hours intelligence.
   * This is an inference layer, not an LTA work-order feed.
   * It combines the packed LTA schedule, train-slot continuity and the
   * depot-transfer heuristic to identify likely after-service/depot activity.
   */
  const LINES = ["NSL","EWL","CG","NEL","CCL","DTL","TEL","BP","SE","SW","PE","PW"];
  const MRT = ["NSL","EWL","NEL","CCL","DTL","TEL"];
  const WIN_START = 60;   // 01:00 Singapore
  const WIN_END = 300;    // 05:00 Singapore
  const DEPOT_NAMES = {
    NSL:["Bishan","Jurong East","Woodlands"],
    EWL:["Clementi","Tuas Link","Simei","Tanah Merah"],
    CG:["Expo","Changi Airport"],
    NEL:["Sengkang","Punggol"],
    CCL:["Tai Seng","HarbourFront"],
    DTL:["Bukit Panjang","Tampines","Sungei Bedok"],
    TEL:["Woodlands North","Woodlands"]
  };

  const pad = n => String(n).padStart(2,"0");
  const hm = m => pad(Math.floor(m/60)%24)+":"+pad(Math.round(m)%60);
  const minsNow = () => {
    try { const n = sgNow(); return n.mins; } catch(e) {
      const d = new Date(Date.now()+8*3600e3); return d.getUTCHours()*60+d.getUTCMinutes()+d.getUTCSeconds()/60;
    }
  };
  const dayNow = () => { try { return sgNow().iso; } catch(e) { return new Date(Date.now()+8*3600e3).toISOString().slice(0,10); } };

  function activeWindow(m){
    // Engineering time is an overnight window; 00:00-05:00 is the tail of the
    // previous operating day, but the public guidance commonly describes work
    // as roughly 01:00-04:00. We use 01:00-05:00 as a conservative envelope.
    return m >= WIN_START && m < WIN_END;
  }

  function tripMeta(code, tid){
    try { return TT && TT.lines && TT.lines[code] && TT.lines[code].tripMeta ? TT.lines[code].tripMeta[tid] : null; }
    catch(e){ return null; }
  }

  function candidates(code, iso){
    if (typeof ttTrips !== "function") return [];
    const list = ttTrips(code, iso) || [];
    const out = [];
    for (const r of list){
      const tm = tripMeta(code, r.tid);
      if (!tm) continue;
      const start = Number(tm.start), end = Number(tm.end);
      if (!Number.isFinite(start) || !Number.isFinite(end)) continue;
      const endM = ((end/60)%1440+1440)%1440;
      const startM = ((start/60)%1440+1440)%1440;
      // Trips finishing in the overnight engineering envelope are the useful
      // candidates. The block/continuity test below decides whether they look
      // like a withdrawal rather than an ordinary passenger turn.
      if (endM >= WIN_START && endM < WIN_END){
        const dep = window.mrtDepotTransferFor ? window.mrtDepotTransferFor(code,{ref:{tid:r.tid}}) : null;
        out.push({tid:r.tid,start,end,startM,endM,firstName:tm.firstName||"",lastName:tm.lastName||"",block:tm.block||"",depot:dep||null});
      }
      // A first passenger trip in the engineering envelope can indicate a
      // pre-service depot launch when the existing block heuristic supports it.
      if (startM >= WIN_START && startM < WIN_END){
        const dep = window.mrtDepotTransferFor ? window.mrtDepotTransferFor(code,{ref:{tid:r.tid}}) : null;
        if (dep && dep.type === "depot_departure") out.push({tid:r.tid,start,end,startM,endM,firstName:tm.firstName||"",lastName:tm.lastName||"",block:tm.block||"",depot:dep,preService:true});
      }
    }
    return out;
  }

  function snapshot(){
    const iso = dayNow(), m = minsNow(), active = activeWindow(m), lines = {};
    for (const code of LINES){
      const c = candidates(code, iso);
      const unique = new Map();
      for (const x of c) unique.set((x.tid||"")+"|"+(x.preService?"pre":"post"),x);
      const a = [...unique.values()];
      const post = a.filter(x=>!x.preService && x.depot && x.depot.type==="depot_return");
      const pre = a.filter(x=>x.preService && x.depot && x.depot.type==="depot_departure");
      if (a.length) lines[code]={afterService:post.slice(0,12),beforeService:pre.slice(0,12)};
    }
    return {iso,minute:m,active,window:["01:00","05:00"],lines};
  }

  function render(){
    const host = document.getElementById("favList");
    if (!host || !window.MRT_DEV_ENGINEERING) return;
    let card = document.getElementById("engIntel");
    if (!card){
      card=document.createElement("section");
      card.id="engIntel";
      card.className="fleet";
      host.parentNode.insertBefore(card,host);
    }
    const s=snapshot(), active=s.active;
    const rows=[];
    for(const code of MRT){
      const x=s.lines[code]; if(!x) continue;
      const post=x.afterService.length, pre=x.beforeService.length;
      if(post||pre) rows.push(`<li><div class="m">${code}<i>${active?"ACTIVE":"NIGHT"}</i></div><div class="s">${post?post+" likely after-service":""}${post&&pre?" · ":""}${pre?pre+" likely pre-service":""}</div></li>`);
    }
    card.innerHTML=`<details class="fleet-details" ${active?"open":""}><summary><span><b>Engineering intelligence</b> · ${active?"engineering window active":"overnight window 01:00–05:00"}</span><span class="chev">⌄</span></summary><div class="fleet-body"><ul>${rows.length?rows.join(""):`<li><div class="m">No candidates</div><div class="s">No scheduled depot-linked movement detected for this service day.</div></li>`}</ul><p class="hint" style="margin:9px 0 0">Inferred from LTA timetable continuity and depot-transfer heuristics. This is <b>not</b> an official track-work or engineering-train feed.</p></div></details>`;
  }

  window.mrtEngineeringSnapshot=snapshot;
  window.MRT_DEV_ENGINEERING=true;
  window.mrtEngineeringRender=render;
  setInterval(()=>{ if(document.hidden) return; try{render();}catch(e){} },30000);
  setTimeout(()=>{try{render();}catch(e){}},0);
})();