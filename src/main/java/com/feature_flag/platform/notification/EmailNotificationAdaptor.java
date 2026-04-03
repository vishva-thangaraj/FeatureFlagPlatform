package com.feature_flag.platform.notification;

import jakarta.mail.MessagingException;
import org.springframework.stereotype.Component;


public interface EmailNotificationAdaptor {
    public void sendEmail(String to,String message, String subject, Boolean isHTML) throws MessagingException;
}
