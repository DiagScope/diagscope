package example.kafka;

/** Syntax-visible producer listener declared for the project's producers. */
public class LoggingProducerListener implements ProducerListener<String, String> {

    @Override
    public void onError(ProducerRecord<String, String> record, RecordMetadata metadata, Exception exception) {
        // Logged by the shared infrastructure listener.
    }
}
