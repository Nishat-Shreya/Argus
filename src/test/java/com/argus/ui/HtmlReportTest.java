package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Section 7.3: {@code HtmlReport} — the security-critical, toolkit-free HTML document builder
 * (plan §3.3, §5.1). Every interpolated value must be escaped; the document must be standalone
 * (no {@code <script>}, no external reference of any kind).
 */
class HtmlReportTest {

    @Test
    void h1DocumentStartsWithDoctypeAndDeclaresUtf8Charset() {
        ReportModel model = model(List.of());

        String html = HtmlReport.render(model);

        assertTrue(html.startsWith("<!DOCTYPE html>"));
        assertTrue(html.contains("<meta charset=\"utf-8\">"));
    }

    @Test
    void h2ContainsTargetTimestampsScanIdAndSummaryLine() {
        ReportModel model = new ReportModel(42L, "example.com", "2026-09-14 14:32:07",
                "2026-09-14 14:40:00", "3 subdomain", List.of());

        String html = HtmlReport.render(model);

        assertTrue(html.contains("example.com"));
        assertTrue(html.contains("2026-09-14 14:32:07"));
        assertTrue(html.contains("2026-09-14 14:40:00"));
        assertTrue(html.contains("42"));
        assertTrue(html.contains("3 subdomain"));
    }

    @Test
    void h3OneTrPerFindingInSnapshotRowsOrder() {
        FindingRow probeA = new FindingRow("port", "a.example.com", "80", "open");
        FindingRow probeB = new FindingRow("port", "a.example.com", "443", "closed");
        FindingRow nonProbe = new FindingRow("subdomain", "b.example.com", "", "");
        ReportModel model = model(List.of(probeA, probeB, nonProbe));

        String html = HtmlReport.render(model);

        int indexA = html.indexOf("a.example.com");
        int indexAPort443 = html.indexOf("443");
        int indexB = html.indexOf("b.example.com");

        assertTrue(indexA >= 0 && indexAPort443 >= 0 && indexB >= 0);
        assertTrue(indexA < indexAPort443, "port 80 row must come before port 443 row");
        assertTrue(indexAPort443 < indexB, "probes must come before non-probes");

        int trCount = countOccurrences(html, "<tr>");
        // 1 header row + 3 finding rows
        assertEquals(4, trCount);
    }

    @Test
    void h4RenderIsDeterministic() {
        ReportModel model = model(List.of(new FindingRow("port", "a.example.com", "80", "open")));

        String first = HtmlReport.render(model);
        String second = HtmlReport.render(model);

        assertEquals(first, second);
    }

    @Test
    void h5SubjectContainingAScriptTagIsEscaped() {
        FindingRow hostile =
                new FindingRow("subdomain", "<script>alert(1)</script>.example.com", "", "");
        ReportModel model = model(List.of(hostile));

        String html = HtmlReport.render(model);

        assertFalse(html.contains("<script"), "unescaped <script must never appear");
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"));
    }

    @Test
    void h6TargetContainingAttributeCharsIsEscapedAndAmpersandFirst() {
        ReportModel model = new ReportModel(1L, "a\"b'c&d target", "2026-01-01 00:00:00", "",
                "no findings recorded for this scan", List.of());

        String html = HtmlReport.render(model);

        assertTrue(html.contains("a&quot;b&#39;c&amp;d target"));

        // "escape & first": an input already containing "&lt;" must render as "&amp;lt;", not
        // "&lt;" -- proving the raw input character '&' is escaped on its own, not re-scanned
        // after '<' has already been turned into markup.
        String escaped = HtmlReport.escape("&lt;");
        assertEquals("&amp;lt;", escaped);
    }

    @Test
    void h7TheAppAuthoredTemplateItselfMakesNoExternalReferenceOfAnyKind() {
        // The static document the app itself writes -- independent of finding data -- must
        // never contain a script element, an external link/script/image reference, or an
        // inline event handler. (H5/H6 separately prove hostile finding data can never inject
        // a REAL "<script"/"<link"/"<iframe" tag, since escaping neutralizes the leading "<".
        // This test proves the app's own authored markup carries no such reference either --
        // a hostile subject may still contain the plain WORD "javascript:" or "onerror=" as
        // inert escaped text, which is harmless and not what this test is about.)
        ReportModel model = model(List.of(new FindingRow("port", "a.example.com", "80", "open")));

        String html = HtmlReport.render(model);

        assertFalse(html.contains("<script"));
        assertFalse(html.contains("<link"));
        assertFalse(html.contains("<iframe"));
        assertFalse(html.contains("http://"));
        assertFalse(html.contains("https://"));
        assertFalse(html.contains("javascript:"));
        assertFalse(html.contains("srcdoc"));
        assertFalse(html.contains("onerror="));
    }

    @Test
    void h7bHostileMarkupInFindingDataCanNeverBecomeARealTagOrAttribute() {
        // The attacker-controlled half of the same guarantee: even when a subject is packed
        // with tag-like and attribute-like text, escaping the leading "<" means no REAL
        // "<script", "<link" or "<iframe" tag, and no real onerror= attribute, ever appears --
        // only inert text inside a table cell.
        FindingRow hostile = new FindingRow("subdomain",
                "<iframe srcdoc=\"x\"></iframe><img onerror=alert(1) src=x> javascript:alert(1) "
                        + "http://evil.test https://evil.test <link rel=stylesheet>",
                "", "");
        ReportModel model = model(List.of(hostile));

        String html = HtmlReport.render(model);

        assertFalse(html.contains("<script"));
        assertFalse(html.contains("<link"));
        assertFalse(html.contains("<iframe"));
        assertFalse(html.contains("<img"));
    }

    @Test
    void h8UnseenVocabularyTokensSurviveAndTheTemplateHasNoHardCodedLiteral() {
        FindingRow unseen = new FindingRow("certificate", "c.example.com", "443", "throttled");
        ReportModel model = model(List.of(unseen));

        String html = HtmlReport.render(model);

        assertTrue(html.contains("certificate"));
        assertTrue(html.contains("throttled"));
        assertFalse(html.contains("PORT"));
        assertFalse(html.contains("SUBDOMAIN"));
        assertFalse(html.contains("OPEN"));
    }

    @Test
    void h9EmptyScanRendersAValidDocumentWithNoStrayRows() {
        ReportModel model = model(List.of());

        String html = HtmlReport.render(model);

        assertTrue(html.contains("no findings recorded for this scan"));
        // exactly the header row, no finding rows
        assertEquals(1, countOccurrences(html, "<tr>"));
    }

    @Test
    void h10NoCredentialOrVaultMaterialAnywhereInTheOutput() {
        ReportModel model = model(List.of(new FindingRow("port", "a.example.com", "80", "open")));

        String html = HtmlReport.render(model).toLowerCase(java.util.Locale.ROOT);

        assertFalse(html.contains("vault"));
        assertFalse(html.contains("apikey"));
        assertFalse(html.contains("api-key"));
        assertFalse(html.contains("password"));
        assertFalse(html.contains("webhook"));
        assertFalse(html.contains("operator"));
        assertFalse(html.contains(".db"));
    }

    private static ReportModel model(List<FindingRow> rows) {
        return new ReportModel(1L, "example.com", "2026-09-14 14:32:07", "2026-09-14 14:40:00",
                rows.isEmpty() ? "no findings recorded for this scan" : "3 subdomain", rows);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) != -1) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
