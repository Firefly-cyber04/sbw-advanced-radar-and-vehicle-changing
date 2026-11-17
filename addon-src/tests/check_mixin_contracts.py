from pathlib import Path
import re,subprocess,json
import os,shutil
root=Path(__file__).resolve().parents[1]
jar=root.parent/'mods/superbwarfare-0.8.9.2-mc1.20.1-65d985a64-all.jar'
java=shutil.which('javap')
if not java and os.environ.get('JAVA_HOME'):
 java=str(Path(os.environ['JAVA_HOME'])/'bin'/'javap')
if not java:raise SystemExit('A JDK with javap is required')
checks=[];unchecked=[]
for source in (root/'src/main/java/com/f16addon/mixin').glob('*.java'):
 text=source.read_text()
 imports=dict((x.rsplit('.',1)[-1],x) for x in re.findall(r'import ([\w.$]+);',text))
 annotation=re.search(r'@Mixin\((.*?)\)\s*public',text,re.S).group(1)
 quoted=re.search(r'targets\s*=\s*\{?"([^"]+)"',annotation)
 if quoted: target=quoted.group(1)
 else: target=imports[re.search(r'(\w+)\.class',annotation).group(1)]
 target_jar = jar if target.startswith('com.atsuishio.superbwarfare.') else root.parent/'mods/dragonrise_reforge-1.5.1-release.jar'
 api=subprocess.check_output([java,'-p','-s','-classpath',str(target_jar),target],text=True)
 methods=set();fields=set();previous=''
 for line in api.splitlines():
  if 'descriptor:' in line:
   desc=line.split('descriptor:',1)[1].strip()
   if '(' in previous:
    name=re.search(r'([\w$]+)\([^)]*\);$',previous)
    if name: methods.add((name.group(1),desc))
   else:
    name=re.search(r'([\w$]+);$',previous)
    if name:fields.add(name.group(1))
  elif line.strip():previous=line.strip()
 for match in re.finditer(r'method\s*=\s*(?:\{([^}]+)\}|"([^"]+)")',text):
  choices=re.findall(r'"([^"]+)"',match.group(1)) if match.group(1) else [match.group(2)]
  ok=any(((choice.split('(',1)[0], '('+choice.split('(',1)[1]) in methods) if '(' in choice else any(n==choice for n,d in methods) for choice in choices)
  start=max(text.rfind('@Inject',0,match.start()),text.rfind('@Redirect',0,match.start()))
  end=re.search(r'\n\s+(?:private|public|protected) ',text[match.end():])
  annotation=text[start:match.end()+(end.start() if end else 200)]
  optional=bool(re.search(r'require\s*=\s*0',annotation))
  checks.append({'source':source.name,'target':target,'selectors':choices,'present':ok,'optional':optional})
 for field in re.findall(r'@Shadow\s+(?:@Final\s+)?private\s+(?:static\s+)?[\w<>?, .\[\]]+\s+(\w+)\s*;',text):
  checks.append({'source':source.name,'target':target,'shadowField':field,'present':field in fields})
report={'checks':checks,'unchecked':unchecked,'passed':sum(x['present'] for x in checks),'failed':sum(not x['present'] and not x.get('optional',False) for x in checks), 'optionalMissing':sum(not x['present'] and x.get('optional',False) for x in checks)}
print(json.dumps(report,indent=2))
print('Mixin target contracts:',report['passed'],'present,',report['failed'],'required missing;',len(unchecked),'unchecked')
for x in checks:
 if not x['present'] and not x.get('optional',False):print(x)
if report['failed']:raise SystemExit(1)
