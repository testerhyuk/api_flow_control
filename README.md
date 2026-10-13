# flow-control

## DLQ 메시지 다시 넣기

DLQ 메시지의 값은 원래 요청 JSON 그대로라서, 원래 요청 토픽에 그대로 넣으면 처음부터 다시 처리된다.
실패 정보(이유, 시도 횟수, 마지막 kind, 처음 들어온 위치)는 Kafka 헤더에 있으므로 kafka-ui(http://localhost:8090)에서 먼저 확인한다.

target-a 의 DLQ 를 요청 토픽으로 옮기는 명령 (CMD):

```
docker exec flow-control-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic flow-control.dlq.target-a --group dlq-redrive --consumer-property auto.offset.reset=earliest --timeout-ms 10000 | docker exec -i flow-control-kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic flow-control.requests.target-a
```

- `dlq-redrive` 그룹이 어디까지 옮겼는지 기억하므로, 다시 실행하면 그 뒤에 새로 쌓인 메시지만 옮긴다.
- 10초 동안 새 메시지가 없으면 끝난다.
- 잘못된 메시지(invalid_message)도 같이 옮겨지며, 다시 DLQ 로 돌아온다.
- 다른 대상은 토픽 이름의 `target-a` 를 바꿔서 실행한다.
