package tn.steg.backend.notification.infrastructure.mail;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
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

    @Value("${steg.notifications.mail.from:no-reply@steg.tn}")
    private String from;

    @Override
    public void send(String to, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        mailSender.send(message);
        log.debug("Notification email sent to {}", to);
    }
}
