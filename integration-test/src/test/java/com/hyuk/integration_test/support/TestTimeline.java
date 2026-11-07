package com.hyuk.integration_test.support;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** 테스트에서 일어난 일의 시각을 모아, 끝난 뒤 Grafana 조회 구간과 대조할 수 있게 출력한다. */
public final class TestTimeline {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final List<String> LINES = new ArrayList<>();

    private final String section;
    private final List<String> events = new ArrayList<>();

    public TestTimeline(String section) {
        this.section = section;
    }

    public void add(Instant at, String event) {
        events.add(TIME.format(at) + " " + event);
    }

    /** 테스트가 실패해도 그때까지의 시각이 남도록 finally 에서 부른다. */
    public void finish() {
        synchronized (LINES) {
            LINES.add("[" + section + "] " + String.join(" → ", events));
        }
    }

    public static void printAll() {
        synchronized (LINES) {
            System.out.println("테스트 시각 (Grafana 조회 구간과 대조) :");
            LINES.forEach(System.out::println);
        }
    }
}
