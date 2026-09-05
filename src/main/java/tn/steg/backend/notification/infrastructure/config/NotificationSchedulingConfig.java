package tn.steg.backend.notification.infrastructure.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables scheduling for the delivery retry sweep. Kept in the notification
 * module so the application entrypoint stays untouched.
 */
@Configuration
@EnableScheduling
public class NotificationSchedulingConfig {
}
