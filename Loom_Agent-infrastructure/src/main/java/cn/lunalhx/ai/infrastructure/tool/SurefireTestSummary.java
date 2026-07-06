package cn.lunalhx.ai.infrastructure.tool;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SurefireTestSummary {

    private static final Pattern EXPECTED_ACTUAL = Pattern.compile(
            "expected:\\s*<([^>]*)>\\s*but was:\\s*<([^>]*)>",
            Pattern.CASE_INSENSITIVE);
    private static final int MAX_FAILURES = 20;

    private SurefireTestSummary() {
    }

    static Map<String, Object> read(Path reportsDirectory) {
        if (reportsDirectory == null || !Files.isDirectory(reportsDirectory)) {
            return unavailable();
        }
        try (var paths = Files.list(reportsDirectory)) {
            return readReports(paths
                    .filter(SurefireTestSummary::isReport)
                    .sorted()
                    .toList());
        } catch (Exception e) {
            return parseFailure(e);
        }
    }

    static Map<String, Object> readForExecution(Path cwd, long startedAtMillis) {
        if (cwd == null || !Files.isDirectory(cwd)) {
            return unavailable();
        }
        long threshold = Math.max(0L, startedAtMillis - 2_000L);
        try (var paths = Files.walk(cwd, 8)) {
            List<Path> reports = paths
                    .filter(SurefireTestSummary::isReport)
                    .filter(path -> isReportDirectory(path.getParent()))
                    .filter(path -> modifiedAtOrAfter(path, threshold))
                    .sorted()
                    .toList();
            return reports.isEmpty() ? unavailable() : readReports(reports);
        } catch (Exception e) {
            return parseFailure(e);
        }
    }

    private static Map<String, Object> readReports(List<Path> reports) {
        Map<String, Object> summary = new LinkedHashMap<>();
        int tests = 0;
        int failures = 0;
        int errors = 0;
        int skipped = 0;
        List<Map<String, Object>> failureDetails = new ArrayList<>();

        try {
            for (Path report : reports) {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                factory.setXIncludeAware(false);
                factory.setExpandEntityReferences(false);
                Document document = factory.newDocumentBuilder().parse(report.toFile());
                Element suite = document.getDocumentElement();
                tests += integerAttribute(suite, "tests");
                failures += integerAttribute(suite, "failures");
                errors += integerAttribute(suite, "errors");
                skipped += integerAttribute(suite, "skipped");

                NodeList cases = suite.getElementsByTagName("testcase");
                for (int i = 0; i < cases.getLength() && failureDetails.size() < MAX_FAILURES; i++) {
                    Element testCase = (Element) cases.item(i);
                    Element failure = child(testCase, "failure");
                    if (failure == null) {
                        failure = child(testCase, "error");
                    }
                    if (failure != null) {
                        failureDetails.add(failure(testCase, failure, suite));
                    }
                }
            }
            summary.put("available", true);
        } catch (Exception e) {
            return parseFailure(e);
        }

        summary.put("tests", tests);
        summary.put("failures", failures);
        summary.put("errors", errors);
        summary.put("skipped", skipped);
        summary.put("passed", failures == 0 && errors == 0);
        summary.put("failureDetails", failureDetails);
        summary.put("truncated", failures + errors > failureDetails.size());
        return summary;
    }

    private static boolean isReport(Path path) {
        String name = path.getFileName().toString();
        return Files.isRegularFile(path)
                && name.startsWith("TEST-")
                && name.endsWith(".xml");
    }

    private static boolean isReportDirectory(Path directory) {
        if (directory == null || directory.getFileName() == null) {
            return false;
        }
        String name = directory.getFileName().toString();
        return "surefire-reports".equals(name) || "failsafe-reports".equals(name);
    }

    private static boolean modifiedAtOrAfter(Path path, long threshold) {
        try {
            return Files.getLastModifiedTime(path).toMillis() >= threshold;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static Map<String, Object> unavailable() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("available", false);
        return summary;
    }

    private static Map<String, Object> parseFailure(Exception exception) {
        Map<String, Object> summary = unavailable();
        summary.put("parseError", exception.getMessage());
        return summary;
    }

    static String render(Map<String, Object> summary) {
        if (!Boolean.TRUE.equals(summary.get("available"))) {
            return "[test_result] Surefire reports unavailable";
        }
        StringBuilder text = new StringBuilder("[test_result] tests=")
                .append(summary.get("tests"))
                .append(" failures=").append(summary.get("failures"))
                .append(" errors=").append(summary.get("errors"))
                .append(" skipped=").append(summary.get("skipped"));
        Object details = summary.get("failureDetails");
        if (details instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> failure) {
                    text.append("\n- ")
                            .append(failure.get("className")).append('#')
                            .append(failure.get("testName")).append(": ")
                            .append(failure.get("message"));
                    if (failure.get("expected") != null || failure.get("actual") != null) {
                        text.append(" expected=").append(failure.get("expected"))
                                .append(" actual=").append(failure.get("actual"));
                    }
                    if (failure.get("stderr") != null) {
                        text.append("\n  stderr: ").append(failure.get("stderr"));
                    }
                }
            }
        }
        return text.toString();
    }

    private static Map<String, Object> failure(
            Element testCase, Element failure, Element suite) {
        Map<String, Object> result = new LinkedHashMap<>();
        String message = failure.getAttribute("message");
        String body = failure.getTextContent();
        Matcher matcher = EXPECTED_ACTUAL.matcher(message + "\n" + body);
        result.put("className", testCase.getAttribute("classname"));
        result.put("testName", testCase.getAttribute("name"));
        result.put("type", failure.getAttribute("type"));
        result.put("message", message);
        Element stderr = child(testCase, "system-err");
        if (stderr == null) {
            stderr = child(suite, "system-err");
        }
        if (stderr != null && !stderr.getTextContent().isBlank()) {
            result.put("stderr", abbreviate(stderr.getTextContent().trim(), 2000));
        }
        if (matcher.find()) {
            result.put("expected", matcher.group(1));
            result.put("actual", matcher.group(2));
        }
        return result;
    }

    private static String abbreviate(String value, int maxLength) {
        return value.length() <= maxLength
                ? value : value.substring(0, maxLength) + "...";
    }

    private static Element child(Element parent, String name) {
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element element && name.equals(element.getTagName())) {
                return element;
            }
        }
        return null;
    }

    private static int integerAttribute(Element element, String name) {
        try {
            return Integer.parseInt(element.getAttribute(name));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
