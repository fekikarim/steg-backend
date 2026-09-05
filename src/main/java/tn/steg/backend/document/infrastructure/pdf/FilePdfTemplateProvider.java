package tn.steg.backend.document.infrastructure.pdf;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import tn.steg.backend.common.domain.exception.BusinessRuleException;
import tn.steg.backend.document.domain.service.PdfTemplateProvider;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Classpath text-file implementation of {@link PdfTemplateProvider}.
 */
@Slf4j
@Component
public class FilePdfTemplateProvider implements PdfTemplateProvider {

    @Override
    public ResolvedTemplate resolve(String logicalName, Map<String, String> values) {
        StructuredTemplate structured = resolveStructured(logicalName, values);
        List<String> paragraphs = new ArrayList<>(structured.introParagraphs());
        paragraphs.addAll(structured.bodyParagraphs());
        return new ResolvedTemplate(structured.title(), structured.subtitle(), paragraphs, structured.footer());
    }

    @Override
    public StructuredTemplate resolveStructured(String logicalName, Map<String, String> values) {
        String raw = stripComments(readTemplate(logicalName));
        String title = section(raw, "TITLE");
        String subtitle = section(raw, "SUBTITLE");
        String footer = section(raw, "FOOTER");
        List<String> intro = splitParagraphs(section(raw, "INTRO"), values);
        List<String> body = splitParagraphs(section(raw, "BODY"), values);
        List<TableRow> details = new ArrayList<>();
        for (String line : section(raw, "DETAILS").split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            int separator = trimmed.indexOf(':');
            if (separator <= 0) {
                throw new BusinessRuleException("TEMPLATE_INVALID",
                        "DETAILS lines in template '" + logicalName + "' must have the form 'Label : {{key}}'.");
            }
            details.add(new TableRow(
                    substitute(trimmed.substring(0, separator).strip(), values),
                    substitute(trimmed.substring(separator + 1).strip(), values)));
        }
        if (title.isBlank() || (intro.isEmpty() && body.isEmpty() && details.isEmpty())) {
            throw new BusinessRuleException("TEMPLATE_INVALID",
                    "Document template '" + logicalName + "' must define TITLE and content sections.");
        }
        return new StructuredTemplate(
                substitute(title.strip(), values),
                substitute(subtitle.strip(), values),
                intro, details, body,
                substitute(footer.strip(), values));
    }

    private List<String> splitParagraphs(String section, Map<String, String> values) {
        List<String> paragraphs = new ArrayList<>();
        for (String paragraph : section.split("\\R\\s*\\R")) {
            String trimmed = paragraph.strip();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                paragraphs.add(substitute(trimmed, values));
            }
        }
        return paragraphs;
    }

    private String readTemplate(String logicalName) {
        String location = "templates/" + logicalName + ".txt";
        ClassPathResource resource = new ClassPathResource(location);
        if (!resource.exists()) {
            throw new BusinessRuleException("TEMPLATE_UNAVAILABLE",
                    "Document template '" + logicalName + "' is missing from the classpath at '" + location + "'.");
        }
        try (InputStream in = resource.getInputStream()) {
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BusinessRuleException("TEMPLATE_UNAVAILABLE",
                    "Failed to read document template '" + logicalName + "': " + e.getMessage());
        }
    }

    /**
     * Removes full-line comments first: template headers document the
     * {@code [SECTION]} markers literally, so markers must only be searched
     * after comments are gone.
     */
    private String stripComments(String raw) {
        StringBuilder cleaned = new StringBuilder();
        for (String line : raw.split("\\R")) {
            if (!line.strip().startsWith("#")) {
                cleaned.append(line).append('\n');
            }
        }
        return cleaned.toString();
    }

    private String section(String raw, String name) {
        String marker = "[" + name + "]";
        int start = raw.indexOf(marker);
        if (start < 0) {
            return "";
        }
        start += marker.length();
        int end = raw.length();
        for (String next : List.of("[TITLE]", "[SUBTITLE]", "[INTRO]", "[DETAILS]", "[BODY]", "[FOOTER]")) {
            if (next.equals(marker)) {
                continue;
            }
            int idx = raw.indexOf(next, start);
            if (idx >= 0) {
                end = Math.min(end, idx);
            }
        }
        return raw.substring(start, end).strip();
    }

    private String substitute(String text, Map<String, String> values) {
        String result = text;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}",
                    entry.getValue() != null ? entry.getValue() : "");
        }
        return result;
    }
}
