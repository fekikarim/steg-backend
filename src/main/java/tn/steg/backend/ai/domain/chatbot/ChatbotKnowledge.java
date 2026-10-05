package tn.steg.backend.ai.domain.chatbot;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Curated assistant knowledge parsed from {@code assistant-knowledge.md}.
 *
 * <p>Pure value object (no framework): splits the document into sections on
 * {@code ##} headers and scores them by keyword hits for the on-demand
 * {@code kb_lookup} tool. The full text is injected into the system prompt.
 */
public record ChatbotKnowledge(
        String fullText,
        List<Section> sections
) {
    public record Section(String id, String title, String body) {}

    public static ChatbotKnowledge parse(String markdown) {
        String text = markdown == null ? "" : markdown;
        List<Section> sections = new ArrayList<>();
        String[] lines = text.split("\\R");
        String title = null;
        StringBuilder body = new StringBuilder();
        int index = 0;
        for (String line : lines) {
            if (line.startsWith("## ")) {
                if (title != null) {
                    sections.add(new Section("kb-" + index++, title, body.toString().strip()));
                }
                title = line.substring(3).strip();
                body = new StringBuilder();
            } else if (title != null) {
                body.append(line).append('\n');
            }
        }
        if (title != null) {
            sections.add(new Section("kb-" + index, title, body.toString().strip()));
        }
        return new ChatbotKnowledge(text, List.copyOf(sections));
    }

    /** Keyword lookup over section title+body (accent/case-insensitive), best first, capped. */
    public List<Section> lookup(String topic, int max) {
        String norm = normalize(topic);
        List<Scored> scored = new ArrayList<>();
        for (Section s : sections) {
            String hay = normalize(s.title() + "\n" + s.body());
            int hits = 0;
            for (String word : norm.split("[^a-z0-9]+")) {
                if (word.length() > 2 && hay.contains(word)) {
                    hits++;
                }
            }
            if (hits > 0) {
                scored.add(new Scored(s, hits));
            }
        }
        scored.sort((a, b) -> Integer.compare(b.score(), a.score()));
        return scored.stream()
                .limit(Math.max(1, Math.min(max, 4)))
                .map(Scored::section)
                .toList();
    }

    static String normalize(String s) {
        if (s == null) {
            return "";
        }
        String lower = s.toLowerCase(Locale.ROOT);
        String decomposed = Normalizer.normalize(lower, Normalizer.Form.NFD);
        StringBuilder sb = new StringBuilder(decomposed.length());
        for (int i = 0; i < decomposed.length(); i++) {
            char c = decomposed.charAt(i);
            if (Character.getType(c) != Character.NON_SPACING_MARK) {
                sb.append(switch (c) {
                    case 'æ' -> "ae";
                    case 'œ' -> "oe";
                    case 'ø' -> "o";
                    case 'å' -> "a";
                    default -> String.valueOf(c);
                });
            }
        }
        return sb.toString();
    }

    private record Scored(Section section, int score) {}
}
