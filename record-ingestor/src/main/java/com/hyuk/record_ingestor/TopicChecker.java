package com.hyuk.record_ingestor;

import java.util.Collection;

public interface TopicChecker {
    void requireExisting(Collection<String> topics);
}
