#!/usr/bin/env bash
#
# 시드 정책의 localhost 주소를 mock 서버의 사설 IP 로 바꾼다. 부하·관측 서버에서 실행한다.
# 이미 같은 주소면 건너뛰므로 다시 실행해도 된다.
#
# 사용법: source /opt/flow-control/experiment.env && infra/aws/register-policies.sh

set -euo pipefail

: "${APP_URL:?환경변수 APP_URL 이 없습니다}"
: "${MOCK_IP:?환경변수 MOCK_IP 가 없습니다}"
ADMIN_API_KEY=${ADMIN_API_KEY:-experiment-admin-key}
WAIT_SECONDS=300

deadline=$((SECONDS + WAIT_SECONDS))
until curl -fsS --max-time 5 -H "Authorization: Bearer $ADMIN_API_KEY" "$APP_URL/admin/policies" > /dev/null 2>&1; do
    if [ "$SECONDS" -ge "$deadline" ]; then
        printf '실패: %s 초 동안 app 에 연결하지 못했습니다 (%s)\n' "$WAIT_SECONDS" "$APP_URL" >&2
        exit 1
    fi
    sleep 5
done

register() {
    local target=$1 url=$2 policy current body

    policy=$(curl -fsS --max-time 10 -H "Authorization: Bearer $ADMIN_API_KEY" "$APP_URL/admin/policies/$target")
    current=$(printf '%s' "$policy" | jq -r '.policy.connection.url')
    if [ "$current" = "$url" ]; then
        printf '%s : 이미 %s 입니다\n' "$target" "$url"
        return
    fi

    body=$(printf '%s' "$policy" | jq --arg url "$url" \
        '{expectedVersion: .version, policy: (.policy | .connection.url = $url), reason: "AWS 실험 환경의 mock 주소로 변경"}')
    curl -fsS --max-time 10 -X PUT \
        -H "Authorization: Bearer $ADMIN_API_KEY" -H 'Content-Type: application/json' \
        --data-binary "$body" "$APP_URL/admin/policies/$target" > /dev/null
    printf '%s : %s 로 바꿨습니다\n' "$target" "$url"
}

register target-a "http://$MOCK_IP:9001"
register target-b "http://$MOCK_IP:9002"
register target-c "http://$MOCK_IP:9003"
