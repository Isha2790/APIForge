package dev.apiforge.management;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Sends plain-text email over SMTP (Spring creates the sender when {@code spring.mail.host} is set, e.g.
 * SPRING_MAIL_HOST=mailpit in Compose). Delivery runs on a small background pool so a slow mail server never
 * delays a request, and so "unknown email" and "known email" answer in about the same time. Failures are logged
 * and never shown to the caller. Without an SMTP host nothing is sent; only the recipient and subject are logged
 * (never the body, which can contain a reset link).
 */
@Component
public class Mailer {
    private static final Logger log = LoggerFactory.getLogger(Mailer.class);

    private final ObjectProvider<JavaMailSender> sender;
    private final String from;
    private final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "mailer");
        thread.setDaemon(true);
        return thread;
    });

    public Mailer(ObjectProvider<JavaMailSender> sender, @Value("${app.mail.from}") String from) {
        this.sender = sender;
        this.from = from;
    }

    public void send(String to, String subject, String body) {
        JavaMailSender mail = sender.getIfAvailable();
        if (mail == null) {
            log.warn("No SMTP server configured (set SPRING_MAIL_HOST); email to {} not sent: {}", to, subject);
            return;
        }
        executor.execute(() -> deliver(mail, to, subject, body));
    }

    private void deliver(JavaMailSender mail, String to, String subject, String body) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);
            mail.send(message);
        } catch (RuntimeException e) {
            log.error("could not send email to {}: {}", to, e.toString());
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
