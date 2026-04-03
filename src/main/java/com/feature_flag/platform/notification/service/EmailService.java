package com.feature_flag.platform.notification.service;

public interface EmailService {

    void sendInvitationEmail(String toEmail, String tenantName, String rawToken);
}
