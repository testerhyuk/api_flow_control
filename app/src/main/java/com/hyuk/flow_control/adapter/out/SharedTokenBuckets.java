package com.hyuk.flow_control.adapter.out;

import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;

import java.util.concurrent.TimeoutException;

/**
 * 서버끼리 함께 쓰는 토큰 버킷. ratelimit 이 쓰고 redis 가 구현한다.
 * 두 패키지가 서로를 모르도록 어느 쪽에도 속하지 않는 이곳에 둔다.
 */
public interface SharedTokenBuckets {
    /** 지금 공용 버킷을 쓸 수 있는지. 쓸 수 없으면 서버 안 버킷을 쓴다. */
    boolean isAvailable();

    /** 처음 버킷을 만들 때 쓸 설정을 기억해 둔다. 저장소에는 쓰지 않는다. */
    void configure(String targetSystemId, BucketConfiguration configuration);

    ConsumptionProbe tryConsume(String targetSystemId) throws TimeoutException;

    void replaceConfiguration(String targetSystemId, BucketConfiguration configuration) throws TimeoutException;

    /** 호출이 실패해서 서버 안 버킷으로 넘긴다. 다시 쓸 수 있는지는 구현이 알아서 확인한다. */
    void markUnavailable(Throwable cause);

    /** 실패가 응답을 기다리다 시간이 다 된 것인지. */
    boolean isTimeout(Throwable error);
}
