'use strict';
// Read-only Pages audit. Never fetches tokens, private repo content or user-specific paths.
// node scripts/pages-audit.cjs [https://owner.github.io/Repo/]
const fs=require('node:fs');
const path=require('node:path');
const crypto=require('node:crypto');
const sha256=bytes=>crypto.createHash('sha256').update(bytes).digest('hex');
const PUBLISHED_PATHS=['AGENTS.md','README.md','docs/WORK-QUEUE.md','tests/browser-smoke.cjs','.github/workflows/validate.yml','.expository-managed'];
function pagesBase(candidate){
 const u=new URL(candidate);
 if(u.protocol!=='https:'||u.username||u.password||u.search||u.hash||!u.pathname.endsWith('/'))throw Error('Pages URL must be HTTPS, credential-free and end with /');
 return u
}
async function auditPages({baseUrl='https://gamesbyian.github.io/Expository/',localHtml,fetchImpl=fetch,paths=PUBLISHED_PATHS}={}){
 if(typeof localHtml!=='string'&&!(localHtml instanceof Uint8Array))throw Error('Canonical index.html bytes required');
 const base=pagesBase(baseUrl),expected=Buffer.from(localHtml),expectedSha=sha256(expected);
 const indexUrl=new URL(base.href);indexUrl.searchParams.set('expository_parity',expectedSha.slice(0,12));
 const response=await fetchImpl(indexUrl.href,{method:'GET',cache:'no-store',redirect:'error',headers:{Accept:'text/html'}});
 if(!response||!response.ok)throw Error('Public Pages index unreachable (HTTP '+(response?.status||'unknown')+').');
 const actual=Buffer.from(await response.arrayBuffer()),actualSha=sha256(actual),same=expectedSha===actualSha;
 const probes=[];
 for(const pathname of paths){
  if(typeof pathname!=='string'||pathname.startsWith('/')||pathname.split('/').some(x=>x==='..'))throw Error('Only known relative public paths may be checked');
  const endpoint=new URL(pathname,base);
  const head=await fetchImpl(endpoint.href,{method:'HEAD',cache:'no-store',redirect:'error'});
  const http=head?.status||0;
  probes.push({path:pathname,http,publiclyServed:http>=200&&http<300});
 }
 return {url:base.href,parity:same,sourceSha256:expectedSha,deployedSha256:actualSha,bytes:{source:expected.length,deployed:actual.length},publicPaths:probes};
}
function summary(report){
 const items=report.publicPaths.map(x=>'- '+x.path+': HTTP '+x.http+(x.publiclyServed?' (served publicly)':'')).join('\n');
 return '## Expository Pages source parity\n\n'+
  (report.parity?'**PASS:** deployed index.html matches canonical main checkout byte-for-byte.':'**FAIL:** deployed index.html differs from checked-out source.')+
  '\n\nPages URL: '+report.url+'\n\nCanonical SHA-256: `'+report.sourceSha256+'`\nLive SHA-256: `'+report.deployedSha256+'`\n\n'+
  'These paths are already in a public GitHub repository. HTTP status here reports which are also served by Pages; it does not imply private-data exposure.\n\n'+items+'\n';
}
async function main(){
 const p=path.join(__dirname,'..','index.html');
 const result=await auditPages({baseUrl:process.argv[2]||process.env.EXPOSITORY_BASE_URL||'https://gamesbyian.github.io/Expository/',localHtml:fs.readFileSync(p)});
 const body=summary(result);
 console.log(body);
 if(process.env.GITHUB_STEP_SUMMARY)fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY,body+'\n');
 if(!result.parity)process.exitCode=1
}
if(require.main===module)main().catch(e=>{console.error('Pages audit could not complete: '+e.message);process.exitCode=1});
module.exports={pagesBase,auditPages,summary,PUBLISHED_PATHS};
