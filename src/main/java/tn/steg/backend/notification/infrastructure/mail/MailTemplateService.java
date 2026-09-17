package tn.steg.backend.notification.infrastructure.mail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Professional HTML e-mail templates (fr) for workflow events (E5).
 *
 * <p>Backend decides when e-mails fire (see {@code NotificationEventListener});
 * clients only display status. Bodies never include secrets, tokens, CINs or
 * internal notes — only references and human-readable outcomes.
 */
@Component
public class MailTemplateService {

    @Value("${steg.notifications.mail.brand:STEG — Stages}")
    private String brand;

    public record Mail(String subject, String html) {}

    /** Branded wrapper for any plain-text notification body (default path). */
    public Mail generic(String title, String textBody) {
        String safe = esc(textBody).replace("\n", "<br>");
        return new Mail(title, layout(title, "<p>" + safe + "</p>"));
    }

    public Mail applicationSubmitted(String reference) {
        return new Mail("[" + brand + "] Candidature " + reference + " bien reçue",
                layout("Candidature reçue",
                        "<p>Votre candidature <strong>" + esc(reference) + "</strong> a bien été reçue.</p>"
                                + "<p>Vous pouvez suivre son avancement depuis votre espace candidat. "
                                + "Chaque décision vous sera notifiée par e-mail.</p>"));
    }

    public Mail applicationAccepted(String reference) {
        return new Mail("[" + brand + "] Candidature " + reference + " acceptée",
                layout("Candidature acceptée",
                        "<p>Bonne nouvelle : votre candidature <strong>" + esc(reference)
                                + "</strong> a été acceptée.</p>"
                                + "<p>Un stage sera créé prochainement et un superviseur vous contactera. "
                                + "Ceci est un message automatique, merci de ne pas y répondre.</p>"));
    }

    public Mail applicationRejected(String reference, String reason) {
        String r = (reason == null || reason.isBlank()) ? ""
                : "<p>Motif communiqué : <em>" + esc(reason.strip()) + "</em></p>";
        return new Mail("[" + brand + "] Décision sur votre candidature " + reference,
                layout("Décision sur votre candidature",
                        "<p>Votre candidature <strong>" + esc(reference) + "</strong> n'a pas été retenue.</p>"
                                + r + "<p>Nous vous remercions de votre intérêt.</p>"));
    }

    public Mail internshipAssigned(String internshipRef, String department) {
        return new Mail("[" + brand + "] Affectation stage " + internshipRef,
                layout("Affectation de stage",
                        "<p>Votre stage <strong>" + esc(internshipRef) + "</strong> est affecté à <strong>"
                                + esc(department) + "</strong>.</p>"
                                + "<p>Votre superviseur vous contactera. L'application mobile est votre espace "
                                + "de travail quotidien (tâches, journal, livrables, messages).</p>"));
    }

    public Mail paymentApproved(String financeRef, String amount, String months) {
        return new Mail("[" + brand + "] Indemnité approuvée — " + financeRef,
                layout("Indemnité de stage approuvée",
                        "<p>Le paiement de <strong>" + esc(amount) + " TND</strong> (" + esc(months)
                                + " mois) pour le dossier <strong>" + esc(financeRef)
                                + "</strong> a été approuvé.</p>"
                                + "<p>Le reçu officiel est disponible. Le superviseur informe l'interne des "
                                + "modalités de versement.</p>"));
    }

    public Mail certificateAvailable(String certificateRef) {
        return new Mail("[" + brand + "] Certificat disponible — " + certificateRef,
                layout("Certificat de stage disponible",
                        "<p>Votre certificat de stage <strong>" + esc(certificateRef)
                                + "</strong> est disponible au téléchargement sécurisé.</p>"));
    }

    private String layout(String title, String body) {
        return "<!DOCTYPE html><html lang=\"fr\"><head><meta charset=\"UTF-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width\"></head>"
                + "<body style=\"margin:0;background:#f4f6f8;font-family:Segoe UI,Arial,sans-serif;\">"
                + "<div style=\"max-width:600px;margin:0 auto;background:#ffffff;border-top:4px solid #0B61A0;\">"
                + "<div style=\"padding:20px 28px;border-bottom:1px solid #e5e7eb;\">"
                + "<div style=\"font-size:18px;font-weight:700;color:#042843;\">" + esc(brand) + "</div></div>"
                + "<div style=\"padding:24px 28px;color:#1f2937;font-size:15px;line-height:1.6;\">"
                + "<h1 style=\"font-size:20px;color:#042843;margin:0 0 12px;\">" + esc(title) + "</h1>"
                + body
                + "<p style=\"color:#6b7280;font-size:13px;margin-top:24px;\">Message automatique — "
                + "merci de ne pas y répondre.</p></div>"
                + "<div style=\"padding:14px 28px;background:#042843;color:#ffffff;font-size:12px;\">"
                + "Société Tunisienne de l'Électricité et du Gaz</div></div></body></html>";
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
