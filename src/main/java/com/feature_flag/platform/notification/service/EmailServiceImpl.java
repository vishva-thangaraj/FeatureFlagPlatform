package com.feature_flag.platform.notification.service;

import com.feature_flag.platform.common.config.AppProperties;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class EmailServiceImpl implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailServiceImpl.class);

    private final JavaMailSender mailSender;
    private final AppProperties appProperties;

    public EmailServiceImpl(JavaMailSender mailSender, AppProperties appProperties) {
        this.mailSender = mailSender;
        this.appProperties = appProperties;
    }

    @Override
    @Async
    public void sendInvitationEmail(String toEmail, String tenantName, String rawToken) {
        try {
            String acceptUrl = appProperties.getClientBaseUrl()
                    + "/invitations/accept?token=" + rawToken;

            String html = """
                    <html>
                    <body>
                        <h2>You've been invited!</h2>
                        <p>You have been invited to join <strong>%s</strong>.</p>
                        <p>Click the link below to accept the invitation:</p>
                        <p><a href="%s">Accept Invitation</a></p>
                        <p>If you did not expect this email, you can safely ignore it.</p>
                    </body>
                    </html>
                    """.formatted(tenantName, acceptUrl);

            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true);
            helper.setFrom(appProperties.getMail().getFrom());
            helper.setTo(toEmail);
            helper.setSubject("Invitation to join " + tenantName);
            helper.setText(html, true);

            mailSender.send(message);
            log.info("Invitation email sent to {}", toEmail);
        } catch (MessagingException e) {
            log.error("Failed to send invitation email to {}", toEmail, e);
        }
    }
}
