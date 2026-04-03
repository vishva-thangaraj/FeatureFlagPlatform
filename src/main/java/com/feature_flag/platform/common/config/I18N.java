package com.feature_flag.platform.common.config;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

@Component
public class I18N {

    private static MessageSource messageSource;

    public I18N(MessageSource messageSource) {
        I18N.messageSource = messageSource;
    }

    public static String getMessage(String key, Object... args) {
        return messageSource.getMessage(key, args, LocaleContextHolder.getLocale());
    }
}