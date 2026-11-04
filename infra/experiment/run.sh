#!/usr/bin/env bash
#
# 실험 한 회차를 돌린다. record-ingestor 와 mock 3대의 시간표를 함께 시작하고, 끝날 때까지 기다린 뒤 결과를 저장한다.
#
# 사용법:
#   INGESTOR_URL=http://host:8095 \
#   TARGET_A_ADMIN_URL=http://host:9101 \
#   TARGET_B_ADMIN_URL=http://host:9102 \
#   TARGET_C_ADMIN_URL=http://host:9103 \
#   infra/experiment/run.sh <시나리오 폴더> <seed>
#
# 시나리오 폴더:
#   ingestor.json   record-ingestor 시작 요청. seed 자리에 __SEED__ 를 적는다. 필수.
#   target-a.json   mock 장애 시간표. target-b.json, target-c.json 도 같다. 없으면 그 mock 은 장애 없이 돌린다.
#
# 결과는 RESULTS_DIR (기본 infra/experiment/results) 아래 회차별 폴더에 저장한다.
# 종료 코드: 0 정상, 1 시작 실패나 중단, 2 끝났지만 전송 실패나 장애 실행 실패가 있어 다시 돌려야 하는 회차.

set -uo pipefail

TARGETS=(target-a target-b target-c)
POLL_SECONDS=2
MAX_POLL_FAILURES=30

fail() {
    printf '실패: %s\n' "$1" >&2
    exit 1
}

for name in INGESTOR_URL TARGET_A_ADMIN_URL TARGET_B_ADMIN_URL TARGET_C_ADMIN_URL; do
    [ -n "${!name:-}" ] || fail "환경변수 $name 이 없습니다"
done

[ $# -eq 2 ] || fail "사용법: run.sh <시나리오 폴더> <seed>"
scenario_dir=${1%/}
seed=$2

[[ "$seed" =~ ^[0-9]+$ ]] || fail "seed 는 0 이상의 정수여야 합니다 : $seed"
[ -f "$scenario_dir/ingestor.json" ] || fail "$scenario_dir/ingestor.json 이 없습니다"
grep -q '__SEED__' "$scenario_dir/ingestor.json" || fail "ingestor.json 의 seed 자리에 __SEED__ 를 적어야 합니다"

admin_url() {
    case "$1" in
        target-a) printf '%s' "$TARGET_A_ADMIN_URL" ;;
        target-b) printf '%s' "$TARGET_B_ADMIN_URL" ;;
        target-c) printf '%s' "$TARGET_C_ADMIN_URL" ;;
    esac
}

# 결과: status 에 HTTP 상태 코드, body 에 본문. 연결 실패면 status 는 000 이다.
request() {
    local method=$1 url=$2 max_time=$3 response
    shift 3
    response=$(curl -sS --max-time "$max_time" -X "$method" -H 'Content-Type: application/json' \
        -w '\n%{http_code}' "$@" "$url" 2>&1) || true
    status=${response##*$'\n'}
    body=${response%$'\n'*}
    [[ "$status" =~ ^[0-9]{3}$ ]] || status=000
}

cancel_all() {
    trap - INT TERM
    printf '이미 시작한 것을 모두 취소합니다\n' >&2
    request POST "$INGESTOR_URL/generator/stop" 180
    for target in "${TARGETS[@]}"; do
        request DELETE "$(admin_url "$target")/actuator/fault-timeline" 30
    done
}

abort() {
    printf '실패: %s\n' "$1" >&2
    cancel_all
    exit 1
}

request GET "$INGESTOR_URL/generator" 10
[ "$status" = 200 ] || fail "record-ingestor 에 연결하지 못했습니다 ($INGESTOR_URL) : $status $body"
for target in "${TARGETS[@]}"; do
    request GET "$(admin_url "$target")/actuator/fault-timeline" 10
    [ "$status" = 200 ] || fail "$target 에 연결하지 못했습니다 ($(admin_url "$target")) : $status $body"
done

script_dir=$(cd "$(dirname "$0")" && pwd)
run_name="$(basename "$scenario_dir")-seed$seed-$(date -u +%Y%m%dT%H%M%SZ)"
result_dir="${RESULTS_DIR:-$script_dir/results}/$run_name"
mkdir -p "$result_dir" || fail "결과 폴더를 만들지 못했습니다 : $result_dir"
cp "$scenario_dir"/*.json "$result_dir"/ || fail "시나리오를 결과 폴더로 복사하지 못했습니다"

# record-ingestor 를 먼저 시작한다. 토픽이 없으면 여기서 거부되어 장애가 걸리지 않는다.
request POST "$INGESTOR_URL/generator/start" 180 --data-binary @- \
    < <(sed "s/__SEED__/$seed/g" "$scenario_dir/ingestor.json")
[ "$status" = 200 ] || fail "record-ingestor 가 시작을 거부했습니다 : $status $body"
started_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
trap 'abort "사용자가 중단했습니다"' INT TERM

for target in "${TARGETS[@]}"; do
    url="$(admin_url "$target")/actuator/fault-timeline"
    if [ -f "$scenario_dir/$target.json" ]; then
        request POST "$url" 30 --data-binary @- < "$scenario_dir/$target.json"
    else
        # 시간표가 없는 mock 도 남아 있는 장애를 풀어 회차마다 같은 상태로 시작한다.
        request DELETE "$url" 30
    fi
    [ "$status" = 200 ] || abort "$target 시간표를 시작하지 못했습니다 : $status $body"
done

printf '시작했습니다 : %s (결과 %s)\n' "$started_at" "$result_dir"

poll_failures=0
while :; do
    request GET "$INGESTOR_URL/generator" 10
    if [ "$status" != 200 ]; then
        poll_failures=$((poll_failures + 1))
        [ "$poll_failures" -lt "$MAX_POLL_FAILURES" ] || abort "record-ingestor 상태를 읽지 못했습니다 : $status $body"
    else
        poll_failures=0
        case "$body" in
            *'"state":"STOPPED"'*) break ;;
        esac
    fi
    sleep "$POLL_SECONDS"
done
trap - INT TERM

printf '%s\n' "$body" > "$result_dir/ingestor-result.json"
rerun=0
if printf '%s' "$body" | grep -q '"failed":[1-9]'; then
    printf '경고: record-ingestor 전송 실패가 있습니다\n' >&2
    rerun=1
fi

for target in "${TARGETS[@]}"; do
    url="$(admin_url "$target")/actuator/fault-timeline"
    request GET "$url" 10
    printf '%s\n' "$body" > "$result_dir/$target-result.json"
    if [ -f "$scenario_dir/$target.json" ]; then
        case "$body" in
            *'"state":"FINISHED"'*) ;;
            *)
                printf '경고: %s 시간표가 실험보다 길어 끝나지 않았습니다 : %s\n' "$target" "$body" >&2
                rerun=1
                ;;
        esac
    fi
    if printf '%s' "$body" | grep -q '"failedSteps":[1-9]'; then
        printf '경고: %s 장애 실행 실패가 있습니다\n' "$target" >&2
        rerun=1
    fi
    # 다음 회차로 장애가 넘어가지 않게 정리한다.
    request DELETE "$url" 30
done

{
    printf 'scenario=%s\n' "$(basename "$scenario_dir")"
    printf 'seed=%s\n' "$seed"
    printf 'startedAt=%s\n' "$started_at"
    printf 'finishedAt=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
} > "$result_dir/run.txt"

printf '끝났습니다. 결과 : %s\n' "$result_dir"
if [ "$rerun" -ne 0 ]; then
    printf '이 회차는 위 경고를 확인한 뒤 다시 돌려야 합니다\n' >&2
    exit 2
fi
