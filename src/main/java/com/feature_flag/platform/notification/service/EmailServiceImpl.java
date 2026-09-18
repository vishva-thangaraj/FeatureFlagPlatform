package com.feature_flag.platform.notification.service;

import com.feature_flag.platform.common.config.AppProperties;
import com.feature_flag.platform.notification.EmailNotificationAdaptor;
import jakarta.mail.MessagingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class EmailServiceImpl implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailServiceImpl.class);
    private final AppProperties appProperties;
    private final EmailNotificationAdaptor emailNotificationAdaptor;

    public EmailServiceImpl(AppProperties appProperties, EmailNotificationAdaptor emailNotificationAdaptor) {
        this.appProperties = appProperties;
        this.emailNotificationAdaptor = emailNotificationAdaptor;
    }

    /**
     * Fire-and-forget: the contract is unchanged, a failure here never breaks the caller's request. What
     * changes is that a failure is now <em>visible</em>.
     *
     * <p>Only {@link MessagingException} used to be caught. That is the checked exception the
     * {@code MimeMessageHelper} setters declare — but the actual transmission,
     * {@code JavaMailSender.send(MimeMessage)}, fails with the <em>unchecked</em>
     * {@link MailException} hierarchy ({@link MailSendException}, {@link MailAuthenticationException},
     * {@link MailPreparationException}, {@link MailParseException}), none of which is a
     * {@code MessagingException}. With the SMTP host down, the send blew past this catch, died on the async
     * thread, and was dropped — the API had already answered 200 and the invitee never heard anything.
     */
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
            String subject = "Invitation to join " + tenantName;

            emailNotificationAdaptor.sendEmail(toEmail, html, subject, true);
            log.info("Invitation email sent to {}", toEmail);
        } catch (MessagingException e) {
            // Composition failed: a bad from/to address, or an unencodable subject or body.
            log.error("Failed to compose invitation email for {} (tenant '{}') — the invitation exists but "
                    + "no mail was sent.", toEmail, tenantName, e);
        } catch (MailAuthenticationException e) {
            // Credentials or the SMTP auth mechanism are wrong: every invitation mail is failing, not just this one.
            log.error("SMTP authentication failed while sending the invitation email to {} (tenant '{}') — "
                    + "check the mail credentials; ALL outbound mail is currently failing.", toEmail, tenantName, e);
        } catch (MailSendException e) {
            // Transport failed: host down, connection refused, recipient rejected.
            int failed = e.getFailedMessages() == null ? 0 : e.getFailedMessages().size();
            log.error("Failed to send invitation email to {} (tenant '{}'); {} message(s) rejected by the "
                            + "mail server — the invitation exists but the invitee was not notified.",
                    toEmail, tenantName, failed, e);
        } catch (MailException e) {
            // The rest of the unchecked hierarchy (preparation, parse, and any future subtype).
            log.error("Mail delivery failed for the invitation email to {} (tenant '{}') — the invitation "
                    + "exists but the invitee was not notified.", toEmail, tenantName, e);
        }
        // Anything else is genuinely unexpected: let it reach the AsyncUncaughtExceptionHandler, which logs
        // the method and its arguments. Swallowing it here would hide a real defect.
    }
}
