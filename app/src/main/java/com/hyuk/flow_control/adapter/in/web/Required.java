package com.hyuk.flow_control.adapter.in.web;

final class Required {
    private Required() {
    }

    static <T> T of(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " 는 필수입니다");
        }
        return value;
    }
}
