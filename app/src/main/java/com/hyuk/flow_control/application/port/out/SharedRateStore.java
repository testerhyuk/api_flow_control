package com.hyuk.flow_control.application.port.out;

import java.util.List;
import java.util.Optional;

public interface SharedRateStore {
    List<Double> exchangeProposals(String targetSystemId, double proposedRate);

    Optional<Double> appliedRate(String targetSystemId);

    void saveAppliedRate(String targetSystemId, double rate);
}
