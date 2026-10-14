package com.hyuk.flow_control.domain.policy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChangeNoteTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    @DisplayName("바꾼 사람이 없으면 만들 수 없다")
    void requiresChangedBy(String changedBy) {
        assertThatThrownBy(() -> new ChangeNote(changedBy, "이유"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    @DisplayName("변경 사유가 없으면 만들 수 없다")
    void requiresReason(String reason) {
        assertThatThrownBy(() -> new ChangeNote("hyuk", reason))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
