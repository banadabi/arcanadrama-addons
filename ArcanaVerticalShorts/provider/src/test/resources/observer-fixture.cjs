const fs = require('fs'), vm = require('vm'), assert = require('assert');
const script = fs.readFileSync(process.argv[2], 'utf8'), mode = process.argv[3];
const target = 'https://verticalshorts.ai/api/site/play?platform=goodshort&id=31001799154&ep=1&lang=tr';
const delay = ms => new Promise(r => setTimeout(r, ms));
function setup(fetch) {
  const listeners = {};
  const ctx = {URL, TextDecoder, Uint8Array, Reflect, JSON, document:{addEventListener:(n,fn)=>listeners[n]=fn}, location:{origin:'https://verticalshorts.ai',href:'https://verticalshorts.ai/tr/watch/dramaseries/31001799154/1'}, __dsGuard:{open:false,bait:false,lib:false}, fetch};
  ctx.window = ctx; vm.createContext(ctx); vm.runInContext(script, ctx);
  return {ctx,listeners};
}
function response(status, url=target, body='{"episode":1}', wait=0) {
  const value = {status,url,headers:{get:()=>null},clone(){let done=false;return {body:{getReader(){return {async read(){if(done)return {done:true};done=true;if(wait)await delay(wait);return {done:false,value:new TextEncoder().encode(body)};},async cancel(){done=true;}};}}};}};
  return value;
}
(async()=>{
 if(mode==='passthrough') {
  const r=response(200), original=Promise.resolve(r), receiver={marker:1};let got,args;
  const {ctx,listeners}=setup(function(){got=this;args=[...arguments];return original;});
  const options={credentials:'same-origin'}, actual=ctx.fetch.call(receiver,target,options);
  assert.strictEqual(actual,original);assert.strictEqual(await actual,r);assert.strictEqual(got,receiver);assert.strictEqual(args[0],target);assert.strictEqual(args[1],options);
  await delay(10);assert.strictEqual(ctx.ownstate.result.status,200);
  listeners.load({target:{tagName:'LINK',href:'https://verticalshorts.ai/_next/static/chunks/0-17d7h2qc-3z.js'}});
  assert.strictEqual(ctx.ownstate.securityScriptDelivered,false);
  listeners.load({target:{tagName:'SCRIPT',src:'https://verticalshorts.ai/_next/static/chunks/0-17d7h2qc-3z.js'}});
  assert.strictEqual(ctx.ownstate.securityScriptDelivered,true);
 } else if(mode==='identity') {
  let r=response(200,target.replace('lang=tr','lang=en'));
  let {ctx}=setup(()=>Promise.resolve(r));await ctx.fetch(target);await delay(10);assert.strictEqual(ctx.ownstate.error,'response_route');
  ({ctx}=setup(()=>Promise.resolve(response(200))));await ctx.fetch(target+'&id=other');await delay(10);assert.strictEqual(ctx.ownstate.result,null);
 } else if(mode==='denial') {
  let n=0;const {ctx}=setup(()=>Promise.resolve(++n===1?response(200,target,'{"episode":1}',40):response(403)));
  ctx.fetch(target);ctx.fetch(target);await delay(80);assert.strictEqual(ctx.ownstate.terminal,true);assert.strictEqual(ctx.ownstate.result.status,403);assert.strictEqual(ctx.ownstate.error,'denied');
 } else if(mode==='body-denial') {
  let n=0;const {ctx}=setup(()=>Promise.resolve(++n===1?response(200,target,'{"error":"forbidden"}',20):response(200,target,'{"episode":1}',50)));
  ctx.fetch(target);ctx.fetch(target);await delay(90);assert.strictEqual(ctx.ownstate.terminal,true);assert.strictEqual(ctx.ownstate.error,'body_denied');assert.strictEqual(JSON.parse(ctx.ownstate.result.body).error,'forbidden');
 } else throw Error('Unknown case');
 console.log('PASS '+mode);
})().catch(e=>{console.error(e.stack);process.exit(1);});
