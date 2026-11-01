package com.hyuk.flow_control.domain.requester;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequesterTest {
    private static final Requester LOAN = new Requester("loan", 1, true, List.of("target-b", "target-a"));

    @Test
    @DisplayName("외부 서버 목록은 정렬하고 중복을 없앤다")
    void sortsAndDeduplicatesTargets() {
        Requester requester = new Requester("loan", 1, true, List.of("target-b", "target-a", "target-b"));

        assertThat(requester.targetSystemIds()).containsExactly("target-a", "target-b");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "loan.app", "loan_app", "대출", "loan app"})
    @DisplayName("요청자 ID 는 영문, 숫자, '-' 만 쓸 수 있다")
    void rejectsInvalidId(String requesterId) {
        assertThatThrownBy(() -> new Requester(requesterId, 1, true, List.of("target-a")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("요청자 ID 는 100자까지다")
    void limitsIdLength() {
        assertThat(new Requester("a".repeat(100), 1, true, List.of("target-a")).requesterId()).hasSize(100);
        assertThatThrownBy(() -> new Requester("a".repeat(101), 1, true, List.of("target-a")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("외부 서버가 없거나 빈 ID 가 있으면 만들 수 없다")
    void rejectsEmptyTargets() {
        assertThatThrownBy(() -> new Requester("loan", 1, true, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Requester("loan", 1, true, List.of("target-a", " ")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("버전은 1 이상이어야 한다")
    void rejectsVersionZero() {
        assertThatThrownBy(() -> new Requester("loan", 0, true, List.of("target-a")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("외부 서버를 더하고 뺄 수 있다")
    void addsAndRemovesTarget() {
        assertThat(LOAN.withTarget("target-c").targetSystemIds()).containsExactly("target-a", "target-b", "target-c");
        assertThat(LOAN.withoutTarget("target-a").targetSystemIds()).containsExactly("target-b");
        assertThat(LOAN.canCall("target-a")).isTrue();
        assertThat(LOAN.canCall("target-c")).isFalse();
    }

    @Test
    @DisplayName("이미 있는 외부 서버는 더할 수 없고, 없는 외부 서버는 뺄 수 없다")
    void rejectsDuplicateOrMissingTarget() {
        assertThatThrownBy(() -> LOAN.withTarget("target-a")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LOAN.withoutTarget("target-c")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("마지막 외부 서버는 뺄 수 없다")
    void rejectsRemovingLastTarget() {
        Requester single = new Requester("loan", 1, true, List.of("target-a"));

        assertThatThrownBy(() -> single.withoutTarget("target-a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("끄는 API");
    }
}
