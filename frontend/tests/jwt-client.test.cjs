const { test } = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
function setup() {
  const calls = [];
  const token = 'x.' + Buffer.from(JSON.stringify({academyId: 7})).toString('base64url') + '.x';
  const context = vm.createContext({
    atob, Date, Number, JSON, Error, Promise,
    location: {href:'', pathname:'/screens/OPS-02-dashboard.html'},
    localStorage: {getItem:()=> 'legacy-key-must-not-be-sent', removeItem:()=>{}},
    alert:()=>{},
    fetch: async (url, options) => {
      calls.push({url, options});
      if (url.endsWith('/refresh')) {
        await Promise.resolve();
        return {ok:true, json:async()=>({accessToken:token, expiresIn:900})};
      }
      return {ok:true, status:200, json:async()=>[]};
    }
  });
  context.window = context;
  for (const file of ['auth.js', 'api-client.js']) vm.runInContext(fs.readFileSync(path.join(__dirname, '../components', file),'utf8'), context);
  return {context,calls};
}
test('parallel page loads rotate refresh once and route both requests to the authenticated academy', async()=>{
  const {context,calls} = setup();
  await vm.runInContext("Promise.all([apiGet('/@academy/students'),apiGet('/@academy/classes')])",context);
  assert.equal(calls.filter(x=>x.url.endsWith('/refresh')).length,1);
  assert.deepEqual(calls.filter(x=>!x.url.endsWith('/refresh')).map(x=>x.url),['/api/rea/7/students','/api/rea/7/classes']);
  for(const c of calls.filter(x=>!x.url.endsWith('/refresh'))) {
    assert.match(c.options.headers.Authorization,/^Bearer /);
    assert.equal(c.options.headers['X-Auth-Key'], undefined);
    assert.equal(c.options.credentials,'same-origin');
  }
});
test('student edits use JWT and preserve the request body',async()=>{
  const {context,calls}=setup();
  await vm.runInContext("apiPut('/@academy/students/23',{name:'QA'},'legacy-key')",context);
  const c=calls.at(-1);
  assert.equal(c.url,'/api/rea/7/students/23');
  assert.equal(c.options.method,'PUT');
  assert.equal(c.options.body,'{"name":"QA"}');
  assert.equal(c.options.headers['X-Auth-Key'],undefined);
});
test('expired session prevents business requests and returns to formal login',async()=>{
 const {context,calls}=setup();
 context.fetch=async(url)=>{calls.push({url});return {ok:false}};
 await assert.rejects(vm.runInContext("apiGet('/@academy/students')",context));
 assert.equal(calls.length,1);
 assert.equal(context.location.href,'AUTH-01-login.html');
});
