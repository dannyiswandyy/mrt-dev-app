(()=>{"use strict";
const K="mrt-intelligence-v3",S={anchors:{},observations:[],journeys:[],graph:{},timetable:null,loadedAt:0};
const id=x=>String(x||"").trim().toUpperCase().replace(/\s+/g,"");
const sn=x=>String(x||"").trim().toUpperCase().replace(/[^A-Z0-9]/g,"");
const min=x=>{let m=String(x||"").match(/(\d{1,2}):(\d{2})/);return m?+m[1]*60+ +m[2]:null};
const sec=x=>{if(typeof x==="number")return x;let m=String(x??"").match(/^(\d+):(\d{2})(?::(\d{2}))?/);return m?+m[1]*3600+ +m[2]*60+(+m[3]||0):null};
const n=()=>Date.now(),dayKey=d=>{let x=new Date(d);return x.getFullYear()+String(x.getMonth()+1).padStart(2,"0")+String(x.getDate()).padStart(2,"0")};
const save=()=>{try{S.observations=S.observations.slice(-500);localStorage.setItem(K,JSON.stringify({anchors:S.anchors,observations:S.observations,journeys:S.journeys,graph:S.graph}))}catch(_){}};
try{Object.assign(S,JSON.parse(localStorage.getItem(K)||"{}"))}catch(_){}
S.anchors||={};S.observations=Array.isArray(S.observations)?S.observations:[];S.journeys=Array.isArray(S.journeys)?S.journeys:[];S.graph||={};

function graph(){let g={};S.journeys.forEach(j=>(j.stops||[]).forEach((x,i)=>{let k=sn(x.name||x.id);if(!k)return;(g[k]||={next:{},prev:{}});if(j.stops[i+1]){let q=sn(j.stops[i+1].name||j.stops[i+1].id);g[k].next[q]=(g[k].next[q]||0)+1}if(j.stops[i-1]){let q=sn(j.stops[i-1].name||j.stops[i-1].id);g[k].prev[q]=(g[k].prev[q]||0)+1}}));S.graph=g}
function ingest(d){
 if(!d||typeof d!=="object")return;
 S.timetable=d;S.loadedAt=n();
 let bt={},tr=d.trips||[],names={};
 (d.stops||[]).forEach(x=>names[x.stop_id||x.id||x.code]=x.stop_name||x.name);
 (d.stop_times||[]).forEach(x=>{let t=x.trip_id||x.trip||x.journey_id;if(!t)return;(bt[t]||=[]).push({q:+(x.stop_sequence||x.sequence||0),id:x.stop_id||x.station_id||x.station||x.code,name:x.stop_name||x.station_name||names[x.stop_id]||x.name,arr:sec(x.arrival_time||x.arrival||x.time),dep:sec(x.departure_time||x.departure||x.time)})});
 let out=[];Object.keys(bt).forEach(t=>{let a=bt[t].filter(x=>x.id||x.name).sort((x,y)=>x.q-y.q),m=tr.find(x=>(x.trip_id||x.id||x.journey_id)==t)||{};if(a.length>1)out.push({id:t,line:m.route_id||m.line_id||m.line||"",dir:m.direction_id??"",stops:a})});
 if(out.length){S.journeys=out;graph();save()}
 window.mrtTimetableEngine={raw:d,journeys:S.journeys,loadedAt:S.loadedAt};
 return d;
}
function serviceActive(d,svc,date){
 let k=dayKey(date),s=d.services?.find(x=>Array.isArray(x)&&String(x[0])===String(svc));
 if(!s)return true;
 if(String(k)<String(s[2]||"00000000")||String(k)>String(s[3]||"99999999"))return false;
 let dow=(new Date(date).getDay()+6)%7,mask=String(s[1]||"0000000");
 let active=mask[dow]==="1";
 for(const e of (d.except?.[k]||[])){if(String(e[0])===String(d.services.indexOf(s))){active=e[1]===1}}
 return active;
}
function exactTrips(line,date){
 let d=S.timetable;if(!d?.lines?.[line])return[];
 let L=d.lines[line],out=[];
 for(const t of (L.trips||[])){
   let service=d.services?.[t[2]]; if(!service||!serviceActive(d,service[0],date))continue;
   let p=L.pats?.[t[1]]; if(!p)continue;
   let first=+p[0],step=+p[1]||1,arr=p[2]||[],dep=p[3]||[];
   let stops=[];for(let i=0;i<Math.max(arr.length,dep.length);i++){let idx=first+i*step;stops.push({index:idx,arr:+arr[i],dep:+(dep[i]??arr[i])})}
   out.push({line,tripIndex:t[1],serviceIndex:t[2],start:+t[0],destination:+(p[4]??-1),stops});
 }
 return out;
}
function stationIndex(line,station){let L=S.timetable?.lines?.[line];if(!L)return null;let q=sn(station);for(const j of S.journeys){if(id(j.line)!=id(line))continue;for(let i=0;i<j.stops.length;i++)if(sn(j.stops[i].name||j.stops[i].id)===q)return j.stops[i].index??i}return null}
function scheduled(line,station,date=new Date(),nowSec=null){
 let trips=exactTrips(line,date),idx=stationIndex(line,station),t=nowSec??(date.getHours()*3600+date.getMinutes()*60+date.getSeconds());
 if(idx==null)return[];
 return trips.map(x=>{let s=x.stops.find(z=>z.index===idx);return s?{...s,trip:x}:null}).filter(Boolean).sort((a,b)=>a.arr-b.arr).map(x=>({...x,deltaSec:x.arr-t})).filter(x=>x.deltaSec>=-180).slice(0,20);
}
function position(line,station,at=new Date()){
 let trips=exactTrips(line,at),idx=stationIndex(line,station),t=at.getHours()*3600+at.getMinutes()*60+at.getSeconds()+at.getMilliseconds()/1000;
 if(idx==null)return null;
 for(const tr of trips){let s=tr.stops.find(z=>z.index===idx);if(!s)continue;if(t>=s.arr&&t<=s.dep)return{state:"dwelling",station,trip:tr,progress:0,source:"lta"};
   let a=tr.stops.find(z=>z.index===idx-1*1),b=s;
   if(a&&t>a.dep&&t<b.arr)return{state:"running",from:a.index,to:b.index,progress:(t-a.dep)/(b.arr-a.dep),trip:tr,source:"lta"};
 }
 return null;
}
function cand(t,st,tm){let z=sn(st),a=[];S.journeys.forEach(j=>(j.stops||[]).forEach((x,i)=>{if(sn(x.name||x.id)!=z)return;let q=x.arr??x.dep,d=q==null||tm==null?0:Math.min(Math.abs(q-tm),1440-Math.abs(q-tm));a.push({j,i,stop:x,score:Math.max(0,100-d*4)+(id(j.id)==id(t)?100:0)})}));return a.sort((a,b)=>b.score-a.score).slice(0,8)}
function anchor(t){let a=S.anchors[id(t)];if(a&&a.until<n()){delete S.anchors[id(t)];save();return null}return a}
function record(o){if(!o?.trainId||!o?.station)return null;let z={trainId:id(o.trainId),station:String(o.station),time:o.time||new Date().toISOString(),ts:o.ts||n(),source:o.source||"manual",confidence:o.confidence??100,direction:o.direction||"",event:o.event||"sighting"};z.candidates=cand(z.trainId,z.station,min(z.time));S.observations.push(z);if(z.source==="manual"||z.confidence>=95)S.anchors[z.trainId]={station:z.station,ts:z.ts,until:z.ts+1800000,confidence:Math.max(95,z.confidence),source:z.source};save();return z}
window.mrtReportSighting=(trainId,station,time,direction,event)=>record({trainId,station,time,direction,event});
window.mrtGetTrainTruth=t=>{let a=anchor(t);if(a)return{...a,anchored:true,ageMs:n()-a.ts};let z=S.observations.filter(x=>x.trainId==id(t)).sort((a,b)=>b.ts-a.ts)[0];return z?{station:z.station,confidence:z.confidence,source:z.source,anchored:false,ageMs:n()-z.ts}:null};
window.mrtScoreSighting=(t,s,x)=>cand(t,s,min(x));

const DWELL={interchange:{min:35,max:60,typical:48},nonInterchange:{min:30,max:50,typical:40},terminal:{min:60,max:180,typical:105},source:"configurable model; observations override"};
const TERMINALS={NSL:["NS1","NS28"],EWL:["EW1","EW33"],CG:["CG2"],NEL:["NE1","NE17"],CCL:["CC1","CC29"],DTL:["DT1","DT35"],TEL:["TE1","TE29"]};
function dwellFor(interchange=false,load=.5,terminal=false){let d=terminal?DWELL.terminal:(interchange?DWELL.interchange:DWELL.nonInterchange);return d.min+(d.max-d.min)*Math.max(0,Math.min(1,Number(load)||0))}
function terminalDwellProfile(interchange=false){let d=interchange?DWELL.interchange:DWELL.nonInterchange;return{platformDwellMin:d.min,platformDwellMax:d.max,platformDwellTypical:d.typical,turnbackExtraMin:60,turnbackExtraMax:180}}
function learnedDwell(line,station){let q=S.observations.filter(x=>id(x.line||line)==id(line)&&sn(x.station)==sn(station)&&x.dwellSec!=null).map(x=>+x.dwellSec).filter(x=>x>=10&&x<=300);return q.length?{sample:q.length,median:q.sort((a,b)=>a-b)[Math.floor(q.length/2)]}:null}

window.mrtTimetablePolicy={dwell:DWELL,dwellFor,terminalDwellProfile,terminals:TERMINALS,learnedDwell};
window.mrtIntelligence={state:S,record,anchor,ingestTimetable:ingest,dwellFor,terminalDwellProfile,acceptInference:(t,s,src,c,ts)=>{let a=anchor(t);return a&&a.confidence>=95&&a.ts>(ts||n())-1800000?{station:a.station,source:a.source,confidence:a.confidence,anchored:true}:{station:s,source:src||"inferred",confidence:c||0,anchored:false}},plausible:(t,s,ts)=>{let z=S.observations.filter(x=>x.trainId==id(t)).sort((a,b)=>b.ts-a.ts)[0],g=z&&S.graph[sn(z.station)];return !z||!g||n()-(ts||n())>1200000||g.next[sn(s)]||g.prev[sn(s)]?{ok:true,penalty:0}:{ok:false,penalty:70}},scheduled,position,learnedDwell};

fetch("timetable.json",{cache:"no-store"}).then(r=>r.ok?r.json():null).then(ingest).catch(()=>{});
fetch("intelligence-timetable.json",{cache:"no-store"}).then(r=>r.ok?r.json():null).then(ingest).catch(()=>{});

function discover(){for(let i=0;i<localStorage.length;i++){let k=localStorage.key(i)||"";if(!/(spot|sight|train.*log|log.*train)/i.test(k))continue;try{let v=JSON.parse(localStorage.getItem(k)),a=Array.isArray(v)?v:v?.items||[];a.slice(-50).forEach(x=>{let t=x.trainId||x.train||x.r||x.run,st=x.station||x.stationName||x.stn,tm=x.time||x.timestamp||x.ts;if(t&&st&&!S.observations.some(o=>o.trainId==id(t)&&sn(o.station)==sn(st)&&String(o.time)==String(tm)))record({trainId:t,station:st,time:tm,source:"manual",event:x.event||"sighting"})})}catch(_){}}}
discover();addEventListener("storage",discover);setInterval(discover,2500);
window.mrtIntelligenceSnapshot=()=>({version:K,anchors:Object.keys(S.anchors),observations:S.observations.slice(-20),journeys:S.journeys.length,graphStations:Object.keys(S.graph).length,timetableLines:Object.keys(S.timetable?.lines||{}).length,timetableLoadedAt:S.loadedAt});
})();