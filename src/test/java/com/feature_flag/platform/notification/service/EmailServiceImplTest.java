package com.feature_flag.platform.notification.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.feature_flag.platform.common.config.AppProperties;
import com.feature_flag.platform.notification.EmailNotificationAdaptor;
import jakarta.mail.MessagingException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

/**
 * The send used to catch {@link MessagingException} only — the <em>checked</em> exception the
 * {@code MimeMessageHelper} setters declare. The actual transmission,
 * {@code JavaMailSender.send(MimeMessage)}, fails with the <em>unchecked</em>
 * {@code MailException} hierarchy, none of which is a {@code MessagingException}. So with the SMTP host
 * down the failure sailed past the catch, died on the async thread, and was dropped: the API had already
 * answered 200 and the invitee never heard anything.
 */
@ExtendWith(MockitoExtension.class)
class EmailServiceImplTest {

    private static final String INVITEE = "invitee@corp.com";
    private static final String TENANT = "Acme";
    private static final String RAW_TOKEN = "raw-invite-token";

    @Mock private EmailNotificationAdaptor emailNotificationAdaptor;

    private EmailServiceImpl emailService;
    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        AppProperties appProperties = new AppProperties();
        appProperties.setClientBaseUrl("https://app.example.test");
        emailService = new EmailServiceImpl(appProperties, emailNotificationAdaptor);

        logger = (Logger) LoggerFactory.getLogger(EmailServiceImpl.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    // ------------------------------------------------------------------ the unchecked hierarchy

    @Test
    void mailSendException_isCaughtAndLogged_andDoesNotBreakTheCaller() throws Exception {
        doThrow(new MailSendException("Mail server connection failed"))
                .when(emailNotificationAdaptor).sendEmail(anyString(), anyString(), anyString(), anyBoolean());

        assertThatCode(() -> emailService.sendInvitationEmail(INVITEE, TENANT, RAW_TOKEN))
                .as("a fire-and-forget send must never surface in the caller's request")
                .doesNotThrowAnyException();

        assertErrorLoggedNaming(INVITEE, TENANT);
    }

    @Test
    void mailAuthenticationException_isCaughtAndLogged() throws Exception {
        doThrow(new MailAuthenticationException("535 authentication failed"))
                .when(emailNotificationAdaptor).sendEmail(anyString(), anyString(), anyString(), anyBoolean());

        assertThatCode(() -> emailService.sendInvitationEmail(INVITEE, TENANT, RAW_TOKEN))
                .doesNotThrowAnyException();

        assertErrorLoggedNaming(INVITEE, TENANT);
    }

    @Test
    void mailPreparationException_isCaughtAndLogged() throws Exception {
        doThrow(new MailPreparationException("could not prepare message"))
                .when(emailNotificationAdaptor).sendEmail(anyString(), anyString(), anyString(), anyBoolean());

        assertThatCode(() -> emailService.sendInvitationEmail(INVITEE, TENANT, RAW_TOKEN))
                .doesNotThrowAnyException();

        assertErrorLoggedNaming(INVITEE, TENANT);
    }

    @Test
    void mailParseException_isCaughtAndLogged() throws Exception {
        doThrow(new MailParseException("could not parse address"))
                .when(emailNotificationAdaptor).sendEmail(anyString(), anyString(), anyString(), anyBoolean());

        assertThatCode(() -> emailService.sendInvitationEmail(INVITEE, TENANT, RAW_TOKEN))
                .doesNotThrowAnyException();

        assertErrorLoggedNaming(INVITEE, TENANT);
    }

    /** None of the above is a {@code MessagingException} — which is exactly why the old catch missed them. */
    @Test
    void theUncheckedMailExceptions_areNotMessagingExceptions() {
        assertThat(new MailSendException("x")).isNotInstanceOf(MessagingException.class);
        assertThat(new MailAuthenticationException("x")).isNotInstanceOf(MessagingException.class);
        assertThat(new MailPreparationException("x")).isNotInstanceOf(MessagingException.class);
    }

    // ------------------------------------------------------------------ the checked one still works

    @Test
    void messagingException_isStillCaughtAndLogged() throws Exception {
        doThrow(new MessagingException("invalid recipient address"))
                .when(emailNotificationAdaptor).sendEmail(anyString(), anyString(), anyString(), anyBoolean());

        assertThatCode(() -> emailService.sendInvitationEmail(INVITEE, TENANT, RAW_TOKEN))
                .doesNotThrowAnyException();

        assertErrorLoggedNaming(INVITEE, TENANT);
    }

    // ------------------------------------------------------------------ the happy path is unchanged

    @Test
    void successfulSend_logsAtInfoAndReportsNoError() {
        emailService.sendInvitationEmail(INVITEE, TENANT, RAW_TOKEN);

        assertThat(appender.list).noneMatch(event -> event.getLevel() == Level.ERROR);
        assertThat(appender.list)
                .anyMatch(event -> event.getLevel() == Level.INFO
                        && event.getFormattedMessage().contains(INVITEE));
    }

    /**
     * Anything outside the mail hierarchy is a genuine defect; it is deliberately left to propagate to the
     * {@code AsyncUncaughtExceptionHandler}, which logs the method and its arguments. Swallowing it here
     * would hide the bug — the failure mode this whole change exists to remove.
     */
    @Test
    void aNonMailRuntimeException_isNotSwallowed() throws Exception {
        doThrow(new IllegalArgumentException("programming error"))
                .when(emailNotificationAdaptor).sendEmail(anyString(), anyString(), anyString(), anyBoolean());

        assertThatThrownBy(() -> emailService.sendInvitationEmail(INVITEE, TENANT, RAW_TOKEN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void assertErrorLoggedNaming(String email, String tenant) {
        List<ILoggingEvent> errors = appender.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .toList();

        assertThat(errors)
                .as("a dropped invitation mail must leave an ERROR behind — it is the only trace there is")
                .hasSize(1);
        assertThat(errors.get(0).getFormattedMessage()).contains(email).contains(tenant);
        assertThat(errors.get(0).getThrowableProxy())
                .as("the cause, not just a one-liner")
                .isNotNull();
    }
}
