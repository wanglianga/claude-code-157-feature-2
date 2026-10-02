#!/usr/bin/env bash
# 端到端冒烟: 申请->审批->发证->扫码->异常->临时替换(核验/空窗/巡查/看护提醒/交接)->退证->看板->归档
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
  "screeningValidUntil":"2026-10-20T00:00:00Z",
  "periodStart":"2026-09-26T00:00:00Z","periodEnd":"2026-10-30T00:00:00Z",
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
curl -s $H -X POST $BASE/api/access/scan -d "{\"token\":\"$TOKEN\",\"direction\":\"IN\",\"gateLocation\":\"W3A-东门闸机\",\"scannedAt\":\"2026-10-03T02:00:00Z\"}" | python3 -m json.tool
curl -s $H -X POST $BASE/api/access/scan -d "{\"token\":\"$TOKEN\",\"direction\":\"OUT\",\"gateLocation\":\"W3A-东门闸机\",\"scannedAt\":\"2026-10-03T03:00:00Z\"}" | python3 -m json.tool

echo "== 5. 夜间非允许时段进入 -> 拒绝+异常 =="
curl -s $H -X POST $BASE/api/access/scan -d "{\"token\":\"$TOKEN\",\"direction\":\"IN\",\"gateLocation\":\"W3A-东门闸机\",\"scannedAt\":\"2026-10-03T14:30:00Z\"}" | python3 -m json.tool

echo "== 6. 原陪护夜间发烧, 家属申请临时替换(新亲属身份/健康承诺/关系) =="
TR=$(curl -s $H -X POST $BASE/api/temporary-replacements -d "{
  \"oldPermitNo\":\"$PERMIT_NO\",\"reason\":\"ESCORT_SICK\",\"detail\":\"周敏夜间发烧38.8度,由母亲接替\",
  \"escortName\":\"周母\",\"escortIdNo\":\"IDSMOKENEW\",\"escortPhone\":\"13833334444\",
  \"relationToPatient\":\"配偶\",\"healthCommitment\":true,\"screeningType\":\"NAT\",
  \"screeningValidUntil\":\"2026-10-20T00:00:00Z\",\"periodEnd\":\"2026-10-30T00:00:00Z\",
  \"needNightCare\":false,\"expectedArriveAt\":\"2026-10-03T01:00:00Z\"}")
echo "$TR" | python3 -m json.tool
TR_ID=$(echo "$TR" | j "['replacementId']")

echo "== 7. 护士站核验身份/健康承诺/与患者关系后批准 =="
curl -s $H -X POST $BASE/api/temporary-replacements/$TR_ID/approval -d '{
  "decidedBy":"NURSE-SMOKE","approved":true,"relationVerified":true,"healthVerified":true,
  "note":"身份证与本人一致,确认为患者配偶,筛查有效"}' | python3 -m json.tool

echo "== 8. 旧陪护离院 -> 患者短时无人陪护, 护士站安排巡查+看护提醒 =="
curl -s $H -X POST $BASE/api/temporary-replacements/$TR_ID/old-escort-leave -d '{
  "operator":"NURSE-SMOKE","note":"周敏发烧由家属陪同离院"}' | python3 -m json.tool

echo "-- 无人陪护患者列表 --"
curl -s $BASE/api/wards/W3A/unattended | python3 -m json.tool

PATROL=$(curl -s $H -X POST $BASE/api/temporary-replacements/$TR_ID/patrols -d '{
  "scheduledBy":"NURSE-SMOKE","scheduledAt":"2026-10-03T00:30:00Z"}')
echo "$PATROL" | python3 -m json.tool
PATROL_ID=$(echo "$PATROL" | j "['patrolId']")
curl -s $H -X POST $BASE/api/temporary-replacements/$TR_ID/patrols/$PATROL_ID/complete -d '{
  "patrolledBy":"NURSE-SMOKE","result":"患者生命体征平稳,已协助饮水"}' | python3 -m json.tool

REM_FEED=$(curl -s $H -X POST $BASE/api/care-reminders -d "{
  \"replacementId\":$TR_ID,\"type\":\"FEEDING\",\"content\":\"06:00协助进流食\",
  \"remindAt\":\"2026-10-03T06:00:00Z\",\"createdBy\":\"NURSE-SMOKE\"}")
echo "$REM_FEED" | python3 -m json.tool
REM_TURN=$(curl -s $H -X POST $BASE/api/care-reminders -d "{
  \"replacementId\":$TR_ID,\"type\":\"TURNING\",\"content\":\"每2小时翻身防压疮\",
  \"remindAt\":\"2026-10-03T02:00:00Z\",\"createdBy\":\"NURSE-SMOKE\"}")
REM_TURN_ID=$(echo "$REM_TURN" | j "['reminderId']")
curl -s $H -X POST $BASE/api/care-reminders -d "{
  \"replacementId\":$TR_ID,\"type\":\"NIGHT_OBSERVATION\",\"content\":\"凌晨监测体温\",
  \"remindAt\":\"2026-10-03T03:00:00Z\",\"createdBy\":\"NURSE-SMOKE\"}" | python3 -m json.tool

echo "== 9. 新陪护到院 -> 空窗结束 =="
curl -s $H -X POST $BASE/api/temporary-replacements/$TR_ID/new-escort-arrive -d '{"operator":"NURSE-SMOKE"}' | python3 -m json.tool
echo "-- 空窗列表应为空 --"
curl -s $BASE/api/wards/W3A/unattended

echo "== 10. 现场身份复核通过, 完成交接(新证签发, 看护提醒转交家属) =="
curl -s $H -X POST $BASE/api/temporary-replacements/$TR_ID/complete -d '{
  "completedBy":"NURSE-SMOKE","identityVerified":true,"note":"人证一致,已告知照护注意事项"}' | python3 -m json.tool

echo "-- 翻身提醒已转交家属(保留交接时间), 家属完成 --"
curl -s $H -X POST $BASE/api/care-reminders/$REM_TURN_ID/complete -d '{"operator":"周母"}' | python3 -m json.tool

echo "== 11. 病区通知(含空窗/交接完成/当前照护人) =="
curl -s "$BASE/api/wards/W3A/notices?unreadOnly=true" | python3 -c "
import sys,json
for n in json.load(sys.stdin)[:12]: print(n['type'],'-',n['message'])"

echo "== 12. 看板(无人陪护数/待处理看护提醒) =="
curl -s "$BASE/api/wards/W3A/dashboard?days=7" | python3 -m json.tool

echo "== 13. 新陪护人退证 =="
NEW_PERMIT=$(curl -s $BASE/api/cases/$CASE_NO/archive | j "['permits'][-1]['permitNo']")
curl -s $H -X POST $BASE/api/permits/$NEW_PERMIT/return -d '{"returnedBy":"周母","channel":"WARD_DESK","note":"患者出院"}' | python3 -m json.tool

echo "== 14. 完整归档事件流 =="
curl -s $BASE/api/cases/$CASE_NO/archive | python3 -c "
import sys,json
a=json.load(sys.stdin)
print('caseNo:',a['caseNo'],'status:',a['status'],'currentEscort:',a['currentEscortName'])
print('applications:',len(a['applications']),'permits:',len(a['permits']),
      'scans:',len(a['scans']),'replacements:',len(a['replacements']),
      'patrols:',len(a['patrols']),'careReminders:',len(a['careReminders']),
      'violations:',len(a['violations']))
r=a['replacements'][0]
print('replacement mode/status:',r['mode'],r['status'],
      '| 离院:',r['oldEscortLeftAt'],'| 到院:',r['newEscortArrivedAt'],'| 交接完成:',r['handoverCompletedAt'])
for cr in a['careReminders']: print(' reminder',cr['type'],cr['status'],'takenOverBy=',cr['takenOverBy'])
for e in a['events']: print(' -',e['eventType'],'|',e['summary'])"

echo "== 15. 关闭记录 =="
curl -s $H -X POST $BASE/api/cases/$CASE_NO/close -d '{"operator":"NURSE-SMOKE"}' | python3 -m json.tool
echo "SMOKE OK: CASE_NO=$CASE_NO"
