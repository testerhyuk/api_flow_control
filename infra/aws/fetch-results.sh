#!/usr/bin/env bash
#
# 부하·관측 서버의 실험 결과와 Prometheus 데이터를 내 PC 로 내려받는다. terraform destroy 전에 실행한다.
# Prometheus 는 복사하는 동안 잠깐 멈추므로 실험이 끝난 뒤에 실행한다.
#
# 사용법: infra/aws/fetch-results.sh <부하·관측 서버 공인 IP> <SSH 개인 키 경로>

set -euo pipefail

[ $# -eq 2 ] || { printf '사용법: fetch-results.sh <공인 IP> <SSH 개인 키 경로>\n' >&2; exit 1; }
host=ubuntu@$1
key=$2

script_dir=$(cd "$(dirname "$0")" && pwd)
target_dir="$script_dir/../experiment/results/aws-$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$target_dir"

ssh_options=(-i "$key" -o StrictHostKeyChecking=accept-new)

# shellcheck disable=SC2029
ssh "${ssh_options[@]}" "$host" 'set -e
trap "sudo docker start flow-control-prometheus > /dev/null" EXIT
sudo docker stop flow-control-prometheus > /dev/null
sudo tar -czf /tmp/prometheus-data.tgz -C /var/lib/docker/volumes/flow-control_prometheus-data/_data .
sudo chown ubuntu /tmp/prometheus-data.tgz
tar -czf /tmp/experiment-results.tgz -C /opt/flow-control results'

scp "${ssh_options[@]}" "$host:/tmp/prometheus-data.tgz" "$host:/tmp/experiment-results.tgz" "$target_dir/"

printf '내려받았습니다 : %s\n' "$target_dir"
