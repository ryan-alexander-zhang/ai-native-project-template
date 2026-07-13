package com.aipersimmon.ddd.web.error;

/**
 * The transport definition of an RFC 9457 problem type: the {@code type} URI, the HTTP
 * {@code status}, and the message-source key for the {@code title}. It is a pure
 * <em>transport</em> value — it deliberately carries <strong>no</strong> error code, so
 * it is not an {@link com.aipersimmon.ddd.core.error.ErrorCode} and does not conflate
 * "which domain rule failed" with "how it renders over HTTP".
 *
 * <p>Resolution is two-tier (see {@link ProblemRegistry}): every {@code ErrorCode}
 * resolves to a descriptor through its {@link com.aipersimmon.ddd.core.error.ErrorCategory}
 * (the {@link DefaultProblemFamilies family default}), and a {@link ProblemCatalog} may
 * <em>override</em> individual codes that warrant their own problem type. Because a
 * descriptor is code-free it is reusable: many fine-grained codes may share one family
 * type, so refining domain codes does not expand the outward API contract. The wire
 * {@code code} extension always comes from the thrown {@code ErrorCode}, not from here.
 *
 * @param typeUri  URI reference identifying the problem type (an identifier, not a
 *                 required link), e.g. {@code "/problems/domain-rule-violation"}; blank →
 *                 {@code "about:blank"} at render time
 * @param status   default HTTP status for this problem type (100–599)
 * @param titleKey message-source key for the {@code title} — a short summary stable
 *                 across occurrences (the occurrence-specific text is the {@code detail})
 */
public record ProblemDescriptor(String typeUri, int status, String titleKey) {

    public ProblemDescriptor {
        if (status < 100 || status > 599) {
            throw new IllegalArgumentException("status must be a valid HTTP status (100-599), was " + status);
        }
        if (titleKey == null || titleKey.isBlank()) {
            throw new IllegalArgumentException("titleKey must not be blank");
        }
        typeUri = (typeUri == null || typeUri.isBlank()) ? "about:blank" : typeUri;
    }
}
