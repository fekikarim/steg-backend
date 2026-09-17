package tn.steg.backend.ai.domain.knowledge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Controlled STEG knowledge base for RAG assistants (E3/E5).
 *
 * <p>The single source of official STEG internship information fed to Gemini.
 * Gemini is never the source of truth: assemblers retrieve the matching
 * entries below and the model only verbalises them. Out-of-scope questions
 * get an explicit decline (see {@link #DECLINE}).
 *
 * <p>Content is intentionally generic-official; any value marked
 * {@code TODO — STEG VALIDATION REQUIRED} must be confirmed with STEG.
 */
@Component
public class StegKnowledgeBase {

    public static final String DECLINE =
            "Cette information ne figure pas dans la documentation officielle des stages STEG. "
                    + "Veuillez contacter le service concerné pour une réponse officielle. / "
                    + "This information is not in the official STEG internship documentation.";

    public record Entry(String id, List<String> keywords, String content) {}

    private final List<Entry> entries = List.of(
            new Entry("steg-overview",
                    List.of("steg", "entreprise", "société", "company", "tunisienne", "électricité", "gaz",
                            "electricity", "about", "présentation"),
                    "La STEG (Société Tunisienne de l'Électricité et du Gaz) est l'opérateur public "
                            + "tunisien de l'électricité et du gaz. Elle accueille des stagiaires dans ses "
                            + "directions, départements et unités à travers le pays."),
            new Entry("internship-types",
                    List.of("type", "types", "observation", "perfectionnement", "pfe", "projet de fin",
                            "différence", "difference", "stage ouvrier", "kind"),
                    "Trois types de stage : (1) Observation — courte immersion découverte, non rémunérée ; "
                            + "(2) Perfectionnement — stage pratique intermédiaire pour appliquer les acquis ; "
                            + "(3) PFE (Projet de Fin d'Études) — stage long de fin d'études avec livrables. "
                            + "Le type est déterminé par le backend à partir du niveau académique, de la période "
                            + "demandée et des pièces justificatives — le candidat ne le choisit pas."),
            new Entry("mandatory-rule",
                    List.of("obligatoire", "mandatory", "optionnel", "optional", "rémunéré", "payé",
                            "indemnité", "allowance", "payment", "paiement", "gratification"),
                    "Seuls les stages obligatoires (requis par le cursus universitaire), terminés, avec "
                            + "certificat généré et rapport validé, peuvent entrer dans le circuit Finance. "
                            + "L'indemnité est calculée par le backend (50 TND par mois complet, max 3 mois / 150 TND). "
                            + "TODO — STEG VALIDATION REQUIRED: confirmer les taux officiels."),
            new Entry("required-documents",
                    List.of("document", "documents", "dossier", "pièce", "demande de stage", "lettre d'affectation",
                            "convention", "cv", "relevé", "required", "fournir", "upload", "pdf"),
                    "Documents requis : (1) la Demande de stage (PDF, contenant « Demande de stage » et le nom "
                            + "complet du candidat) ; (2) la Lettre d'affectation (PDF). Le service de détection "
                            + "vérifie le type de document par mots-clés et la présence du nom complet (normalisé, "
                            + "insensible aux accents, correspondance floue contrôlée), avec repli OCR pour les PDF "
                            + "scannés. Un rapport de stage est demandé pour les PFE et le circuit Finance."),
            new Entry("procedure",
                    List.of("procédure", "procedure", "étapes", "steps", "candidature", "apply", "soumettre",
                            "submit", "suivi", "tracking", "référence", "reference", "comment postuler"),
                    "Procédure : 1) créer un compte et compléter le profil ; 2) démarrer l'assistant de "
                            + "candidature (brouillon DRAFT reprisable) ; 3) choisir les dates (type dérivé par le "
                            + "backend) ; 4) téléverser les PDF ; 5) relire et soumettre ; 6) suivre via la référence "
                            + "de suivi. Chaque décision (acceptation, correction demandée, rejet) est notifiée "
                            + "par e-mail et dans l'application."),
            new Entry("workflow-status",
                    List.of("statut", "status", "under_review", "accepted", "rejected", "correction",
                            "en cours", "validé", "refusé", "workflow", "suivi dossier"),
                    "Cycle de vie d'une candidature : DRAFT → SUBMITTED → UNDER_REVIEW → "
                            + "(NEEDS_CORRECTION → resoumission) → ACCEPTED / REJECTED / WITHDRAWN. "
                            + "Le suivi affiche l'historique réel du workflow, pas une frise statique."),
            new Entry("intern-life",
                    List.of("journal", "tâche", "task", "livrable", "deliverable", "carnet", "logbook",
                            "évaluation", "evaluation", "superviseur", "supervisor", "quotidien", "daily"),
                    "Pendant le stage (application mobile) : l'interne enregistre tâches et journal quotidien ; "
                            + "le superviseur valide chaque jour avec feedback ; les livrables sont versionnés et "
                            + "validés ; à la fin, le Carnet de stage (colonnes Période / Tâche) est généré "
                            + "uniquement à partir des activités réellement enregistrées, relu par l'interne puis "
                            + "validé par le superviseur avant d'être officiel."),
            new Entry("faq-duration",
                    List.of("durée", "duration", "combien de temps", "long", "période", "period", "dates"),
                    "La durée dépend du type et de l'établissement. Les dates choisies déterminent le type "
                            + "calculé par le backend. TODO — STEG VALIDATION REQUIRED: durées min/max officielles "
                            + "par type."),
            new Entry("faq-contact",
                    List.of("contact", "email", "téléphone", "phone", "adresse", "address", "contacter", "help",
                            "aide", "support"),
                    "Pour toute question hors documentation, utilisez la page Contact du Front Office ou "
                            + "l'adresse officielle de votre établissement. L'assistant ne fournit jamais de "
                            + "coordonnées personnelles."));
    private static final int MAX_ENTRIES = 4;

    /** Keyword retrieval: scores entries by keyword hits (case/accent-insensitive). */
    public List<Entry> retrieve(String question, int max) {
        String norm = normalize(question);
        List<Scored> scored = new ArrayList<>();
        for (Entry e : entries) {
            int hits = 0;
            for (String kw : e.keywords()) {
                if (norm.contains(normalize(kw))) {
                    hits++;
                }
            }
            if (hits > 0) {
                scored.add(new Scored(e, hits));
            }
        }
        scored.sort((a, b) -> Integer.compare(b.score(), a.score()));
        return scored.stream().limit(Math.max(1, Math.min(max, MAX_ENTRIES))).map(Scored::entry).toList();
    }

    public List<Entry> retrieve(String question) {
        return retrieve(question, MAX_ENTRIES);
    }

    /** Renders retrieved entries as a context block for the model prompt. */
    public static String renderContext(List<Entry> found) {
        if (found.isEmpty()) {
            return "(Aucune fiche officielle correspondante — décliner poliment : " + DECLINE + ")";
        }
        StringBuilder sb = new StringBuilder("Fiches officielles STEG applicables :\n");
        for (Entry e : found) {
            sb.append("[").append(e.id()).append("] ").append(e.content()).append("\n");
        }
        return sb.toString();
    }

    private record Scored(Entry entry, int score) {}

    static String normalize(String s) {
        if (s == null) {
            return "";
        }
        String lower = s.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            sb.append(switch (c) {
                case 'à', 'â', 'ä' -> 'a';
                case 'é', 'è', 'ê', 'ë' -> 'e';
                case 'î', 'ï' -> 'i';
                case 'ô', 'ö' -> 'o';
                case 'û', 'ü', 'ù' -> 'u';
                case 'ç' -> 'c';
                case 'ÿ' -> 'y';
                default -> c;
            });
        }
        return sb.toString();
    }
}
