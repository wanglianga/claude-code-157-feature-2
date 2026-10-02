#!/usr/bin/env bash
# 端到端冒烟: 申请->审批->发证->扫码->异常->替换->退证->看板->归档
# 前置: 应用已在 localhost:8080 运行, 本地 Postgres 已初始化
set -euo pipefail
BASE="http://localhost:8080"
H='-H Content-Type:application/json'
j() { python3 -c "import sys,json;print(json.load(sys.stdin)$1)"; }

echo "== 病区列表 =="
curl -s $BASE/api/wards | python3 -m json.tool

echo "== 1. 家属提交申请 =="
APPLY=$(curl -s $H -X POST $BASE/api/applications -d '{
  "patientInpatientNo":"IP-SMOKE-01","wardCode":"W3A","bedNo":"09-2",
  "escortName":"周敏","escortIdNo":"IDSMOKE01","escortPhone":"13811112222",
  "relationToPatient":"女儿","healthCommitment":true,"screeningType":"NAT",
  "screeningValidUntil":"2026-12-01T00:00:00Z",
  "periodStart":"2026-09-26T00:00:00Z","periodEnd":"2026-10-10T00:00:00Z",
  "needNightCare":false}')
echo "$APPLY" | python3 -m json.tool
APP_NO=$(echo "$APPLY" | j "['applicationNo']")
CASE_NO=$(echo "$APPLY" | j "['caseNo']")

echo "== 2. 护士站审批 =="
curl -s $H -X POST $BASE/api/applications/$APP_NO/decision -d '{
  "approved":true,"decidedBy":"NURSE-SMOKE","note":"病情需要,同意","approveNightCare":false}' | python3 -m json.tool

echo "== 3. 查看陪护证(含下游同步状态) =="
PERMIT_NO=$(curl -s $BASE/api/cases/$CASE_NO/archive | j "['permits'][0]['permitNo']")
TOKEN=$(curl -s $BASE/api/permits/$PERMIT_NO | j "['qrToken']")
echo "permit=$PERMIT_NO token=$TOKEN"
curl -s $BASE/api/permits/$PERMIT_NO | python3 -m json.tool

echo "== 4. 正常时段进入/离开 =="
curl -s $H -X POST $BASE/api/access/scan -d "{\"token\":\"$TOKEN\",\"direction\":\"IN\",\"gateLocation\":\"W3A-东门闸机\",\"scannedAt\":\"2026-09-27T02:00:00Z\"}" | python3 -m json.tool
curl -s $H -X POST $BASE/api/access/scan -d "{\"token\":\"$TOKEN\",\"direction\":\"OUT\",\"gateLocation\":\"W3A-东门闸机\",\"scannedAt\":\"2026-09-27T03:00:00Z\"}" | python3 -m json.tool

echo "== 5. 夜间非允许时段进入 -> 拒绝+异常 =="
curl -s $H -X POST $BASE/api/access/scan -d "{\"token\":\"$TOKEN\",\"direction\":\"IN\",\"gateLocation\":\"W3A-东门闸机\",\"scannedAt\":\"2026-09-27T14:30:00Z\"}" | python3 -m json.tool

echo "== 6. 陪护人临时生病, 申请替换并即时审批 =="
curl -s $H -X POST $BASE/api/replacements -d "{
  \"oldPermitNo\":\"$PERMIT_NO\",\"reason\":\"ESCORT_SICK\",\"detail\":\"周敏发烧,由母亲接替\",
  \"escortName\":\"周母\",\"escortIdNo\":\"IDSMOKENEW\",\"escortPhone\":\"13833334444\",
  \"relationToPatient\":\"配偶\",\"healthCommitment\":true,\"screeningType\":\"NAT\",
  \"screeningValidUntil\":\"2026-12-01T00:00:00Z\",
  \"periodEnd\":\"2026-10-10T00:00:00Z\",\"autoApprove\":true,\"decidedBy\":\"NURSE-SMOKE\"}" | python3 -m json.tool

echo "== 7. 病区通知(应含当前照护人变更) =="
curl -s "$BASE/api/wards/W3A/notices?unreadOnly=true" | python3 -c "
import sys,json
for n in json.load(sys.stdin)[:8]: print(n['type'],'-',n['message'])"

echo "== 8. 看板 =="
curl -s "$BASE/api/wards/W3A/dashboard?days=7" | python3 -m json.tool

echo "== 9. 新陪护人退证 =="
NEW_PERMIT=$(curl -s $BASE/api/cases/$CASE_NO/archive | j "['permits'][-1]['permitNo']")
curl -s $H -X POST $BASE/api/permits/$NEW_PERMIT/return -d '{"returnedBy":"周母","channel":"WARD_DESK","note":"患者出院"}' | python3 -m json.tool

echo "== 10. 完整归档事件流 =="
curl -s $BASE/api/cases/$CASE_NO/archive | python3 -c "
import sys,json
a=json.load(sys.stdin)
print('caseNo:',a['caseNo'],'status:',a['status'],'currentEscort:',a['currentEscortName'])
print('applications:',len(a['applications']),'permits:',len(a['permits']),
      'scans:',len(a['scans']),'replacements:',len(a['replacements']),
      'violations:',len(a['violations']))
for e in a['events']: print(' -',e['eventType'],'|',e['summary'])"

echo "== 11. 关闭记录 =="
curl -s $H -X POST $BASE/api/cases/$CASE_NO/close -d '{"operator":"NURSE-SMOKE"}' | python3 -m json.tool
echo "SMOKE OK: CASE_NO=$CASE_NO"

# ========== 临时替换(原陪护夜间发烧): 无人陪护 -> 巡查提醒 -> 到位核验交接 ==========

echo "== 12. 第二位患者: 申请+审批发证 =="
APPLY2=$(curl -s $H -X POST $BASE/api/applications -d '{
  "patientInpatientNo":"IP-SMOKE-02","wardCode":"W3A","bedNo":"09-3",
  "escortName":"周敏","escortIdNo":"IDSMOKE21","escortPhone":"13811112222",
  "relationToPatient":"女儿","healthCommitment":true,"screeningType":"NAT",
  "screeningValidUntil":"2026-12-01T00:00:00Z",
  "periodStart":"2026-10-01T00:00:00Z","periodEnd":"2026-10-20T00:00:00Z",
  "needNightCare":true}')
APP_NO2=$(echo "$APPLY2" | j "['applicationNo']")
CASE_NO2=$(echo "$APPLY2" | j "['caseNo']")
curl -s $H -X POST $BASE/api/applications/$APP_NO2/decision -d '{
  "approved":true,"decidedBy":"NURSE-SMOKE","approveNightCare":true,"note":"夜间陪护同意"}' >/dev/null
PERMIT_NO2=$(curl -s $BASE/api/cases/$CASE_NO2/archive | j "['permits'][0]['permitNo']")
echo "case2=$CASE_NO2 permit2=$PERMIT_NO2"

echo "== 13. 原陪护夜间发烧, 家属申请临时替换(旧陪护已离院) =="
TEMP=$(curl -s $H -X POST $BASE/api/replacements/temporary -d "{
  \"oldPermitNo\":\"$PERMIT_NO2\",\"reason\":\"ESCORT_SICK\",\"detail\":\"周敏夜间发烧39度,由妹妹接替\",
  \"escortName\":\"周兰\",\"escortIdNo\":\"IDSMOKE22\",\"escortPhone\":\"13833334444\",
  \"relationToPatient\":\"妹妹\",\"healthCommitment\":true,\"screeningType\":\"NAT\",
  \"screeningValidUntil\":\"2026-12-01T00:00:00Z\",\"periodEnd\":\"2026-10-20T00:00:00Z\"}")
echo "$TEMP" | python3 -m json.tool
REPL_ID=$(echo "$TEMP" | j "['replacementId']")
# 旧证立即停用
curl -s $BASE/api/permits/$PERMIT_NO2 | python3 -c "import sys,json;p=json.load(sys.stdin);print('旧证状态:',p['status'])"

echo "== 14. 护士站看到患者短时无人陪护, 安排巡查 =="
curl -s $BASE/api/wards/W3A/unattended-patients | python3 -c "
import sys,json
for p in json.load(sys.stdin):
    if p['caseNo']=='$CASE_NO2':
        print('无人陪护:',p['patientInpatientNo'],'原陪护:',p['previousEscortName'],
              '接替人:',p['newEscortName'],p['relationToPatient'],'无人陪护分钟:',p['unattendedMinutes'])"

echo "== 15. 护士站临时创建进食/翻身/夜间观察提醒 =="
R_FEED=$(curl -s $H -X POST $BASE/api/care-reminders -d "{
  \"caseNo\":\"$CASE_NO2\",\"type\":\"FEEDING\",\"note\":\"协助进水和流食\",\"createdBy\":\"NURSE-SMOKE\"}")
R_FEED_ID=$(echo "$R_FEED" | j "['id']")
curl -s $H -X POST $BASE/api/care-reminders -d "{
  \"caseNo\":\"$CASE_NO2\",\"type\":\"TURNING\",\"note\":\"每2小时翻身\",\"createdBy\":\"NURSE-SMOKE\"}" >/dev/null
curl -s $H -X POST $BASE/api/care-reminders -d "{
  \"caseNo\":\"$CASE_NO2\",\"type\":\"NIGHT_WATCH\",\"note\":\"夜间每小时观察\",\"createdBy\":\"NURSE-SMOKE\"}" >/dev/null
echo "待巡查提醒数: $(curl -s "$BASE/api/wards/W3A/care-reminders?status=NURSE_PENDING" | python3 -c "import sys,json;print(len(json.load(sys.stdin)))")"
# 护士先执行一次进食照护(新陪护未到)
curl -s $H -X POST $BASE/api/care-reminders/$R_FEED_ID/nurse-done -d '{"operator":"NURSE-SMOKE","note":"已协助进水"}' \
  | python3 -c "import sys,json;r=json.load(sys.stdin);print('进食提醒状态:',r['status'],'执行护士:',r['nurseDoneBy'])"

echo "== 16. 新陪护到院: 核验缺一不可, 三项通过后到位交接 =="
curl -s $H -X POST $BASE/api/replacements/$REPL_ID/arrival -d '{"operator":"NURSE-SMOKE"}' \
  | python3 -c "import sys,json;print('未核验身份被拒:',json.load(sys.stdin)['message'])"
curl -s $H -X POST $BASE/api/replacements/$REPL_ID/arrival -d '{
  "operator":"NURSE-SMOKE","identityVerified":true,"healthCommitmentAccepted":true,"relationVerified":true,
  "note":"人证一致,无发热,确系患者妹妹"}' | python3 -m json.tool

echo "== 17. 提醒转家属接手/看板/归档, 退证关闭 =="
curl -s "$BASE/api/care-reminders?wardCode=W3A" | python3 -c "
import sys,json
for r in json.load(sys.stdin):
    if r['caseNo']=='$CASE_NO2':
        print('提醒',r['type'],'->',r['status'],'接手人:',r['takenOverBy'],'交接时间:',r['takenOverAt'])"
curl -s "$BASE/api/wards/W3A/dashboard" | python3 -c "
import sys,json;d=json.load(sys.stdin)
print('看板无人陪护患者数:',d['unattendedPatientCount'],'待巡查提醒数:',d['pendingReminderCount'])"
curl -s $BASE/api/cases/$CASE_NO2/archive | python3 -c "
import sys,json
a=json.load(sys.stdin)
print('careStatus:',a['careStatus'],'当前陪护:',a['currentEscortName'])
print('交接时间 newArrivedAt:',a['replacements'][0]['newArrivedAt'],'无人陪护分钟:',a['replacements'][0]['gapMinutes'])
for e in a['events']:
    if e['eventType'].startswith('TEMP_REPLACEMENT') or e['eventType'].startswith('CARE_REMINDER'):
        print(' -',e['eventType'],'|',e['summary'])"
NEW_PERMIT2=$(curl -s $BASE/api/cases/$CASE_NO2/archive | j "['permits'][-1]['permitNo']")
curl -s $H -X POST $BASE/api/permits/$NEW_PERMIT2/return -d '{"returnedBy":"周兰","channel":"WARD_DESK","note":"患者出院"}' >/dev/null
curl -s $H -X POST $BASE/api/cases/$CASE_NO2/close -d '{"operator":"NURSE-SMOKE"}' >/dev/null
echo "SMOKE TEMP-REPLACEMENT OK: CASE_NO=$CASE_NO2 REPLACEMENT=$REPL_ID"
