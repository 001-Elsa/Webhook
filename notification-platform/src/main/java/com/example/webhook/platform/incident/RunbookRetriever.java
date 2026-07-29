package com.example.webhook.platform.incident;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Structured keyword retrieval over runbook markdown sections.
 * This is NOT vector RAG: sections are split on {@code ##} headings and ranked
 * by token overlap with incident signals (status codes, error tokens, backlog).
 */
@Component
public class RunbookRetriever {
    private static final Pattern HEADING = Pattern.compile("(?m)^##\\s+(.+)$");
    private static final Pattern TOKEN = Pattern.compile("[a-zA-Z0-9_./-]{2,}");
    private static final int DEFAULT_TOP_K = 3;

    private final List<Section> sections;

    public RunbookRetriever() {
        this(loadClasspathRunbook());
    }

    RunbookRetriever(String markdown) {
        this.sections = parseSections(markdown == null ? "" : markdown);
    }

    public String retrieve(IncidentSignals signals) {
        return retrieve(signals, DEFAULT_TOP_K);
    }

    public String retrieve(IncidentSignals signals, int topK) {
        List<ScoredSection> ranked = rank(signals);
        if (ranked.isEmpty()) {
            return "No runbook excerpt available.";
        }
        return ranked.stream()
                .limit(Math.max(1, topK))
                .map(scored -> "### [" + scored.section().id() + "] " + scored.section().title()
                        + "\n" + scored.section().body().trim())
                .collect(Collectors.joining("\n\n"));
    }

    List<ScoredSection> rank(IncidentSignals signals) {
        Set<String> query = tokenize(signals == null ? null : signals.toQueryText());
        return sections.stream()
                .map(section -> new ScoredSection(section, score(query, section.tokens())))
                .sorted(Comparator.comparingInt(ScoredSection::score).reversed()
                        .thenComparing(scored -> scored.section().id()))
                .toList();
    }

    static List<Section> parseSections(String markdown) {
        Matcher matcher = HEADING.matcher(markdown);
        List<int[]> ranges = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        while (matcher.find()) {
            ranges.add(new int[]{matcher.start(), matcher.end()});
            titles.add(matcher.group(1).trim());
        }
        List<Section> parsed = new ArrayList<>();
        if (ranges.isEmpty()) {
            String body = markdown.trim();
            if (!body.isEmpty()) {
                parsed.add(new Section("RB-0", "runbook", body, tokenize(body)));
            }
            return parsed;
        }
        for (int i = 0; i < ranges.size(); i++) {
            int bodyStart = ranges.get(i)[1];
            int bodyEnd = i + 1 < ranges.size() ? ranges.get(i + 1)[0] : markdown.length();
            String body = markdown.substring(bodyStart, bodyEnd).trim();
            String id = "RB-" + (i + 1);
            String title = titles.get(i);
            parsed.add(new Section(id, title, body, tokenize(title + " " + body)));
        }
        return parsed;
    }

    private static int score(Set<String> query, Set<String> sectionTokens) {
        if (query.isEmpty() || sectionTokens.isEmpty()) {
            return 0;
        }
        int overlap = 0;
        for (String token : query) {
            if (sectionTokens.contains(token)) {
                overlap++;
            }
        }
        return overlap;
    }

    static Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        Set<String> tokens = new LinkedHashSet<>();
        Matcher matcher = TOKEN.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }

    private static String loadClasspathRunbook() {
        try {
            return new ClassPathResource("runbooks/incident-control-plane.md")
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception failure) {
            return "";
        }
    }

    public record IncidentSignals(
            Integer statusCode,
            String deliveryStatus,
            String errorText,
            long outboxBacklog,
            boolean circuitOpen,
            boolean endpointPaused
    ) {
        String toQueryText() {
            List<String> parts = new ArrayList<>();
            if (statusCode != null) {
                parts.add(String.valueOf(statusCode));
                if (statusCode == 401 || statusCode == 403 || statusCode == 404) {
                    parts.add("auth configuration 401 403 404");
                } else if (statusCode == 429) {
                    parts.add("rate limit retry-after 429");
                } else if (statusCode >= 500) {
                    parts.add("receiver 5xx transient network failure");
                }
            }
            if (deliveryStatus != null) {
                parts.add(deliveryStatus);
            }
            if (errorText != null) {
                parts.add(errorText);
            }
            if (outboxBacklog > 0) {
                parts.add("outbox backlog pending publisher");
            }
            if (circuitOpen) {
                parts.add("circuit open cooldown");
            }
            if (endpointPaused) {
                parts.add("endpoint paused");
            }
            return String.join(" ", parts);
        }
    }

    record Section(String id, String title, String body, Set<String> tokens) { }

    record ScoredSection(Section section, int score) { }
}
