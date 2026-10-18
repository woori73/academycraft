#!/usr/bin/env python3
"""New-account administrator only. Reuse existing ALB/cert/EC2; no DNS writes."""
import fnmatch, json, os, socket, subprocess
from datetime import datetime, timezone
from pathlib import Path
os.umask(0o077)
os.environ['AWS_DEFAULT_REGION']='ap-northeast-2'
HOST='academy.feel2real.co.kr'
IID='i-0414de33d2803c723'
def aws(*args):
 p=subprocess.run(['aws',*args,'--output','json'],capture_output=True,text=True)
 if p.returncode: raise SystemExit('AWS failed: '+args[0]+' '+args[1]+'; inspect in administrator session')
 return json.loads(p.stdout) if p.stdout.strip() else {}
def require(ok,msg):
 if not ok: raise SystemExit(msg)
def ips(host):return {x[4][0] for x in socket.getaddrinfo(host,443)}
require(aws('sts','get-caller-identity')['Account']=='425805163960','Wrong account')
albs=aws('elbv2','describe-load-balancers','--names','f2r-alb')['LoadBalancers']
require(len(albs)==1,'Ambiguous ALB');alb=albs[0]
require(alb['State']['Code']=='active','ALB not active')
inst=aws('ec2','describe-instances','--instance-ids',IID)['Reservations'][0]['Instances'][0]
require(inst['State']['Name']=='running' and inst['VpcId']==alb['VpcId'],'Wrong instance/VPC')
listeners=aws('elbv2','describe-listeners','--load-balancer-arn',alb['LoadBalancerArn'])['Listeners']
ls=[x for x in listeners if x['Port']==443 and x['Protocol']=='HTTPS']
require(len(ls)==1,'Existing HTTPS443 listener required');listener=ls[0]
certs=aws('elbv2','describe-listener-certificates','--listener-arn',listener['ListenerArn'])['Certificates']
covered=False
for c in certs:
 cert=aws('acm','describe-certificate','--certificate-arn',c['CertificateArn'])['Certificate']
 if cert['Status']=='ISSUED' and datetime.fromisoformat(cert['NotAfter']).astimezone(timezone.utc)>datetime.now(timezone.utc) and any(fnmatch.fnmatchcase(HOST,n) for n in cert['SubjectAlternativeNames']):covered=True
require(covered,'STOP: attached existing certificate does not cover '+HOST+'; no certificate replaced')
try: require(bool(ips(HOST)&ips(alb['DNSName'])),'STOP: DNS is not pointing to existing ALB: '+alb['DNSName'])
except OSError: raise SystemExit('STOP: DNS required: '+HOST+' -> '+alb['DNSName']+' (no AWS mutation made)')
require(len(alb['SecurityGroups'])==1 and len(inst['SecurityGroups'])==1,'Review multiple SGs manually')
albsg=alb['SecurityGroups'][0];appsg=inst['SecurityGroups'][0]['GroupId']
sgs=aws('ec2','describe-security-groups','--group-ids',appsg,albsg)['SecurityGroups']
byid={x['GroupId']:x for x in sgs}
def matches(p):return p['IpProtocol']=='-1' or (p['IpProtocol'] in ('tcp','6') and p.get('FromPort',0)<=8082<=p.get('ToPort',65535))
def source_only(p,sg):
 return not p.get('IpRanges') and not p.get('Ipv6Ranges') and not p.get('PrefixListIds') and all(x['GroupId']==sg for x in p.get('UserIdGroupPairs',[]))
app_rules=[p for p in byid[appsg]['IpPermissions'] if matches(p)]
require(all(source_only(p,albsg) for p in app_rules),'STOP: another ingress source already exposes 8082; existing rules unchanged')
# Existing broad ALB egress may be retained; do not widen it silently.
egress=any(matches(p) and (any(x.get('CidrIp')=='0.0.0.0/0' for x in p.get('IpRanges',[])) or any(x['GroupId']==appsg for x in p.get('UserIdGroupPairs',[]))) for p in byid[albsg]['IpPermissionsEgress'])
require(egress,'STOP: ALB egress needs administrator review for app SG TCP8082')
rules=aws('elbv2','describe-rules','--listener-arn',listener['ListenerArn'])['Rules']
def values(c):return c.get('HostHeaderConfig',{}).get('Values',c.get('Values',[]))
# Stop if an existing wildcard host condition could overlap the new host.
existing=[]
for r in rules:
 for c in r.get('Conditions',[]):
  if c['Field']=='host-header' and any(fnmatch.fnmatchcase(HOST,v) for v in values(c)):
   require(values(c)==[HOST] and len(r['Conditions'])==1,'Overlapping host rule; manual review required')
   existing.append(r)
require(len(existing)<=1,'Duplicate host rules')
tgs=aws('elbv2','describe-target-groups','--load-balancer-arn',alb['LoadBalancerArn'])['TargetGroups']
# Include unattached target groups by listing before any write.
all_tgs=aws('elbv2','describe-target-groups')['TargetGroups']
found=[t for t in all_tgs if t['TargetGroupName']=='f2r-academycraft-tg']
require(len(found)<=1,'Duplicate target groups')
if found:
 tg=found[0];require(tg['VpcId']==alb['VpcId'] and tg['Port']==8082 and tg['Protocol']=='HTTP' and tg['TargetType']=='instance' and tg.get('HealthCheckPath')=='/actuator/health','Existing target group differs')
 targets=aws('elbv2','describe-target-health','--target-group-arn',tg['TargetGroupArn'])['TargetHealthDescriptions']
 require(all(t['Target']['Id']==IID and t['Target']['Port']==8082 for t in targets),'Unexpected existing targets')
else:tg=None
if existing:
 actions=existing[0]['Actions']
 require(tg is not None and len(actions)==1 and actions[0]['Type']=='forward' and actions[0].get('TargetGroupArn')==tg['TargetGroupArn'],'Existing host action differs; no overwrite')
 groups=actions[0].get('ForwardConfig',{}).get('TargetGroups',[])
 require(not groups or (len(groups)==1 and groups[0]['TargetGroupArn']==tg['TargetGroupArn']),'Existing weighted forwarding differs')
state=Path('academycraft-routing-before.json')
require(not state.exists(),'State file already exists: keep it and use a fresh admin directory to avoid overwriting rollback evidence')
state.write_text(json.dumps({'alb':alb,'listener':listener,'rules':rules,'groups':sgs,'target':tg,'created':[]},indent=2))
created=[]
def record(kind,value):
 created.append({'kind':kind,'value':value});d=json.loads(state.read_text());d['created']=created;state.write_text(json.dumps(d,indent=2))
if tg is None:
 tg=aws('elbv2','create-target-group','--name','f2r-academycraft-tg','--protocol','HTTP','--port','8082','--vpc-id',alb['VpcId'],'--target-type','instance','--health-check-path','/actuator/health','--tags','Key=Project,Value=AcademyCraft')['TargetGroups'][0]
 record('target-group',tg['TargetGroupArn'])
aws('elbv2','register-targets','--target-group-arn',tg['TargetGroupArn'],'--targets','Id='+IID+',Port=8082')
if not app_rules:
 p=[{'IpProtocol':'tcp','FromPort':8082,'ToPort':8082,'UserIdGroupPairs':[{'GroupId':albsg,'Description':'AcademyCraft from existing ALB only'}]}]
 result=aws('ec2','authorize-security-group-ingress','--group-id',appsg,'--ip-permissions',json.dumps(p))
 for r in result.get('SecurityGroupRules',[]):record('security-group-rule',r['SecurityGroupRuleId'])
if not existing:
 used={int(r['Priority']) for r in rules if r['Priority']!='default'};priority=next(i for i in range(1,50001) if i not in used)
 result=aws('elbv2','create-rule','--listener-arn',listener['ListenerArn'],'--priority',str(priority),'--conditions',json.dumps([{'Field':'host-header','HostHeaderConfig':{'Values':[HOST]}}]),'--actions',json.dumps([{'Type':'forward','TargetGroupArn':tg['TargetGroupArn']}]))
 record('listener-rule',result['Rules'][0]['RuleArn'])
print('PASS: existing ALB/certificate reused; 8082 ingress from ALB only. No EC2/ALB/DNS created.')
print('Rollback record: '+str(state.resolve()))
print('Targets may remain unhealthy until AcademyCraft is deployed.')
