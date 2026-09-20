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
        return new Mail("[" + brand + "] Candidature " + reference + " — Confirmation de soumission",
                layout("Candidature soumise avec succès",
                        "<p>Madame, Monsieur,</p>"
                                + "<p>Nous avons le plaisir de vous confirmer que votre candidature de stage "
                                + "<strong>" + esc(reference) + "</strong> a bien été <strong>soumise avec succès</strong> "
                                + "et est maintenant <strong>en attente de validation</strong> par le superviseur responsable.</p>"
                                + "<div style=\"background:#f0f7ff;border-left:4px solid #0B61A0;padding:12px 16px;margin:16px 0;\">"
                                + "<p style=\"margin:0 0 6px;font-weight:600;color:#042843;\">Statut actuel : En attente de validation</p>"
                                + "<p style=\"margin:0;color:#374151;\">Référence à conserver : <strong>" + esc(reference) + "</strong></p>"
                                + "</div>"
                                + "<p><strong>Prochaines étapes :</strong></p>"
                                + "<ul style=\"margin:8px 0;padding-left:20px;color:#1f2937;\">"
                                + "<li>Votre dossier sera examiné par le superviseur du département concerné</li>"
                                + "<li>Vous recevrez une notification par e-mail et dans votre espace candidat dès qu'une décision sera prise</li>"
                                + "<li>Vous pouvez suivre l'avancement de votre candidature à tout moment depuis votre espace candidat</li>"
                                + "</ul>"
                                + "<p style=\"margin-top:16px;\">Nous vous remercions pour votre intérêt pour la STEG et vous souhaitons bonne chance.</p>"
                                + "<p style=\"color:#6b7280;font-size:13px;\">Ceci est un message automatique, merci de ne pas y répondre. "
                                + "Pour toute question, contactez votre espace candidat.</p>"));
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
        // Modern, formal, responsive STEG template — table-based for client compatibility, mobile-optimized
        return "<!DOCTYPE html><html lang=\"fr\"><head><meta charset=\"UTF-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">"
                + "<meta name=\"color-scheme\" content=\"light\">"
                + "<meta name=\"supported-color-schemes\" content=\"light\">"
                + "<style>@media only screen and (max-width:600px){.steg-container{width:100% !important;} .steg-pad{padding:20px !important;} .steg-h1{font-size:20px !important;}}</style>"
                + "</head>"
                + "<body style=\"margin:0;padding:0;background:#f1f5f9;\">"
                + "<div style=\"display:none;max-height:0;overflow:hidden;mso-hide:all;\">" + esc(title) + " — " + esc(brand) + "</div>"
                + "<table role=\"presentation\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\" width=\"100%\" style=\"background:#f1f5f9;padding:24px 12px;\">"
                + "<tr><td align=\"center\">"
                + "<table role=\"presentation\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\" width=\"600\" class=\"steg-container\" style=\"width:600px;max-width:600px;background:#ffffff;border-radius:12px;overflow:hidden;box-shadow:0 4px 24px rgba(4,40,67,0.08);\">"
                // Header
                + "<tr><td style=\"background:#042843;padding:22px 28px;\">"
                + "<div style=\"font-family:'Segoe UI',Helvetica,Arial,sans-serif;font-size:13px;font-weight:600;letter-spacing:0.08em;text-transform:uppercase;color:#7dd3fc;\">STEG</div>"
                + "<div style=\"font-family:'Segoe UI',Helvetica,Arial,sans-serif;font-size:18px;font-weight:700;color:#ffffff;margin-top:4px;\">" + esc(brand) + "</div>"
                + "<div style=\"font-family:'Segoe UI',Helvetica,Arial,sans-serif;font-size:12px;color:#cbd5e1;margin-top:6px;\">Société Tunisienne de l'Électricité et du Gaz — Stages & Démarches</div>"
                + "</td></tr>"
                // Accent bar
                + "<tr><td style=\"height:4px;background:linear-gradient(90deg,#0B61A0 0%,#38bdf8 100%);line-height:4px;font-size:0;\">&nbsp;</td></tr>"
                // Body
                + "<tr><td class=\"steg-pad\" style=\"padding:28px 32px;\">"
                + "<h1 class=\"steg-h1\" style=\"font-family:'Segoe UI',Helvetica,Arial,sans-serif;font-size:22px;font-weight:800;color:#042843;margin:0 0 16px;line-height:1.3;\">" + esc(title) + "</h1>"
                + "<div style=\"font-family:'Segoe UI',Helvetica,Arial,sans-serif;font-size:15px;line-height:1.7;color:#1e293b;\">" + body + "</div>"
                + "<div style=\"margin-top:28px;padding:14px 16px;background:#f8fafc;border:1px solid #e2e8f0;border-radius:8px;\">"
                + "<p style=\"margin:0;font-family:'Segoe UI',Helvetica,Arial,sans-serif;font-size:12px;color:#64748b;line-height:1.5;\">"
                + "Message automatique — merci de ne pas y répondre.<br>"
                + "Pour toute question, connectez-vous à votre espace candidat sur la plateforme STEG Stages."
                + "</p></div>"
                + "</td></tr>"
                // Footer
                + "<tr><td style=\"background:#f8fafc;padding:18px 28px;border-top:1px solid #e2e8f0;\">"
                + "<div style=\"font-family:'Segoe UI',Helvetica,Arial,sans-serif;font-size:11px;color:#64748b;line-height:1.6;text-align:center;\">"
                + "Société Tunisienne de l'Électricité et du Gaz &nbsp;·&nbsp; STEG Stages & Démarches<br>"
                + "Plateforme officielle — Tunis, Tunisie<br>"
                + "<span style=\"color:#94a3b8;\">Cet e-mail a été envoyé automatiquement par le système STEG. Veuillez ne pas y répondre directement.</span>"
                + "</div></td></tr>"
                + "</table></td></tr></table>"
                + "</body></html>";
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
