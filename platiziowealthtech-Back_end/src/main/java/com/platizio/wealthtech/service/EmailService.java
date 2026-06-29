package com.platizio.wealthtech.service;

import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * Thin wrapper over {@link JavaMailSender} for transactional emails.
 *
 * <p>SMTP is optional: when {@code spring.mail.host} is blank the service is
 * considered <em>disabled</em> and simply returns {@code false} from send
 * methods instead of throwing. Callers (e.g. OtpService) use that signal to
 * fall back to logging the code — so the app runs out-of-the-box locally
 * without any SMTP credentials, and any free SMTP can be wired in via env.
 */
@Service
public class EmailService {

    private static final Logger logger = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender mailSender;
    private final String mailHost;
    private final String fromAddress;
    private final String fromName;
    private final boolean sendingEnabled;

    public EmailService(
            JavaMailSender mailSender,
            @Value("${spring.mail.host:}") String mailHost,
            @Value("${app.otp.from-address:no-reply@platizio.local}") String fromAddress,
            @Value("${app.otp.from-name:Platizio}") String fromName,
            @Value("${app.email.enabled:true}") boolean sendingEnabled
    ) {
        this.mailSender = mailSender;
        this.mailHost = mailHost == null ? "" : mailHost.trim();
        this.fromAddress = fromAddress;
        this.fromName = fromName;
        this.sendingEnabled = sendingEnabled;
    }

    /**
     * True when email sending is enabled ({@code app.email.enabled}) AND an SMTP host
     * is configured. In development {@code app.email.enabled} is false, so NO real
     * emails are sent — callers fall back to the dev OTP code / the always-returned
     * link URL. Set {@code APP_EMAIL_ENABLED=true} only for live testing.
     */
    public boolean isEnabled() {
        return sendingEnabled && !mailHost.isBlank();
    }

    /**
     * Sends an HTML email. Returns {@code true} if dispatched to the SMTP
     * server, {@code false} if email is disabled. Throws only on genuine SMTP
     * failures so the caller can surface a meaningful error.
     */
    public boolean sendHtml(String to, String subject, String htmlBody) {
        if (!isEnabled()) {
            logger.info("Email disabled (app.email.enabled=false or no spring.mail.host). Skipping send to {} subject='{}'", to, subject);
            return false;
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            try {
                helper.setFrom(fromAddress, fromName);
            } catch (UnsupportedEncodingException ex) {
                helper.setFrom(fromAddress);
            }
            mailSender.send(message);
            logger.info("Email sent to {} subject='{}'", to, subject);
            return true;
        } catch (MailException | jakarta.mail.MessagingException ex) {
            logger.error("Failed to send email to {} subject='{}': {}", to, subject, ex.getMessage());
            throw new IllegalStateException("Unable to send email right now. Please try again.", ex);
        }
    }
}
