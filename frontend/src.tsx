import React,{useEffect,useRef,useState}from 'react'; import {createRoot} from 'react-dom/client'; import {BarChart,Bar,XAxis,YAxis,Tooltip,ResponsiveContainer} from 'recharts'; import './style.css';

// ---------- types & api ----------
type Role='APPLICANT'|'LOAN_OFFICER'|'ADMIN';
type Ledger={feature:string;feature_value:string|number;shap_contribution:number};
type Application={id:string;amountRequested:number;purpose:string;status:string;decisionReason?:string|null;submittedAt:string;applicant:{businessName:string;pan:string;gstin:string;address:string}};
type Check={checkType:string;passed:boolean;reason:string};
type Risk={probabilityOfDefault:number;riskBand:string;modelVersion:string;shapBreakdown:string};
type Detail={application:Application;complianceChecks:Check[];riskAssessment:Risk|null;message?:string};
type Options={googleClientId:string;passwordLogin:boolean};
const session={get token(){return localStorage.getItem('token')||''},get role(){return(localStorage.getItem('role')||'') as Role|''},get email(){return localStorage.getItem('email')||''},set(t:string,r:string,e:string){localStorage.setItem('token',t);localStorage.setItem('role',r);localStorage.setItem('email',e)},clear(){localStorage.clear()}};
class ApiError extends Error{constructor(public status:number,m:string){super(m)}}
async function api<T>(path:string,init:{method?:string;body?:unknown}={}):Promise<T>{
  const r=await fetch('/api'+path,{method:init.method||'GET',headers:{'Content-Type':'application/json',...(session.token?{Authorization:`Bearer ${session.token}`}:{})},body:init.body===undefined?undefined:JSON.stringify(init.body)});
  if(r.status===401&&session.token){session.clear();location.reload()}
  if(!r.ok){let m='Request failed';try{m=(await r.json()).message||m}catch{}throw new ApiError(r.status,m)}
  return r.json() as Promise<T>}
const inr=(n:number|string)=>'₹ '+Number(n).toLocaleString('en-IN');
const label=(s:string)=>s.replaceAll('_',' ');
const parseLedger=(r:Risk|null):Ledger[]=>{try{return r?JSON.parse(r.shapBreakdown):[]}catch{return[]}};
const bandClass=(b:string)=>b==='HIGH'?'high':b==='MEDIUM'?'medium':'low';
const statusClass=(s:string)=>s.includes('FAILED')||s==='REJECTED'?'high':s.includes('REVIEW')?'medium':'low';

// ---------- shared pieces ----------
function Notice(){return <div className="notice"><b>Research demo.</b> CreditSense uses a simulated model trained on synthetic data and makes no real lending decisions. <b>Please do not enter real PAN, GSTIN or other personal details</b> — use made-up values.</div>}
function Privacy({close}:{close:()=>void}){return <div className="modal" onClick={close}><div className="modal-box" onClick={e=>e.stopPropagation()}><h2>Privacy notice</h2>
  <p>CreditSense is a <b>research and teaching demonstration</b>. The risk score comes from a simulated model trained on synthetic data; it is not a credit decision and no lender will ever see it.</p>
  <ul><li><b>What we store:</b> your Google email address and any application details you submit (business name, PAN, GSTIN, address, financial figures), plus an audit log of actions.</li>
  <li><b>Why:</b> only to run the demo — compliance checks, the risk score and its explanation.</li>
  <li><b>Please use made-up values.</b> Do not enter real PAN, GSTIN or other identifiers.</li>
  <li><b>Who can see it:</b> you, the demo's loan officers and its administrator.</li>
  <li><b>Your rights (DPDP Act, 2023):</b> you can withdraw consent and erase your account and all your applications at any time with the <i>Delete my data</i> button. Audit entries are kept but no longer linked to you.</li></ul>
  <button className="primary" onClick={close}>Close</button></div></div>}
function LedgerView({rows}:{rows:Ledger[]}){if(!rows.length)return null;const max=Math.max(...rows.map(x=>Math.abs(x.shap_contribution)))||1;return <section className="ledger"><div className="section-title">WHY THIS SCORE <span>SHAP CONTRIBUTION · RED RAISES RISK, GREEN LOWERS IT</span></div>{rows.slice(0,8).map(x=>{const w=Math.abs(x.shap_contribution)/max*48;return <div className="entry" key={x.feature}><div><b>{label(x.feature)}</b><small>{String(x.feature_value)}</small></div><div className="barwrap"><i className={x.shap_contribution>0?'plus':'minus'} style={{width:`${w}%`,left:x.shap_contribution>0?'50%':`${50-w}%`}}/></div><code>{x.shap_contribution>0?'+':''}{x.shap_contribution.toFixed(3)}</code></div>})}</section>}
function Checks({checks}:{checks:Check[]}){return <section className="ledger"><div className="section-title">COMPLIANCE CHECKS</div>{checks.map(c=><div className="check" key={c.checkType}><span className={'badge '+(c.passed?'low':'high')}>{c.passed?'PASS':'FAIL'}</span><div><b>{label(c.checkType)}</b><small>{c.reason}</small></div></div>)}</section>}
function Result({d}:{d:Detail}){const risk=d.riskAssessment,ledger=parseLedger(risk),a=d.application;
  return <div className="case"><section className="score"><p className="eyebrow">{risk?`RISK ASSESSMENT / ${risk.modelVersion}`:'RISK ASSESSMENT'}</p>{risk?<><div className="big">{(Number(risk.probabilityOfDefault)*100).toFixed(1)}<small>%</small></div><b className={'badge '+bandClass(risk.riskBand)}>{risk.riskBand} RISK</b><p>Estimated probability of default (simulated model)</p></>:<><div className="big">—</div><b className={'badge '+statusClass(a.status)}>{label(a.status)}</b><p>No score available</p></>}<hr/><dl><dt>Business</dt><dd>{a.applicant.businessName}</dd><dt>Requested</dt><dd>{inr(a.amountRequested)}</dd><dt>Status</dt><dd>{label(a.status)}</dd>{a.decisionReason&&<><dt>Reason</dt><dd>{a.decisionReason}</dd></>}</dl>{d.message&&<p className="msg">{d.message}</p>}</section><div className="stack"><Checks checks={d.complianceChecks}/><LedgerView rows={ledger}/></div></div>}

// ---------- sign in ----------
declare global{interface Window{google?:any}}
function GoogleButton({clientId,onCredential}:{clientId:string;onCredential:(c:string)=>void}){const box=useRef<HTMLDivElement>(null);useEffect(()=>{let dead=false;const init=()=>{if(dead||!window.google||!box.current)return;window.google.accounts.id.initialize({client_id:clientId,callback:(r:{credential:string})=>onCredential(r.credential)});window.google.accounts.id.renderButton(box.current,{theme:'outline',size:'large',text:'signin_with',width:280})};if(window.google)init();else{const s=document.createElement('script');s.src='https://accounts.google.com/gsi/client';s.async=true;s.onload=init;document.head.appendChild(s)}return()=>{dead=true}},[clientId]);return <div ref={box} className="gbtn"/>}
function Login({options,onLogin,showPrivacy}:{options:Options;onLogin:()=>void;showPrivacy:()=>void}){
  const [email,setEmail]=useState(''),[pass,setPass]=useState(''),[error,setError]=useState(''),[busy,setBusy]=useState(false);
  async function finish(p:Promise<{accessToken:string;role:string;email:string}>){setBusy(true);setError('');try{const d=await p;session.set(d.accessToken,d.role,d.email);onLogin()}catch(e){setError(e instanceof ApiError&&e.status===403?'Password sign-in is turned off.':'Could not sign in. Please try again.')}finally{setBusy(false)}}
  return <main className="login"><div><p className="eyebrow">CREDITSENSE / RISK DESK</p><h1>Decisions you can defend.</h1><p>Every lending decision is compliance-gated, model-scored, and explained line by line.</p><Notice/></div>
  <div className="form"><h2>Sign in</h2>
    {options.googleClientId?<GoogleButton clientId={options.googleClientId} onCredential={c=>finish(api('/auth/google',{method:'POST',body:{credential:c}}))}/>:<p className="error">Google sign-in is not configured.</p>}
    {busy&&<p>Signing in…</p>}
    {options.passwordLogin&&<form onSubmit={e=>{e.preventDefault();finish(api('/auth/login',{method:'POST',body:{email,password:pass}}))}}><hr/><small>Local demo accounts (demo mode only)</small><label>EMAIL<input value={email} onChange={e=>setEmail(e.target.value)} type="email"/></label><label>PASSWORD<input value={pass} onChange={e=>setPass(e.target.value)} type="password"/></label><button className="primary">Sign in</button></form>}
    {error&&<p className="error">{error}</p>}
    <small>By signing in you agree to the <a href="#privacy" onClick={e=>{e.preventDefault();showPrivacy()}}>privacy notice</a>.</small></div></main>}
function Waking(){return <main className="login"><div><p className="eyebrow">CREDITSENSE</p><h1>Server waking up…</h1><p>The free demo server sleeps when nobody is using it. This can take a few minutes the first time — the page will continue by itself.</p></div></main>}

// ---------- applicant: new application ----------
const SECTORS=['manufacturing','retail','services','hospitality','construction','agriculture'];
const DOCS=[['PAN','PAN card'],['UDYAM','Udyam registration'],['ADDRESS_PROOF','Address proof'],['BANK_STATEMENTS','Bank statements']];
const NUM:[string,string,string][]=[['business_vintage_years','Years in business','6'],['monthly_revenue','Monthly revenue (₹)','1800000'],['revenue_volatility','Revenue volatility (0–5)','0.2'],['gst_filing_consistency','GST filing consistency (0–1)','0.8'],['debt_to_revenue_ratio','Debt to revenue ratio','0.35'],['inflow_outflow_ratio','Bank inflow / outflow ratio','1.1'],['average_bank_balance','Average bank balance (₹)','230000'],['trade_references','Trade references (count)','8'],['delinquency_buckets','Past delinquency buckets','0'],['loan_to_revenue_ratio','Loan to revenue ratio','0.5'],['kyc_completeness_score','KYC completeness (0–1)','1'],['digital_transaction_frequency','Digital transactions / month','210']];
function NewApplication({done}:{done:(d:Detail)=>void}){
  const [f,setF]=useState<Record<string,string>>({businessName:'',pan:'',gstin:'',address:'',amountRequested:'500000',purpose:'Working capital',sector:'manufacturing',...Object.fromEntries(NUM.map(([k,,v])=>[k,v]))});
  const [docs,setDocs]=useState<string[]>(DOCS.map(d=>d[0])),[consent,setConsent]=useState(false),[error,setError]=useState(''),[busy,setBusy]=useState(false);
  const set=(k:string)=>(e:React.ChangeEvent<HTMLInputElement|HTMLSelectElement>)=>setF({...f,[k]:e.target.value});
  async function submit(e:React.FormEvent){e.preventDefault();setBusy(true);setError('');try{
    const features:Record<string,unknown>={sector:f.sector};NUM.forEach(([k])=>features[k]=Number(f[k]));
    const d=await api<Detail>('/applications',{method:'POST',body:{businessName:f.businessName,pan:f.pan.toUpperCase(),gstin:f.gstin.toUpperCase(),address:f.address,amountRequested:Number(f.amountRequested),purpose:f.purpose,kycDocuments:docs,features,consent}});done(d)}catch(e){setError(e instanceof Error?e.message:'Submission failed')}finally{setBusy(false)}}
  return <form className="apply" onSubmit={submit}><section className="panel"><div className="section-title">BUSINESS</div><div className="grid">
    <label>BUSINESS NAME<input required value={f.businessName} onChange={set('businessName')}/></label>
    <label>PAN (made-up, e.g. ABCDE1234F)<input required pattern="[A-Za-z]{5}[0-9]{4}[A-Za-z]" value={f.pan} onChange={set('pan')}/></label>
    <label>GSTIN (made-up, 15 characters)<input required minLength={15} maxLength={15} value={f.gstin} onChange={set('gstin')}/></label>
    <label>ADDRESS<input required minLength={9} value={f.address} onChange={set('address')}/></label>
    <label>AMOUNT REQUESTED (₹)<input required type="number" min="1" value={f.amountRequested} onChange={set('amountRequested')}/></label>
    <label>PURPOSE<input required value={f.purpose} onChange={set('purpose')}/></label>
    <label>SECTOR<select value={f.sector} onChange={set('sector')}>{SECTORS.map(s=><option key={s}>{s}</option>)}</select></label></div></section>
  <section className="panel"><div className="section-title">DOCUMENTS SUPPLIED</div><div className="checks">{DOCS.map(([k,n])=><label className="inline" key={k}><input type="checkbox" checked={docs.includes(k)} onChange={e=>setDocs(e.target.checked?[...docs,k]:docs.filter(x=>x!==k))}/>{n}</label>)}</div></section>
  <section className="panel"><div className="section-title">FINANCIAL PROFILE <span>SIMULATED INPUTS</span></div><div className="grid">{NUM.map(([k,n])=><label key={k}>{n.toUpperCase()}<input required type="number" step="any" value={f[k]} onChange={set(k)}/></label>)}</div></section>
  <label className="inline consent"><input type="checkbox" checked={consent} onChange={e=>setConsent(e.target.checked)}/><span>I understand this is a research demo with a simulated model, I have <b>not</b> entered real personal or business identifiers, and I consent to CreditSense storing what I submit. I can delete it at any time.</span></label>
  {error&&<p className="error">{error}</p>}<button className="primary" disabled={busy||!consent}>{busy?'Checking and scoring…':'Submit and see my result'}</button></form>}

// ---------- case review (officer / admin) and detail ----------
function CaseReview({id,role,back}:{id:string;role:Role;back:()=>void}){
  const [d,setD]=useState<Detail|null>(null),[error,setError]=useState(''),[reason,setReason]=useState(''),[busy,setBusy]=useState(false);
  const load=()=>api<Detail>('/applications/'+id).then(setD).catch(e=>setError(e.message));useEffect(()=>{load()},[id]);
  const decide=async(status:'APPROVED'|'REJECTED')=>{setBusy(true);setError('');try{await api('/applications/'+id+'/decision',{method:'POST',body:{status,reason}});setReason('');await load()}catch(e){setError(e instanceof Error?e.message:'Failed')}finally{setBusy(false)}};
  const rescore=async()=>{setBusy(true);setError('');try{await api('/applications/'+id+'/compliance-check',{method:'POST'});await api('/applications/'+id+'/risk-assessment',{method:'POST'});await load()}catch(e){setError(e instanceof Error?e.message:'Failed');await load()}finally{setBusy(false)}};
  if(!d)return <p>{error||'Loading…'}</p>;const canDecide=role!=='APPLICANT'&&['RISK_SCORED','MANUAL_REVIEW'].includes(d.application.status);
  return <div><button className="link" onClick={back}>← Back</button><Result d={d}/>{role!=='APPLICANT'&&<section className="panel decide">{canDecide?<><label>DECISION REASON (required)<input value={reason} onChange={e=>setReason(e.target.value)}/></label><div className="row"><button className="approve" disabled={busy||!reason} onClick={()=>decide('APPROVED')}>Approve</button><button className="reject" disabled={busy||!reason} onClick={()=>decide('REJECTED')}>Reject</button></div></>:<p>No decision needed in status {label(d.application.status)}.</p>}{['COMPLIANCE_REVIEW','MANUAL_REVIEW'].includes(d.application.status)&&<button className="link" disabled={busy} onClick={rescore}>Re-run compliance and scoring</button>}{error&&<p className="error">{error}</p>}</section>}</div>}

// ---------- lists & admin ----------
function Queue({role,open}:{role:Role;open:(id:string)=>void}){const [rows,setRows]=useState<Application[]>([]),[error,setError]=useState(''),[q,setQ]=useState('');useEffect(()=>{api<Application[]>(role==='APPLICANT'?'/applications/mine':'/applications').then(setRows).catch(e=>setError(e.message))},[role]);
  const shown=rows.filter(r=>(r.applicant?.businessName+r.id).toLowerCase().includes(q.toLowerCase())).sort((a,b)=>b.submittedAt.localeCompare(a.submittedAt));
  return <>{role!=='APPLICANT'&&<div className="kpis">{[['TOTAL',rows.length],['AWAITING DECISION',rows.filter(r=>['RISK_SCORED','MANUAL_REVIEW'].includes(r.status)).length],['APPROVED',rows.filter(r=>r.status==='APPROVED').length],['COMPLIANCE FAILED',rows.filter(r=>r.status==='COMPLIANCE_FAILED').length]].map(([l,v])=><div key={l}><span>{l}</span><strong>{v}</strong></div>)}</div>}
  <section className="tablepanel"><div className="toolbar"><b>{error||`${shown.length} applications`}</b><input placeholder="Search business or ID" value={q} onChange={e=>setQ(e.target.value)}/></div><table><thead><tr><th>CASE</th><th>BUSINESS</th><th>AMOUNT</th><th>STATUS</th><th/></tr></thead><tbody>{shown.map(r=><tr key={r.id}><td><code>{r.id.slice(0,8).toUpperCase()}</code></td><td>{r.applicant?.businessName}</td><td><code>{inr(r.amountRequested)}</code></td><td><span className={'badge '+statusClass(r.status)}>{label(r.status)}</span></td><td><button onClick={()=>open(r.id)}>Open →</button></td></tr>)}</tbody></table>{!shown.length&&!error&&<p className="empty">Nothing here yet.</p>}</section></>}
type Analytics={applications:number;statusDistribution:Record<string,number>;riskDistribution:Record<string,number>};
function Portfolio(){const [a,setA]=useState<Analytics|null>(null),[m,setM]=useState<any>(null),[error,setError]=useState(''),[busy,setBusy]=useState(false);
  const load=()=>{api<Analytics>('/portfolio/analytics').then(setA).catch(e=>setError(e.message));api('/models/info').then(setM).catch(()=>setM(null))};useEffect(load,[]);
  const retrain=async()=>{setBusy(true);try{await api('/models/retrain',{method:'POST'});load()}catch(e){setError(e instanceof Error?e.message:'Retrain failed')}finally{setBusy(false)}};
  if(!a)return <p>{error||'Loading…'}</p>;const toData=(o:Record<string,number>)=>Object.entries(o).map(([n,v])=>({n:label(n),v}));
  return <div className="charts"><section><div className="section-title">RISK DISTRIBUTION</div><ResponsiveContainer width="100%" height={250}><BarChart data={toData(a.riskDistribution)}><XAxis dataKey="n"/><YAxis allowDecimals={false}/><Tooltip/><Bar dataKey="v" fill="#d9a441"/></BarChart></ResponsiveContainer></section><section><div className="section-title">STATUS DISTRIBUTION</div><ResponsiveContainer width="100%" height={250}><BarChart data={toData(a.statusDistribution)}><XAxis dataKey="n" tick={{fontSize:10}}/><YAxis allowDecimals={false}/><Tooltip/><Bar dataKey="v" fill="#3f7d58"/></BarChart></ResponsiveContainer></section>
  <section><div className="section-title">MODEL</div>{m?<dl className="modelinfo"><dt>Version</dt><dd>{m.model_version}</dd><dt>ROC-AUC</dt><dd>{m.metrics?.roc_auc??m.metrics?.auc??'—'}</dd><dt>Trained</dt><dd>{String(m.training_date).slice(0,10)}</dd></dl>:<p>Model service unavailable.</p>}<button className="primary" disabled={busy} onClick={retrain}>{busy?'Retraining…':'Retrain model'}</button>{error&&<p className="error">{error}</p>}</section></div>}
type Audit={id:string;actor?:{email:string}|null;action:string;entityType:string;entityId:string;createdAt:string};
function AuditTrail(){const [rows,setRows]=useState<Audit[]>([]),[error,setError]=useState('');useEffect(()=>{api<Audit[]>('/audit-logs').then(setRows).catch(e=>setError(e.message))},[]);return <section className="tablepanel"><div className="toolbar"><b>{error||'Decision audit trail'}</b></div><table><thead><tr><th>TIME</th><th>ACTOR</th><th>ACTION</th><th>ENTITY</th></tr></thead><tbody>{rows.slice(0,200).map(r=><tr key={r.id}><td><code>{new Date(r.createdAt).toLocaleString()}</code></td><td><code>{r.actor?.email??'(removed)'}</code></td><td><code>{r.action}</code></td><td><code>{r.entityType} {r.entityId.slice(0,8)}</code></td></tr>)}</tbody></table></section>}

// ---------- shell ----------
type View='list'|'new'|'case'|'portfolio'|'audit'|'result';
function Desk({role,showPrivacy}:{role:Role;showPrivacy:()=>void}){
  const [view,setView]=useState<View>(role==='APPLICANT'?'new':'list'),[caseId,setCaseId]=useState(''),[result,setResult]=useState<Detail|null>(null),[del,setDel]=useState('');
  const applicant=role==='APPLICANT';const nav:[View,string][]=applicant?[['new','New application'],['list','My applications']]:role==='ADMIN'?[['list','Application queue'],['portfolio','Portfolio & model'],['audit','Audit trail']]:[['list','Application queue']];
  const open=(id:string)=>{setCaseId(id);setView('case')};
  async function deleteData(){if(!confirm('Delete your account and all your applications? This cannot be undone.'))return;try{await api('/me',{method:'DELETE'});session.clear();location.reload()}catch(e){setDel(e instanceof Error?e.message:'Could not delete')}}
  const title={list:applicant?'My credit applications':'Underwriting queue',new:'New credit application',case:'Application review',portfolio:'Portfolio & model',audit:'Decision audit trail',result:'Your result'}[view];
  return <div className="shell"><aside><div className="brand">C<span>S</span></div><nav>{nav.map(([id,n])=><button className={view===id?'active':''} onClick={()=>setView(id)} key={id}>{n}</button>)}</nav><small className="who">{session.email}<br/>{label(role)}</small><button className="signout" onClick={showPrivacy}>Privacy notice</button>{applicant&&<button className="signout" onClick={deleteData}>Delete my data</button>}<button className="signout" onClick={()=>{session.clear();location.reload()}}>Sign out</button></aside>
  <main className="desk"><Notice/><header><div><p className="eyebrow">{label(role)}</p><h1>{title}</h1></div></header>{del&&<p className="error">{del}</p>}
  {view==='new'?<NewApplication done={d=>{setResult(d);setView('result')}}/>:view==='result'&&result?<><Result d={result}/><button className="link" onClick={()=>setView('list')}>View all my applications →</button></>:view==='case'?<CaseReview id={caseId} role={role} back={()=>setView('list')}/>:view==='portfolio'?<Portfolio/>:view==='audit'?<AuditTrail/>:<Queue role={role} open={open}/>}</main></div>}

function App(){
  const [options,setOptions]=useState<Options|null>(null),[authed,setAuthed]=useState(!!session.token&&!!session.role),[privacy,setPrivacy]=useState(false);
  useEffect(()=>{let dead=false;(async()=>{while(!dead){try{const r=await fetch('/api/auth/options');if(r.ok){setOptions(await r.json());return}}catch{}await new Promise(r=>setTimeout(r,5000))}})();return()=>{dead=true}},[]);
  return <>{!options?<Waking/>:authed&&session.role?<Desk role={session.role as Role} showPrivacy={()=>setPrivacy(true)}/>:<Login options={options} onLogin={()=>setAuthed(true)} showPrivacy={()=>setPrivacy(true)}/>}{privacy&&<Privacy close={()=>setPrivacy(false)}/>}</>}
createRoot(document.getElementById('root')!).render(<App/>);
