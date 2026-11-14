(()=>{"use strict";
/*
 * MRT App Operating History
 * Local-only operating memory built from the app's LTA timetable/live train model.
 * It does not scrape or import third-party train databases.
 *
 * Records a compact timeline: line -> train run/slot -> station progression -> ETA trend ->
 * terminal layover/turnback -> observed trainset evidence. Old records are capped locally.
 */
const KEY="mrt.operating.history.v1", MAX=9000, SAMPLE_MS=45000;
let state={day:"",events:[],last:{}};
try{state=JSON.parse(localStorage.getItem(KEY)||"")||state}catch(e){}
const sgDay=at=>new Date(Number(at||0)+8*3600e3).toISOString().slice(0,10);
const nowMs=()=>Date.now();
const slotOf=tid=>{const m=String(tid||"").match(/^[^_]+_[^_]+_[^_]+_(\d+)(?:_|$)/);return m?m[1]:""};
const stationName=(code,i,iso)=>{
 try{const a=activeStations(LINES[code],iso),x=a[i];return x?x[1]:""}catch(e){return""}
};
function resetIfNeeded(day){
 if(state.day!==day){state={day,events:[],last:{}};save();}
}
function save(){try{localStorage.setItem(KEY,JSON.stringify({day:state.day,events:state.events,last:state.last}))}catch(e){
 // If storage is full, retain the newest half.
 try{state.events=state.events.slice(-Math.floor(MAX/2));localStorage.setItem(KEY,JSON.stringify({day:state.day,events:state.events,last:state.last}))}catch(_){}
}}
function observe(code,now,trains){
 const iso=now?.iso||sgDay(nowMs()); resetIfNeeded(iso);
 const ts=nowMs(), bucket=Math.floor(ts/SAMPLE_MS);
 for(const t of Array.isArray(trains)?trains:[]){
   if(!t||!t.id)continue;
   const tid=t.ref?.tid||"";
   const slot=slotOf(tid);
   const key=code+"|"+t.id;
   const prev=state.last[key];
   // Ignore repeated frames unless the train has materially changed state.
   const eta=Number.isFinite(t.eta)?Math.round(t.eta*60):null;
   const pos=Number.isFinite(t.idx)?Math.round(t.idx):null;
   const sig=[bucket,pos,eta==null?"":Math.round(eta/15),t.atStation?1:0,t.layover?1:0,t.prev||""].join("|");
   if(prev&&prev.sig===sig)continue;
   state.last[key]={sig,ts};
   const e={ts,code,id:t.id,prev:t.prev||"",tid,slot,dir:t.dir??null,pos,station:stationName(code,pos,iso),
     next:Number.isFinite(t.next)?Math.round(t.next):null,eta,at:t.atStation?1:0,lay:t.layover?1:0,
     to:Number.isFinite(t.to)?Math.round(t.to):null};
   state.events.push(e);
 }
 if(state.events.length>MAX)state.events.splice(0,state.events.length-MAX);
 // Drop stale per-train keys so the map cannot grow forever.
 const cutoff=ts-6*3600e3;
 for(const k of Object.keys(state.last))if(state.last[k].ts<cutoff)delete state.last[k];
 if(ts-(state._save||0)>30000){state._save=ts;save();}
}
function eventsFor(line,slot,iso){
 resetIfNeeded(iso||state.day);
 const s=String(slot||"").replace(/^T/i,"");
 return state.events.filter(e=>e.code===line&&(!s||e.slot===s));
}
function summarise(line,slot,iso){
 const ev=eventsFor(line,slot,iso), runs=new Map(), stations=[], seen=new Set();
 for(const e of ev){
   const k=e.tid||e.id;
   let r=runs.get(k);if(!r)runs.set(k,r={tid:e.tid,id:e.id,slot:e.slot,first:e.ts,last:e.ts,firstPos:e.pos,lastPos:e.pos,stations:[],maxLate:null,turn:0});
   r.last=e.ts;if(r.first===e.ts)r.first=e.ts;
   if(e.pos!=null)r.lastPos=e.pos;if(r.firstPos==null)r.firstPos=e.pos;
   if(e.station&&!seen.has(k+"|"+e.station)){seen.add(k+"|"+e.station);r.stations.push(e.station);stations.push(e.station);}
   if(e.eta!=null)r.maxLate=r.maxLate==null?e.eta:Math.max(r.maxLate,e.eta);
   if(e.lay)r.turn=1;
 }
 const arr=[...runs.values()].sort((a,b)=>a.first-b.first);
 return {line,slot:String(slot||"").replace(/^T/i,""),iso:iso||state.day,observations:ev.length,runs:arr,stations:[...new Set(stations)]};
}
function latest(line,slot,iso){const ev=eventsFor(line,slot,iso);return ev.length?ev[ev.length-1]:null}
function explain(line,slot,iso){
 const s=summarise(line,slot,iso);if(!s.observations)return"No operating history yet.";
 const r=s.runs.length;const last=s.runs[r-1];
 return `Slot ${s.slot||"unknown"} · ${r} observed run${r===1?"":"s"} · ${s.stations.length} station${s.stations.length===1?"":"s"} tracked${last?.turn?" · terminal turnback observed":""}.`;
}
window.mrtHistoryObserve=observe;
window.mrtOperatingHistory=(line,slot,iso)=>summarise(line,slot,iso);
window.mrtOperatingHistoryLatest=latest;
window.mrtOperatingHistoryExplain=explain;
window.mrtOperatingHistorySnapshot=()=>state.events.slice(-MAX);
window.mrtOperatingHistoryClear=()=>{state={day:sgDay(nowMs()),events:[],last:{}};save();};
})();