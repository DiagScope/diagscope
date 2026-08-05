package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.EntrypointType;
import dev.diagscope.core.domain.Flow;
import dev.diagscope.core.domain.FlowMethod;

import java.util.Optional;

/** Shared lookup for the listener method that starts a Kafka flow. */
final class KafkaListenerFlows {
    private KafkaListenerFlows() {
    }

    static Optional<FlowMethod> listener(Flow flow) {
        if (flow.entrypoint().type() != EntrypointType.KAFKA_LISTENER) {
            return Optional.empty();
        }
        return flow.methods().stream()
                .filter(flowMethod -> flowMethod.method().id().equals(flow.entrypoint().method()))
                .min((left, right) -> Integer.compare(left.depth(), right.depth()));
    }
}
