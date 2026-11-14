(()=>{"use strict";
const DEPOT_STATIONS={
  NSL:new Set(["Bishan","Jurong East","Woodlands"]),
  EWL:new Set(["Clementi","Tuas Link","Simei","Tanah Merah"]),
  CG:new Set(["Expo","Changi Airport"]),
  NEL:new Set(["Sengkang","Punggol"]),
  CCL:new Set(["Tai Seng","HarbourFront"]),
  DTL:new Set(["Bukit Panjang","Tampines","Sungei Bedok"]),
  TEL:new Set(["Woodlands North","Woodlands"])
};
const MIN_GAP=12*60;
let cacheKey="", cache=new Map();

let TT=null;
function loadTT(){
  if(TT)return TT;
  try{if(window.MrtApp&&MrtApp.getTimetable)TT=JSON.parse(MrtApp.getTimetable())}catch(_){}
  if(TT)return TT;
  return null;
}
function meta(line){
  const L=loadTT();
  return L?.lines?.[line]?.tripMeta||null;
}
function build(line){
  const M=meta(line); if(!M)return new Map();
  const groups=new Map();
  for(const [tid,x] of Object.entries(M)){
    if(!x?.block)continue;
    if(!groups.has(x.block))groups.set(x.block,[]);
    groups.get(x.block).push({...x,tid});
  }
  const out=new Map();
  for(const [block,a] of groups){
    a.sort((u,v)=>u.start-v.start);
    for(let i=0;i<a.length;i++){
      const x=a[i], prev=a[i-1], next=a[i+1];
      if(prev){
        const gap=x.start-prev.end;
        if(gap>=MIN_GAP && DEPOT_STATIONS[line]?.has(x.firstName||"")){
          out.set(x.tid,{type:"depot_departure",confidence:Math.min(94,70+Math.min(20,Math.round((gap-720)/60))),station:x.firstName,gapSec:gap,block});
        }
      }
      if(next){
        const gap=next.start-x.end;
        if(gap>=MIN_GAP && DEPOT_STATIONS[line]?.has(x.lastName||"")){
          out.set(x.tid,{type:"depot_return",confidence:Math.min(94,70+Math.min(20,Math.round((gap-720)/60))),station:x.lastName,gapSec:gap,block});
        }
      }
    }
  }
  return out;
}
function candidates(line){
  const k=line;
  if(k!==cacheKey){cacheKey=k;cache=build(line)}
  return cache;
}
window.mrtDepotTransferFor=(line,t)=>{
  const tid=t?.ref?.tid||t?.tid||"";
  const x=candidates(line).get(tid);
  return x?{...x,kind:x.type==="depot_return"?"Likely depot transfer after service":"Likely depot transfer before service"}:null;
};
window.mrtDepotTransferSnapshot=()=>({lines:Object.fromEntries(Object.keys(DEPOT_STATIONS).map(l=>[l,candidates(l).size]))});
})();