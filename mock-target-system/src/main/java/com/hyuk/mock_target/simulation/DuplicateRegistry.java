package com.hyuk.mock_target.simulation;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.LongSupplier;

public class DuplicateRegistry {
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final long retentionMillis;
    private final LongSupplier currentMillis;

    public DuplicateRegistry(Duration retention, LongSupplier currentMillis) {
        if (retention == null || !retention.isPositive()) {
            throw new IllegalArgumentException("기억 기간은 0보다 커야 합니다");
        }
        this.retentionMillis = retention.toMillis();
        this.currentMillis = currentMillis;
    }

    public Claim claim(String requestId) {
        while (true) {
            Entry fresh = new Entry();
            Entry existing = entries.putIfAbsent(requestId, fresh);

            if (existing == null) {
                return new Claim(requestId, fresh, true);
            }
            if (!existing.isExpired(currentMillis.getAsLong(), retentionMillis)) {
                return new Claim(requestId, existing, false);
            }

            entries.remove(requestId, existing);
        }
    }

    public void complete(Claim claim, CallResult result) {
        if (!claim.owner()) {
            throw new IllegalStateException("처음 받은 요청만 결과를 남길 수 있습니다");
        }

        Entry entry = claim.entry();

        if (result == null) {
            entries.remove(claim.requestId(), entry);
            entry.result.completeExceptionally(new IllegalStateException("처음 받은 요청이 처리 중 중단되었습니다"));
            return;
        }

        if (isRemembered(result.outcome())) {
            entry.completedAtMillis = currentMillis.getAsLong();
        } else {
            entries.remove(claim.requestId(), entry);
        }

        entry.result.complete(result);
    }

    public int sweep() {
        long now = currentMillis.getAsLong();
        int before = entries.size();
        entries.values().removeIf(entry -> entry.isExpired(now, retentionMillis));
        return before - entries.size();
    }

    public int size() {
        return entries.size();
    }

    static boolean isRemembered(Outcome outcome) {
        return outcome == Outcome.SUCCESS || outcome == Outcome.BUSINESS_REJECTED;
    }

    public record Claim(String requestId, Entry entry, boolean owner) {
        public CallResult awaitResult() throws InterruptedException {
            try {
                return entry.result.get();
            } catch (ExecutionException e) {
                throw new IllegalStateException(e.getCause().getMessage(), e.getCause());
            }
        }
    }

    public static final class Entry {
        private final CompletableFuture<CallResult> result = new CompletableFuture<>();
        private volatile long completedAtMillis = -1;

        private boolean isExpired(long now, long retentionMillis) {
            long completedAt = completedAtMillis;
            return completedAt >= 0 && now - completedAt >= retentionMillis;
        }
    }
}
