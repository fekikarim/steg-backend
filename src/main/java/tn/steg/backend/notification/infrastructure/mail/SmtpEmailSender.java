package tn.steg.backend.notification.infrastructure.mail;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import tn.steg.backend.notification.application.port.out.EmailSender;

/**
 * Production {@link EmailSender} over {@code JavaMailSender} (plain SMTP).
 *
 * <p>Deliberately no paid external notification SaaS: this is built-in email
 * against operator-configured SMTP (free/dev provider acceptable for MVP).
 * The service only routes EMAIL deliveries here when
 * {@code steg.notifications.mail.enabled=true}; the sender itself just sends.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SmtpEmailSender implements EmailSender {

    private final JavaMailSender mailSender;
    private final MailTemplateService templates;

    @Value("${steg.notifications.mail.from:no-reply@steg.tn}")
    private String from;

    @Override
    public void send(String to, String subject, String body) {
        try {
            var message = mailSender.createMimeMessage();
            // multipart/alternative: plain-text + branded HTML (E5 Brevo templates).
            var helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(body, templates.generic(subject, body).html());
            mailSender.send(message);
            log.debug("Notification email sent to {}", to);
        } catch (Exception ex) {
            log.error("Failed to send notification email to {}: {}", to, ex.getClass().getSimpleName());
            // Preserve the MailException contract callers (and tests) rely on.
            throw new org.springframework.mail.MailSendException("Email delivery failed", ex);
        }
    }

    /** Event-specific branded HTML mail (used by listeners for key workflow events). */
    public void sendTemplated(String to, MailTemplateService.Mail mail) {
        try {
            var message = mailSender.createMimeMessage();
            var helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(mail.subject());
            helper.setText(mail.html(), true);
            mailSender.send(message);
            log.debug("Templated notification email sent to {}", to);
        } catch (Exception ex) {
            log.error("Failed to send templated email to {}: {}", to, ex.getClass().getSimpleName());
            throw new org.springframework.mail.MailSendException("Email delivery failed", ex);
        }
    }
}
