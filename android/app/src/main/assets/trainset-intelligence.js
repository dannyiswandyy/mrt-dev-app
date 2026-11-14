(()=>{"use strict";
/*
 * MRT App Trainset Intelligence
 * Independent evidence layer. Uses only MRT App sightings + LTA-derived slot IDs.
 * It never imports or scrapes third-party trainset databases.
 */
const DAY=864e5;
const normSlot=v=>{const s=String(v||"").trim(),m=s.match(/^(?:T)?(\d{2,4})$/i);return m?m[1]:s};
const day=at=>new Date(Number(at||0)+8*3600e3).toISOString().slice(0,10);
const spots=()=>{try{return Array.isArray(window.spotLog?.())?window.spotLog():[]}catch(e){return[]}};
const esc=s=>String(s||"").replace(/[&<>"]/g,m=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;"}[m]));
function analyse(line,slot,iso){
 const rows=new Map(), list=spots().filter(s=>s?.line===line&&normSlot(s.slot)===normSlot(slot)&&day(s.at)===iso&&s.set);
 for(const s of list){
  const set=String(s.set).trim(); if(!set)continue;
  let r=rows.get(set); if(!r)rows.set(set,r={set,count:0,last:0,stations:new Set(),models:new Set(),items:[]});
  r.count++; r.last=Math.max(r.last,Number(s.at)||0); if(s.st)r.stations.add(s.st); if(s.model)r.models.add(s.model); r.items.push(s);
 }
 const ranked=[...rows.values()].sort((a,b)=>b.count-a.count||b.last-a.last), total=list.length;
 if(!ranked.length)return{line,slot:normSlot(slot),iso,total,ranked,best:null,confidence:0,status:"Unknown",conflict:false};
 const b=ranked[0], second=ranked[1]; let score=35+Math.min(42,b.count*11);
 if(b.count>=2)score+=8;
 if(b.stations.size>=2)score+=7;
 if(total&&b.count/total>=.8)score+=8;
 const conflict=!!second&&second.count>=Math.max(1,Math.ceil(b.count*.5));
 if(conflict)score=Math.min(score,74);
 score=Math.min(99,score);
 const status=score>=90?"Confirmed":score>=75?"Strong inference":score>=55?"Probable":"Unknown";
 return{line,slot:normSlot(slot),iso,total,ranked,best:b,confidence:score,status,conflict};
}
window.mrtTrainsetEvidence=analyse;
window.mrtTrainsetSnapshot=()=>{const seen=new Set(),out=[];for(const s of spots()){if(!s?.line||!s?.slot||!s?.set)continue;const slot=normSlot(s.slot),iso=day(s.at),k=s.line+"|"+slot+"|"+iso;if(seen.has(k))continue;seen.add(k);out.push(analyse(s.line,slot,iso))}return out};
window.mrtTrainsetConfidence=(line,slot,iso)=>analyse(line,slot,iso).confidence;
window.mrtTrainsetExplain=(line,slot,iso)=>{
 const e=analyse(line,slot,iso); if(!e.best)return "No independent trainset sighting yet.";
 const stations=[...e.best.stations].slice(0,4).join(", ");
 return e.best.set+" · "+e.status+" · "+e.confidence+"% confidence · "+e.best.count+" sighting"+(e.best.count===1?"":"s")+" across "+(e.best.stations.size||1)+" station"+(e.best.stations.size===1?"":"s")+(stations?" ("+stations+")":"")+".";
};
})();