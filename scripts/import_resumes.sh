#!/bin/bash
SRC=/Users/mac/Desktop/XrmProject/Qylin-AIEmployee/backend/uploads/resumes
TOKEN=$(cat /tmp/token.txt)
API=http://127.0.0.1:18080/api/v2/kb/upload
LOG=/tmp/resume-import
mkdir -p $LOG
ls "$SRC"/*.pdf "$SRC"/*.jpg "$SRC"/*.png 2>/dev/null > $LOG/all.txt
# 过滤已导入（按文件名比对）
/tmp/mindcrew-runtime/mysql-8.4.3-macos14-arm64/bin/mysql -uroot -proot -h127.0.0.1 docmind -N -e \
  "select name from kb_knowledge_base where deleted=0" 2>/dev/null > $LOG/existing.txt
while IFS= read -r f; do
  b=$(basename "$f")
  grep -Fxq "$b" $LOG/existing.txt || echo "$f"
done < $LOG/all.txt > $LOG/todo.txt
TOTAL=$(wc -l < $LOG/todo.txt)
echo "TODO=$TOTAL" | tee $LOG/progress.log
i=0
split -l 80 $LOG/todo.txt $LOG/batch_
for batch in $LOG/batch_*; do
  xargs -P 4 -I{} curl -s -o /dev/null --max-time 60 -X POST "$API" \
    -H "Authorization: Bearer $TOKEN" -F "file=@{}" -F "category=简历" < "$batch"
  i=$((i+80))
  # 等待积压下降，避免任务队列被打爆
  for _ in $(seq 1 200); do
    pending=$(/tmp/mindcrew-runtime/mysql-8.4.3-macos14-arm64/bin/mysql -uroot -proot -h127.0.0.1 docmind -N -e \
      "select count(*) from kb_knowledge_base where status in ('uploading','processing');" 2>/dev/null)
    [ "${pending:-999}" -lt 30 ] && break
    sleep 3
  done
  echo "uploaded~$i/$TOTAL pending=$pending $(date +%H:%M:%S)" >> $LOG/progress.log
done
echo "UPLOAD_DONE" >> $LOG/progress.log
