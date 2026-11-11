import json, re, subprocess
MYSQL="/tmp/mindcrew-runtime/mysql-8.4.3-macos14-arm64/bin/mysql"
def q(sql):
    out=subprocess.run([MYSQL,"-uroot","-proot","-h127.0.0.1","docmind","-N","-B","-e",sql],
                       capture_output=True,text=True).stdout
    return out

fields=json.load(open('/tmp/resume_fields2.json'))
ids=sorted(int(k) for k in fields)
rows={}
for line in q("SELECT id, HEX(name), HEX(IFNULL(description,'')) FROM kb_knowledge_base WHERE category IN ('简历','resume') AND id IN (%s);"%','.join(map(str,ids))).splitlines():
    if '\t' not in line: continue
    i,nhex,dhex=line.split('\t')
    rows[int(i)]=(bytes.fromhex(nhex).decode('utf-8','ignore'), bytes.fromhex(dhex).decode('utf-8','ignore'))

# 缺名编号
missing=0
used={}
backup=[]; updates=[]
for i in ids:
    orig_name, orig_desc = rows.get(i,('',''))
    name,pos = fields[str(i)]
    if name:
        base=f"{name}-{pos if pos else '通用岗'}"
    elif pos:
        missing+=1
        base=f"候选人{missing:03d}-{pos}"
    else:
        missing+=1
        base=f"候选人{missing:03d}-未识别岗位"
    base=re.sub(r'[\x00-\x1f\x7f]','',base).replace('\n',' ').strip()[:200]
    if not base or base in ('-',): base=f"候选人999-未识别岗位"
    # 去重
    cnt=used.get(base,0)+1
    used[base]=cnt
    final=base if cnt==1 else f"{base}-{cnt}"[:200]
    desc=f"原文件：{orig_name}" if not orig_desc.startswith('原文件') else orig_desc
    desc=re.sub(r'[\x00-\x1f\x7f]','',desc).strip()
    backup.append({"id":i,"name":orig_name,"description":orig_desc})
    updates.append((i,final,desc[:500]))

with open('/tmp/resume_rename_backup.json','w',encoding='utf-8') as f:
    json.dump(backup,f,ensure_ascii=False,indent=1)

def esc(s): return s.replace("\\","\\\\").replace("'","''")
with open('/tmp/rename.sql','w',encoding='utf-8') as f:
    f.write("SET NAMES utf8mb4;\n")
    for i,name,desc in updates:
        f.write(f"UPDATE kb_knowledge_base SET name='{esc(name)}', description='{esc(desc)}' WHERE id={i};\n")
print("to_update",len(updates),"missing_name",missing)
print("samples:")
for i,name,desc in updates[:8]: print(i,name,'|',desc[:40])
