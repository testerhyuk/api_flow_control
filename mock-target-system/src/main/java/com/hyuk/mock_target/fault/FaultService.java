package com.hyuk.mock_target.fault;

import com.hyuk.mock_target.simulation.WorkSlots;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class FaultService {
    private static final Logger log = LoggerFactory.getLogger(FaultService.class);

    private final Faults faults;
    private final WorkSlots slots;
    private final CallPort callPort;
    private final int normalCapacity;

    public FaultService(Faults faults, WorkSlots slots, CallPort callPort, int normalCapacity) {
        this.faults = faults;
        this.slots = slots;
        this.callPort = callPort;
        this.normalCapacity = normalCapacity;
    }

    public synchronized Map<String, Object> apply(FaultType type, Double value) {
        boolean wasDown = faults.isActive(FaultType.DOWN);
        faults.apply(type, value);

        if (type == FaultType.DOWN && !wasDown) {
            callPort.close();
        }
        if (type == FaultType.CAPACITY) {
            slots.changeCapacity(faults.capacity());
        }

        log.info("장애를 걸었습니다 : {} {}", type.id(), value);
        return faults.describe();
    }

    public synchronized Map<String, Object> clear(FaultType type) {
        boolean wasDown = faults.isActive(FaultType.DOWN);
        faults.clear(type);

        if (type == FaultType.DOWN && wasDown) {
            callPort.open();
        }
        if (type == FaultType.CAPACITY) {
            slots.changeCapacity(normalCapacity);
        }

        log.info("장애를 풀었습니다 : {}", type.id());
        return faults.describe();
    }

    public synchronized Map<String, Object> clearAll() {
        for (FaultType type : FaultType.values()) {
            if (faults.isActive(type)) {
                clear(type);
            }
        }
        return faults.describe();
    }

    public Map<String, Object> describe() {
        return faults.describe();
    }
}
