package com.hyuk.flow_control.domain.dryrun;

import com.hyuk.flow_control.domain.policy.ChangeNote;
import com.hyuk.flow_control.support.Policies;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DryRunTest {
    private static final Instant START = Instant.parse("2026-10-14T00:00:00Z");
    private static final ChangeNote NOTE = new ChangeNote("hyuk", "baseline 조정");

    @Test
    @DisplayName("새 정책의 버전은 기준 버전 다음이어야 한다")
    void candidateVersionFollowsBase() {
        assertThatThrownBy(() -> new DryRun(Policies.policy("target-a", 3), 3, START, START.plusSeconds(60), NOTE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("끝 시각은 시작 시각보다 뒤여야 한다")
    void expiresAfterStart() {
        assertThatThrownBy(() -> new DryRun(Policies.policy("target-a", 2), 1, START, START, NOTE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("끝 시각이 되면 끝난 것으로 본다")
    void expiresAtEnd() {
        DryRun dryRun = new DryRun(Policies.policy("target-a", 2), 1, START, START.plus(Duration.ofMinutes(30)), NOTE);

        assertThat(dryRun.isExpiredAt(START.plus(Duration.ofMinutes(29)))).isFalse();
        assertThat(dryRun.isExpiredAt(START.plus(Duration.ofMinutes(30)))).isTrue();
        assertThat(dryRun.targetSystemId()).isEqualTo("target-a");
    }
}
