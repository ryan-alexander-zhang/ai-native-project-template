package com.aipersimmon.ddd.web.spring;

import com.aipersimmon.ddd.web.error.ProblemType;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

/**
 * Resolves a {@link ProblemType}'s {@code titleKey} to a human-readable title
 * through the application {@link MessageSource}, using the request locale. Falls
 * back to the key itself when no message source is present or the key is not
 * defined, so a title is always produced.
 */
public class ProblemTitleResolver {

    private final MessageSource messageSource;

    public ProblemTitleResolver(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    public String resolve(ProblemType problemType) {
        String key = problemType.titleKey();
        if (messageSource == null) {
            return key;
        }
        return messageSource.getMessage(key, null, key, LocaleContextHolder.getLocale());
    }
}
