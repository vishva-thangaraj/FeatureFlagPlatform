package com.feature_flag.platform.notification;

import com.feature_flag.platform.common.config.AppProperties;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

@Component
public class EmailNotificationAdaptorImpl implements EmailNotificationAdaptor{

    private final JavaMailSender mailSender;
    private final AppProperties appProperties;

    public EmailNotificationAdaptorImpl(JavaMailSender mailSender, AppProperties appProperties) {
        this.mailSender = mailSender;
        this.appProperties = appProperties;
    }

    @Override
    public void sendEmail(String to, String message, String subject, Boolean isHTML) throws MessagingException {
        MimeMessage mimeMessage = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true);
        helper.setFrom(appProperties.getMail().getFrom());
        helper.setTo(to);
        helper.setSubject(subject);
        helper.setText(message, Boolean.TRUE.equals(isHTML));

        mailSender.send(mimeMessage);
    }
}
