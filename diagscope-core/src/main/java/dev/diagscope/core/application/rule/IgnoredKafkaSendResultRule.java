package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class IgnoredKafkaSendResultRule implements DiagnosticRule {
    public static final String ID = "KAFKA_SEND_RESULT_IGNORED";

    @Override public String id() { return ID; }

    @Override
    public List<Finding> evaluate(Flow flow) {
        var findings = new ArrayList<Finding>();
        for (var flowMethod : flow.methods()) {
            var method = flowMethod.method();
            for (var invocation : method.invocations()) {
                if (!"send".equals(invocation.methodName()) || !invocation.resultIgnored()) continue;
                if (!invocation.receiverType().toLowerCase(java.util.Locale.ROOT).contains("kafkatemplate")) continue;
                var confidence = Confidence.min(Confidence.HIGH, flowMethod.confidence());
                findings.add(new Finding(
                        ID, Severity.WARNING, confidence, invocation.location(),
                        "KafkaTemplate.send() result is ignored by this flow.",
                        "Verify whether the flow requires broker acknowledgement or explicit asynchronous failure handling.",
                        List.of(RelatedFlow.from(flow.entrypoint(), confidence)),
                        Map.of("scope", invocation.scope(), "receiverType", invocation.receiverType(),
                                "resultUsage", invocation.resultUsage().name(), "method", method.id().displayName())
                ));
            }
        }
        return List.copyOf(findings);
    }
}
