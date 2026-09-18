package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PdfReportTest {

    private static final ReportModel EMPTY_MODEL = new ReportModel(
            1L, "example.com", "2026-01-01 00:00:00", "2026-01-01 00:05:00",
            "1 finding", List.of(new FindingRow("PORT", "example.com", "80", "OPEN")));

    @Test
    void startsWithThePdfHeader() {
        byte[] pdf = PdfReport.render(EMPTY_MODEL);
        String header = new String(pdf, 0, 8, StandardCharsets.US_ASCII);
        assertEquals("%PDF-1.4", header);
    }

    @Test
    void endsWithEof() {
        byte[] pdf = PdfReport.render(EMPTY_MODEL);
        String text = new String(pdf, StandardCharsets.US_ASCII);
        assertTrue(text.endsWith("%%EOF"), "must end with %%EOF");
    }

    @Test
    void isDeterministic() {
        byte[] first = PdfReport.render(EMPTY_MODEL);
        byte[] second = PdfReport.render(EMPTY_MODEL);
        assertArrayEquals(first, second);
    }

    @Test
    void everyXrefOffsetPointsAtItsOwnObjectHeader() {
        byte[] pdf = PdfReport.render(EMPTY_MODEL);
        String text = new String(pdf, StandardCharsets.US_ASCII);

        int xrefIndex = text.indexOf("\nxref\n");
        assertTrue(xrefIndex >= 0, "must contain an xref section");
        int countLineEnd = text.indexOf('\n', xrefIndex + 6);
        String countLine = text.substring(xrefIndex + 6, countLineEnd);
        int totalEntries = Integer.parseInt(countLine.split(" ")[1]);

        int lineStart = countLineEnd + 1;
        // entry 0 is the free-list head; skip it
        lineStart += 20;
        for (int objNum = 1; objNum < totalEntries; objNum++) {
            String entry = text.substring(lineStart, lineStart + 20);
            int offset = Integer.parseInt(entry.substring(0, 10));
            String atOffset = text.substring(offset, Math.min(offset + 12, text.length()));
            assertTrue(atOffset.startsWith(objNum + " 0 obj"),
                    "object " + objNum + "'s xref offset " + offset
                            + " does not point at its own header, found: " + atOffset);
            lineStart += 20;
        }
    }

    @Test
    void aSubjectContainingParensAndBackslashesIsEscapedNotInjected() {
        ReportModel model = new ReportModel(1L, "example.com", "s", "f", "summary",
                List.of(new FindingRow("PORT", ") Tj (injected\\", "80", "OPEN")));

        byte[] pdf = PdfReport.render(model);
        String text = new String(pdf, StandardCharsets.US_ASCII);

        assertTrue(text.contains("\\) Tj \\(injected\\\\"),
                "the malicious subject's parens and backslash must be individually escaped");
        assertFalse(text.contains(") Tj (injected\\ ) Tj"),
                "an unescaped close-paren must never appear followed by a raw Tj/open-paren pair");
    }

    @Test
    void nonAsciiAndControlCharactersBecomeAQuestionMark() {
        ReportModel model = new ReportModel(1L, "example.com", "s", "f", "summary",
                List.of(new FindingRow("PORT", "cafédomain", "80", "OPEN")));

        byte[] pdf = PdfReport.render(model);
        String text = new String(pdf, StandardCharsets.US_ASCII);
        assertTrue(text.contains("caf??domain"));
    }

    @Test
    void manyFindingsSpanMultiplePagesAndTheCountMatches() {
        List<FindingRow> rows = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            rows.add(new FindingRow("PORT", "host" + i + ".example.com", String.valueOf(i), "OPEN"));
        }
        ReportModel model = new ReportModel(1L, "example.com", "s", "f", "summary", rows);

        byte[] pdf = PdfReport.render(model);
        String text = new String(pdf, StandardCharsets.US_ASCII);

        int countIndex = text.indexOf("/Count ");
        int countEnd = text.indexOf(' ', countIndex + "/Count ".length());
        int declaredPageCount = Integer.parseInt(text.substring(countIndex + 7, countEnd));
        assertTrue(declaredPageCount > 1, "200 rows must span more than one page");

        int actualPageObjects = 0;
        int index = 0;
        while ((index = text.indexOf("/Type /Page ", index)) >= 0) {
            actualPageObjects++;
            index += 1;
        }
        assertEquals(declaredPageCount, actualPageObjects);
    }

    @Test
    void emptyFindingsStillProducesAValidSinglePageDocument() {
        ReportModel model = new ReportModel(1L, "example.com", "s", "f", "no findings", List.of());
        byte[] pdf = PdfReport.render(model);
        String text = new String(pdf, StandardCharsets.US_ASCII);
        assertTrue(text.contains("(no findings)".replace("(", "\\(").replace(")", "\\)")));
    }
}
