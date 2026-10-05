#!/usr/bin/env python3
"""Black-box OpenAI smoke test: real service + gateway, fake loopback upstream only.

Build service/gradlew -p service fatJar first, then run this script. Requires Java,
Go and Python 3; no Python packages or production credentials. Everything is kept
in a temporary directory and all child processes are stopped on completion.
"""
from __future__ import annotations
import argparse
import base64
import http.cookiejar
import http.server
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import sqlite3
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[2]


def available_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def request(base, path, method='GET', body=None, token=None, client=None, headers=None):
    headers = dict(headers or {})
    if token:
        headers['Authorization'] = 'Bearer ' + token
    if body is not None:
        headers['Content-Type'] = 'application/json'
    req = urllib.request.Request(base + path, method=method, headers=headers,
                                 data=json.dumps(body).encode() if body is not None else None)
    try:
        res = (client or urllib.request).open(req, timeout=15) if client else urllib.request.urlopen(req, timeout=15)
    except urllib.error.HTTPError as exc:
        res = exc
    with res:
        raw = res.read()
        data = json.loads(raw) if 'application/json' in res.headers.get('Content-Type', '') else raw.decode()
        return res.status, data, res.headers


def expect(base, path, method='GET', body=None, token=None, client=None, status=200, headers=None):
    code, data, response_headers = request(base, path, method, body, token, client, headers)
    assert code == status, f'{method} {path}: expected {status}, got {code}'
    return data, response_headers


def wait_ready(base, proc):
    for _ in range(150):
        if proc.poll() is not None:
            raise RuntimeError('Test process exited before health check; use --keep for private logs')
        try:
            if request(base, '/healthz')[0] == 200:
                return
        except (OSError, urllib.error.URLError):
            pass
        time.sleep(0.2)
    raise TimeoutError('Test process did not become ready')


class FakeUpstream(http.server.BaseHTTPRequestHandler):
    events = []
    fail_api = False
    sparse_terminal = False
    oauth_polls = 0
    api_secret = ''
    oauth_secret = ''
    anthropic_secret = ''
    protocol_version = 'HTTP/1.1'

    def log_message(self, *_):
        pass

    def send(self, body, code=200, content_type='application/json', limits=False):
        raw = json.dumps(body).encode() if not isinstance(body, bytes) else body
        self.send_response(code)
        self.send_header('Content-Type', content_type)
        self.send_header('Content-Length', str(len(raw)))
        if limits:
            for k, v in {'x-codex-primary-used-percent':'25', 'x-codex-primary-window-minutes':'300',
                         'x-codex-primary-reset-at':str(int(time.time())+18000),
                         'x-codex-secondary-used-percent':'40', 'x-codex-secondary-window-minutes':'10080',
                         'x-codex-secondary-reset-at':str(int(time.time())+604800),
                         'x-codex-review-primary-used-percent':'99',
                         'x-codex-review-primary-window-minutes':'300',
                         'x-codex-review-primary-reset-at':str(int(time.time())+18000)}.items():
                self.send_header(k, v)
        self.end_headers()
        self.wfile.write(raw)

    def capture(self, body=None):
        self.events.append({'path':self.path, 'headers':dict(self.headers), 'body':body})

    def do_GET(self):
        self.capture()
        if self.path.startswith('/backend-api/wham/usage'):
            self.send({'rate_limit':{'allowed':True,'limit_reached':False,
                'primary_window':{'limit_window_seconds':18000,'used_percent':20,'reset_at':int(time.time())+18000},
                'secondary_window':{'limit_window_seconds':604800,'used_percent':30,'reset_at':int(time.time())+604800}}})
        elif self.path.startswith('/codex/models'):
            self.send({'models':[{'slug':'gpt-smoke','display_name':'Smoke model','visibility':'list','enabled':True}]})
        elif self.path == '/v1/models':
            self.send({'object':'list','data':[{'id':'gpt-smoke','object':'model','owned_by':'openai'}]})
        else:
            self.send({'error':{'message':'Unknown fake upstream path'}},404)

    def do_POST(self):
        raw = self.rfile.read(int(self.headers.get('Content-Length','0')))
        body = json.loads(raw) if self.headers.get('Content-Type','').startswith('application/json') else urllib.parse.parse_qs(raw.decode())
        self.capture(body)
        if self.path == '/api/accounts/deviceauth/usercode':
            self.send({'device_auth_id':'fake-device','user_code':'SMOKE-CODE','interval':5})
            return
        if self.path == '/api/accounts/deviceauth/token':
            type(self).oauth_polls += 1
            if self.oauth_polls == 1:
                self.send({'error':'authorization_pending'},403)
            else:
                self.send({'authorization_code':'fake-code','code_verifier':'fake-verifier'})
            return
        if self.path == '/oauth/token':
            claims = base64.urlsafe_b64encode(json.dumps({'https://api.openai.com/auth':{'chatgpt_account_id':'workspace_device'}}).encode()).decode().rstrip('=')
            self.send({'access_token':'header.'+claims+'.signature','refresh_token':secrets.token_urlsafe(24),'expires_in':3600})
            return
        if self.path == '/v1/messages':
            self.send({'id':'msg_smoke','type':'message','role':'assistant','model':body.get('model','claude-smoke'),
                       'content':[{'type':'text','text':'Claude OK'}],'stop_reason':'end_turn',
                       'usage':{'input_tokens':3,'output_tokens':2}})
            return
        if self.path not in ('/v1/responses','/codex/responses'):
            self.send({'error':{'message':'Unknown fake upstream path'}},404)
            return
        if self.path == '/v1/responses' and self.fail_api:
            self.send({'error':{'code':'rate_limit_exceeded'}},429,limits=True)
            return
        response={'id':'resp_smoke','object':'response','model':body['model'],'status':'completed',
                  'output':[{'type':'message','role':'assistant','content':[{'type':'output_text','text':'OpenAI OK'}]},
                            {'type':'function_call','name':'echo','call_id':'call_smoke','arguments':'{"text":"ok"}'}],
                  'usage':{'input_tokens':100,'input_tokens_details':{'cached_tokens':60},'output_tokens':20}}
        if body.get('stream'):
            events=[{'type':'response.created','response':{'id':'resp_smoke','status':'in_progress'}},
                    {'type':'response.output_text.delta','delta':'OpenAI OK'},
                    {'type':'response.function_call_arguments.done','name':'echo','call_id':'call_smoke','arguments':'{"text":"ok"}'},
                    {'type':'response.completed','response':response}]
            if self.sparse_terminal:
                output=[{'id':'rs_smoke','type':'reasoning','summary':[],
                         'encrypted_content':'opaque-workspace-smoke'}]+response['output']
                completed=dict(response,output=[])
                events=events[:-1]+[{'type':'response.output_item.done','output_index':i,'item':item}
                                    for i,item in enumerate(output)]
                events.append({'type':'response.completed','response':completed})
            sse=''.join('event: '+e['type']+'\ndata: '+json.dumps(e)+'\n\n' for e in events)
            self.send(sse.encode(),content_type='text/event-stream',limits=True)
        else:
            self.send(response,limits=True)


def run(args, temp):
    service_port, gateway_port = available_port(), available_port()
    while gateway_port == service_port:
        gateway_port = available_port()
    service='http://127.0.0.1:'+str(service_port)
    gateway='http://127.0.0.1:'+str(gateway_port)
    upstream=http.server.ThreadingHTTPServer(('127.0.0.1',0),FakeUpstream)
    upstream.daemon_threads=True
    threading.Thread(target=upstream.serve_forever,daemon=True).start()
    fake='http://127.0.0.1:'+str(upstream.server_port)
    secret,password=secrets.token_urlsafe(48),secrets.token_urlsafe(24)
    FakeUpstream.api_secret=secrets.token_urlsafe(24)
    FakeUpstream.oauth_secret=secrets.token_urlsafe(24)
    FakeUpstream.anthropic_secret=secrets.token_urlsafe(24)
    env=dict(os.environ,MASTER_KEY=secrets.token_urlsafe(48),SESSION_SECRET=secrets.token_urlsafe(48),
             INTERNAL_TOKEN=secret,ADMIN_USER='smoke-admin',ADMIN_PASSWORD=password,
             DATABASE_URL='',DB_PATH=str(temp/'smoke.db'),BIND_HOST='127.0.0.1',PORT=str(service_port),
             PUBLIC_DOMAIN='',PUBLIC_BASE_URL=service,UPSTREAM_BASE_URL=fake,
             OPENAI_OAUTH_ISSUER=fake,OPENAI_CHATGPT_BASE_URL=fake+'/backend-api')
    gateway_bin=temp/'gateway'
    subprocess.run(['go','build','-o',str(gateway_bin),'.'],cwd=ROOT/'gateway',check=True)
    service_log=open(temp/'service.log','wb'); gateway_log=open(temp/'gateway.log','wb')
    procs=[]
    try:
        test_jar=temp/'service.jar'
        shutil.copy2(args.jar,test_jar)
        svc=subprocess.Popen(['java','-jar',str(test_jar)],cwd=temp,env=env,stdout=service_log,stderr=subprocess.STDOUT); procs.append(svc)
        wait_ready(service,svc)
        gwenv=dict(env,PORT=str(gateway_port),GATEWAY_BIND_HOST='127.0.0.1',SERVICE_URL=service,
                   OPENAI_API_BASE_URL=fake+'/v1',OPENAI_CODEX_BASE_URL=fake+'/codex')
        gw=subprocess.Popen([str(gateway_bin)],cwd=temp,env=gwenv,stdout=gateway_log,stderr=subprocess.STDOUT); procs.append(gw)
        wait_ready(gateway,gw)
        admin=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        expect(service,'/api/auth/login','POST',{'username':'smoke-admin','password':password},client=admin)
        def mgmt(path,method='GET',body=None,status=200):
            return expect(service,path,method,body,client=admin,status=status)[0]
        def add(name,provider,type_,priority,**credentials):
            accounts=mgmt('/api/accounts','POST',dict(name=name,provider=provider,type=type_,priority=priority,**credentials))['accounts']
            return next(a['id'] for a in accounts if a['name']==name)
        claude_id=add('claude-smoke','ANTHROPIC','API_KEY',1,apiKey=FakeUpstream.anthropic_secret)
        api_id=add('openai-api','OPENAI','API_KEY',10,apiKey=FakeUpstream.api_secret)
        oauth_id=add('openai-oauth','OPENAI','OAUTH_STATIC',20,accessToken=FakeUpstream.oauth_secret,accountUuid='workspace_smoke')
        routing=mgmt('/api/routing-tokens','POST',{'name':'smoke','systemPrompt':'Integration static prompt'})
        token=routing['token']
        proxy=mgmt('/api/proxy-tokens','POST',{'name':'smoke'})['token']
        payload={'model':'gpt-smoke','input':'Hello','store':False}
        catalog,_=expect(gateway,'/openai/v1/models',token=token)
        assert catalog['data'][0]['id']=='gpt-smoke'
        result,headers=expect(gateway,'/openai/v1/responses','POST',payload,token=token,
                              headers={'Cookie':'not-forwarded','ChatGPT-Account-Id':'client-spoof','OpenAI-Organization':'client-spoof'})
        assert result['output'][0]['content'][0]['text']=='OpenAI OK'
        assert headers['x-codex-primary-used-percent']=='25'
        event=next(e for e in reversed(FakeUpstream.events) if e['path']=='/v1/responses')
        h={k.lower():v for k,v in event['headers'].items()}
        assert h['authorization']=='Bearer '+FakeUpstream.api_secret and 'cookie' not in h
        assert 'chatgpt-account-id' not in h and 'openai-organization' not in h
        assert event['body']['instructions'].startswith('Integration static prompt')
        sse,_=expect(gateway,'/openai/v1/responses','POST',dict(payload,stream=True),token=token)
        assert 'response.completed' in sse and 'response.function_call_arguments.done' in sse
        print('PASS native API-key models, JSON/SSE/tools, quota headers, header isolation and static prompt',flush=True)
        mgmt(f'/api/accounts/{api_id}','PATCH',{'enabled':False})
        catalog,_=expect(gateway,'/openai/v1/models',token=token)
        assert catalog['data'][0]['id']=='gpt-smoke' and catalog['models'][0]['slug']=='gpt-smoke'
        result,_=expect(gateway,'/openai/v1/responses','POST',payload,token=token)
        assert result['id']=='resp_smoke'
        event=next(e for e in reversed(FakeUpstream.events) if e['path']=='/codex/responses')
        h={k.lower():v for k,v in event['headers'].items()}
        assert h['authorization']=='Bearer '+FakeUpstream.oauth_secret and h['chatgpt-account-id']=='workspace_smoke'
        assert event['body']['stream'] is True and isinstance(event['body']['input'],list)
        snapshot,_=expect(service,'/gateway/v1/usage?provider=OPENAI',token=token)
        assert snapshot['provider']=='OPENAI' and snapshot['available_accounts']==1
        assert snapshot['rate_limits']['five_hour']['used_percentage']==25
        assert snapshot['rate_limits']['seven_day']['used_percentage']==40
        serialized=json.dumps(snapshot)
        assert 'workspace_smoke' not in serialized and FakeUpstream.oauth_secret not in serialized
        expect(service,'/gateway/v1/usage?provider=OPENAI',token=proxy,status=403)
        print('PASS Codex models, OAuth identity, SSE-to-JSON and anonymous provider-specific limits',flush=True)
        # Codex can put all output in output_item.done and leave the terminal output empty.
        # Encrypted history from that response must stay bound to the original account.
        FakeUpstream.sparse_terminal=True
        session_headers={'X-Proxy-Session-ID':'smoke-stable-session'}
        first,_=expect(gateway,'/openai/v1/responses','POST',payload,token=token,headers=session_headers)
        assert len(first['output'])==3
        assert first['output'][0]['encrypted_content']=='opaque-workspace-smoke'
        assert first['output'][1]['content'][0]['text']=='OpenAI OK'
        assert first['output'][2]['type']=='function_call'
        mgmt(f'/api/accounts/{api_id}','PATCH',{'enabled':True})
        continuation=dict(payload,input=first['output']+[{'type':'function_call_output','call_id':'call_smoke','output':'ok'},
                                                       {'role':'user','content':'Continue'}])
        before=len(FakeUpstream.events)
        expect(gateway,'/openai/v1/responses','POST',continuation,token=token,headers=session_headers)
        attempts=[e for e in FakeUpstream.events[before:] if e['path'].endswith('/responses')]
        assert len(attempts)==1 and attempts[0]['path']=='/codex/responses'
        headers={k.lower():v for k,v in attempts[0]['headers'].items()}
        assert headers['authorization']=='Bearer '+FakeUpstream.oauth_secret
        assert all(key not in headers for key in ('x-proxy-session-id','session-id','thread-id'))
        before=len(FakeUpstream.events)
        expect(gateway,'/openai/v1/responses','POST',continuation,token=token,status=409)
        expect(gateway,'/openai/v1/responses','POST',continuation,token=token,
               headers={'X-Proxy-Session-ID':'unknown-session'},status=409)
        mgmt(f'/api/accounts/{oauth_id}','PATCH',{'enabled':False})
        expect(gateway,'/openai/v1/responses','POST',continuation,token=token,headers=session_headers,status=409)
        assert not any(e['path'].endswith('/responses') for e in FakeUpstream.events[before:])
        mgmt(f'/api/accounts/{oauth_id}','PATCH',{'enabled':True})
        mgmt(f'/api/accounts/{api_id}','PATCH',{'enabled':False})
        FakeUpstream.sparse_terminal=False
        print('PASS sparse terminal output preserves reasoning/text/tools; encrypted continuation stays on owner; unknown/unavailable owner returns409',flush=True)
        recent=mgmt('/api/stats/recent')
        rows=[r for r in recent if r.get('model')=='gpt-smoke']
        assert rows and all(r['inputTokens']==40 and r['cacheReadTokens']==60 and r['outputTokens']==20 for r in rows)
        assert all(r['costKnown'] is False and r['cost']==0 for r in rows)
        mgmt('/api/model-prices','POST',{'pattern':'gpt-smoke','inputPrice':1,'outputPrice':2,'cacheReadPrice':0.5})
        expect(gateway,'/openai/v1/responses','POST',payload,token=token)
        row=next(r for r in mgmt('/api/stats/recent') if r.get('model')=='gpt-smoke')
        assert row['costKnown'] is True and abs(row['cost']-0.00011)<1e-9
        print('PASS real usage persistence, cached-token accounting and explicit/unknown model pricing',flush=True)
        expect(gateway,'/openai/v1/responses','POST',dict(payload,tools=[{'type':'web_search'}]),token=token)
        row=next(r for r in mgmt('/api/stats/recent') if r.get('model')=='gpt-smoke')
        assert row['costKnown'] is False and row['inputTokens']==40 and row['outputTokens']==20
        print('PASS unsupported billable dimensions preserve tokens but never claim complete cost',flush=True)
        # Shared native Responses cannot enforce a USD cap until reservation/reconciliation exists.
        user_password=secrets.token_urlsafe(24)
        limited=mgmt('/api/users','POST',{'username':'limited-smoke','password':user_password,'roles':['user'],'dailyRoutingCostLimit':1})
        limited_client=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        expect(service,'/api/auth/login','POST',{'username':'limited-smoke','password':user_password},client=limited_client)
        limited_token=expect(service,'/api/routing-tokens','POST',{'name':'limited'},client=limited_client)[0]['token']
        before=len(FakeUpstream.events)
        for model in ('gpt-unpriced','gpt-smoke'):
            blocked,_=expect(gateway,'/openai/v1/responses','POST',dict(payload,model=model),token=limited_token,status=403)
            assert blocked['error']['code']=='metering_unsupported'
        assert len(FakeUpstream.events)==before
        quota,_=expect(service,'/gateway/v1/usage?provider=OPENAI&model=gpt-smoke',token=limited_token)
        assert quota['metering_unsupported'] is True and quota['available_accounts']==0
        expect(gateway,'/openai/v1/models',token=limited_token)
        mgmt(f'/api/users/{limited["id"]}','PATCH',{'enabled':False})
        disabled_status=request(gateway,'/openai/v1/models',token=limited_token)[0]
        assert disabled_status in (401,403), f'Disabled user remained authorized: {disabled_status}'
        print('PASS capped shared OpenAI fails closed even with configured prices; catalog/quota consistent; disabled-user token rejected',flush=True)
        # Unsupported stateful features are rejected before any upstream request.
        before=len(FakeUpstream.events)
        for patch in ({'previous_response_id':'resp_other'},{'background':True},{'store':True},
                      {'input':[{'type':'item_reference','id':'msg_other'}]},
                      {'input':[{'role':'user','content':[{'type':'input_file','file_id':'file_other'}]}]},
                      {'tools':[{'type':'file_search','vector_store_ids':['vs_other']}]},
                      {'tools':[{'type':'shell','environment':{'type':'container_auto','skills':[{'type':'skill_reference','skill_id':'skill_other'}]}}]}):
            expect(gateway,'/openai/v1/responses','POST',dict(payload,**patch),token=token,status=400)
        assert len(FakeUpstream.events)==before
        expect(gateway,'/openai/v1/models',token=proxy,status=401)
        expect(gateway,'/openai/v1/files',token=token,status=404)
        mgmt(f'/api/accounts/{oauth_id}','PATCH',{'enabled':False})
        expect(gateway,'/openai/v1/responses','POST',payload,token=token,status=503)
        mgmt(f'/api/accounts/{oauth_id}','PATCH',{'enabled':True})
        mgmt(f'/api/routing-tokens/{routing["id"]}/enabled','PATCH',{'enabled':False})
        expect(gateway,'/openai/v1/models',token=token,status=401)
        expect(service,'/gateway/v1/usage?provider=OPENAI',token=token,status=401)
        mgmt(f'/api/routing-tokens/{routing["id"]}/enabled','PATCH',{'enabled':True})
        print('PASS stateless contract, wrong-token rejection and immediate account/token disable',flush=True)
        # 429 on a higher-priority API account rotates to Codex, never to the Claude account.
        mgmt(f'/api/accounts/{api_id}','PATCH',{'enabled':True})
        FakeUpstream.fail_api=True
        start=len(FakeUpstream.events)
        expect(gateway,'/openai/v1/responses','POST',payload,token=token)
        used=[e['path'] for e in FakeUpstream.events[start:] if e['path'].endswith('/responses')]
        assert used==['/v1/responses','/codex/responses'],used
        FakeUpstream.fail_api=False
        message,_=expect(gateway,'/v1/messages','POST',{'model':'claude-smoke','max_tokens':16,'messages':[{'role':'user','content':'Hello'}]},token=proxy)
        assert message['content'][0]['text']=='Claude OK'
        last=next(e for e in reversed(FakeUpstream.events) if e['path']=='/v1/messages')
        assert last['headers'].get('X-Api-Key',last['headers'].get('x-api-key'))==FakeUpstream.anthropic_secret
        print('PASS cross-process 429 rotation stays within OpenAI; Claude datapath unchanged',flush=True)
        # Device flow is tested through the real management routes and token exchange.
        flow=mgmt('/api/my/accounts/openai/oauth/start','POST')
        poll={'flowId':flow['flowId'],'name':'device-smoke'}
        state=mgmt('/api/my/accounts/openai/oauth/poll','POST',poll)
        assert state['status']=='pending'
        for _ in range(4):
            time.sleep(5.1)
            state=mgmt('/api/my/accounts/openai/oauth/poll','POST',poll)
            if state['status']=='complete':
                break
        assert state['status']=='complete', 'Device flow did not complete within its polling window'
        again=mgmt('/api/my/accounts/openai/oauth/poll','POST',poll)
        assert again['accountId']==state['accountId']
        personal=mgmt('/api/my/accounts')['accounts']
        added=[a for a in personal if a['name']=='device-smoke']
        assert len(added)==1 and added[0]['provider']=='OPENAI' and added[0]['accountUuid']=='workspace_device'
        assert 'access_token' not in json.dumps(again) and 'refresh_token' not in json.dumps(added)
        with sqlite3.connect(temp/'smoke.db') as db:
            encrypted=[row[0] for row in db.execute('SELECT cipher_blob FROM account_secrets')]
        assert len(encrypted)==4 and all(encrypted)
        for credential in (FakeUpstream.api_secret,FakeUpstream.oauth_secret,FakeUpstream.anthropic_secret):
            assert all(credential not in blob for blob in encrypted), 'Plaintext upstream credential in database'
        print('PASS real device login pending/completion, encrypted account creation and idempotent completion',flush=True)
    finally:
        for proc in reversed(procs):
            proc.terminate()
            try: proc.wait(timeout=8)
            except subprocess.TimeoutExpired: proc.kill(); proc.wait(timeout=5)
        service_log.close(); gateway_log.close()
        upstream.shutdown(); upstream.server_close()


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar',type=Path,default=ROOT/'service/build/libs/claude-proxy-0.1.0-all.jar')
    parser.add_argument('--keep',action='store_true',help='Keep private temporary test logs/DB for debugging')
    args=parser.parse_args()
    args.jar=args.jar.resolve()
    if not args.jar.is_file():
        parser.error('Build the service fatJar before running this test')
    temp=Path(tempfile.mkdtemp(prefix='claude-openai-smoke-'))
    try:
        run(args,temp)
    finally:
        if args.keep:
            print('Private test artifacts:',temp)
        else:
            shutil.rmtree(temp,ignore_errors=True)
