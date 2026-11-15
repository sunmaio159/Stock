package com.aquila.governance;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * One observed version of a licensed source document. {@code sourceRevisionOrder} is
 * preferred when the source supplies a reliable sequence; when it is null the service
 * allocates the next local observation order under the document row lock.
 */
public record ObservedDocument(
        String sourceCode,
        String outerId,
        Long sourceRevisionOrder,
        String bodyHash,
        String rawRecordId,
        LocalDate fetchedDate,
        Instant publishedAt,
        Instant observedAt,
        String normalizerVersion,
        String parserVersion,
        List<Mention> mentions) {

    public record Mention(String securityCode, int mentionCount) {}

    public ObservedDocument {
        mentions = mentions == null ? List.of() : List.copyOf(mentions);
    }
}
