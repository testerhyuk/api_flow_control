package com.hyuk.flow_control.domain.requester;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;

public record Requester(
        String requesterId,
        long version,
        boolean enabled,
        List<String> targetSystemIds
) {
    private static final Pattern REQUESTER_ID = Pattern.compile("[a-zA-Z0-9-]{1,100}");

    public Requester {
        if (requesterId == null || !REQUESTER_ID.matcher(requesterId).matches()) {
            throw new IllegalArgumentException("요청자 ID 는 영문, 숫자, '-' 로 100자 이하여야 합니다 : " + requesterId);
        }
        if (version < 1) {
            throw new IllegalArgumentException("version 은 1 이상이어야 합니다");
        }
        if (targetSystemIds == null || targetSystemIds.isEmpty()) {
            throw new IllegalArgumentException("부를 수 있는 외부 서버가 하나 이상 있어야 합니다 : " + requesterId);
        }
        if (targetSystemIds.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw new IllegalArgumentException("외부 서버 ID 가 비어 있습니다 : " + requesterId);
        }
        targetSystemIds = List.copyOf(new TreeSet<>(targetSystemIds));
    }

    public boolean canCall(String targetSystemId) {
        return targetSystemIds.contains(targetSystemId);
    }

    public Requester withVersion(long version) {
        return new Requester(requesterId, version, enabled, targetSystemIds);
    }

    public Requester withEnabled(boolean enabled) {
        return new Requester(requesterId, version, enabled, targetSystemIds);
    }

    public Requester withTarget(String targetSystemId) {
        if (canCall(targetSystemId)) {
            throw new IllegalArgumentException("이미 부를 수 있는 외부 서버입니다 : " + requesterId + " " + targetSystemId);
        }

        List<String> targets = new ArrayList<>(targetSystemIds);
        targets.add(targetSystemId);
        return new Requester(requesterId, version, enabled, targets);
    }

    public Requester withoutTarget(String targetSystemId) {
        if (!canCall(targetSystemId)) {
            throw new IllegalArgumentException("부를 수 없는 외부 서버입니다 : " + requesterId + " " + targetSystemId);
        }
        if (targetSystemIds.size() == 1) {
            throw new IllegalArgumentException(
                    "마지막 외부 서버는 뺄 수 없습니다. 요청자를 끄는 API 를 써 주세요 : " + requesterId);
        }

        return new Requester(requesterId, version, enabled,
                targetSystemIds.stream().filter(id -> !id.equals(targetSystemId)).toList());
    }
}
