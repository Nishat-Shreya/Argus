package com.argus.ui;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Builds a complete, standalone PDF 1.4 document from a {@link ReportModel} (P3-18). Zero new
 * dependencies: no PDFBox, no {@code javafx.print}/OS printer driver (that route was rejected
 * in P3-04's planning specifically for its OS-dependence) -- just enough of the PDF object
 * model, written directly, to lay out plain monospaced text across one or more pages using the
 * standard, always-available Helvetica base font (no font embedding needed).
 *
 * THE SECURITY-CRITICAL CLASS OF THIS ITEM (the {@code HtmlReport} precedent): a finding's
 * subject arrives verbatim from crt.sh, a public log anyone can write into, so every
 * interpolated value passes through {@link #sanitize} and {@link #escape} before it is ever
 * placed inside a PDF literal string -- an un-escaped {@code )} or {@code \} there would let a
 * crafted subject inject new content-stream operators.
 */
final class PdfReport {

    private static final int PAGE_WIDTH = 612;   // US Letter, points
    private static final int PAGE_HEIGHT = 792;
    private static final int LEFT_MARGIN = 40;
    private static final int TOP_START = 750;
    private static final int LINE_HEIGHT = 14;
    private static final int FONT_SIZE = 10;
    private static final int LINES_PER_PAGE = 45;

    private PdfReport() {
    }

    /** A complete, standalone PDF document. Deterministic: same model -&gt; same bytes. */
    static byte[] render(ReportModel model) {
        Objects.requireNonNull(model, "model");
        List<List<String>> pages = paginate(buildLines(model));
        return write(pages);
    }

    private static List<String> buildLines(ReportModel model) {
        List<String> lines = new ArrayList<>();
        lines.add("argus attack surface report");
        lines.add("target: " + model.target());
        lines.add("scan id: " + model.scanId());
        lines.add("started: " + model.startedAt());
        lines.add("finished: "
                + (model.finishedAt().isEmpty() ? "(not finished)" : model.finishedAt()));
        lines.add("summary: " + model.summaryLine());
        lines.add("");
        lines.add(String.format("%-10s %-40s %-8s %-10s", "type", "subject", "port", "state"));
        for (FindingRow row : model.rows()) {
            lines.add(String.format("%-10s %-40s %-8s %-10s",
                    row.type(), row.subject(), row.port(), row.state()));
        }
        if (model.rows().isEmpty()) {
            lines.add("(no findings)");
        }
        return lines;
    }

    private static List<List<String>> paginate(List<String> lines) {
        List<List<String>> pages = new ArrayList<>();
        for (int i = 0; i < lines.size(); i += LINES_PER_PAGE) {
            pages.add(lines.subList(i, Math.min(i + LINES_PER_PAGE, lines.size())));
        }
        if (pages.isEmpty()) {
            pages.add(List.of());
        }
        return pages;
    }

    /**
     * Object numbering is fixed and computed, never reserved-then-backfilled: 1=Catalog,
     * 2=Pages, 3=Font, then for page {@code i} (0-based): content stream = {@code 4 + 2*i},
     * page = {@code 5 + 2*i}. Knowing the page count up front (from {@code pages.size()})
     * before any byte is written is what makes this safe.
     */
    private static byte[] write(List<List<String>> pages) {
        int pageCount = pages.size();
        int catalogNum = 1;
        int pagesNum = 2;
        int fontNum = 3;
        int totalObjects = 3 + 2 * pageCount;

        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < pageCount; i++) {
            if (i > 0) {
                kids.append(' ');
            }
            kids.append(5 + 2 * i).append(" 0 R");
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int[] offsets = new int[totalObjects + 1]; // index 0 unused (the free-list head)
        writeAscii(out, "%PDF-1.4\n");

        offsets[catalogNum] = out.size();
        writeAscii(out, catalogNum + " 0 obj\n<< /Type /Catalog /Pages "
                + pagesNum + " 0 R >>\nendobj\n");

        offsets[pagesNum] = out.size();
        writeAscii(out, pagesNum + " 0 obj\n<< /Type /Pages /Kids [" + kids + "] /Count "
                + pageCount + " >>\nendobj\n");

        offsets[fontNum] = out.size();
        writeAscii(out, fontNum
                + " 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n");

        for (int i = 0; i < pageCount; i++) {
            int contentNum = 4 + 2 * i;
            int pageNum = 5 + 2 * i;
            byte[] stream = buildContentStream(pages.get(i));

            offsets[contentNum] = out.size();
            writeAscii(out, contentNum + " 0 obj\n<< /Length " + stream.length + " >>\nstream\n");
            out.writeBytes(stream);
            writeAscii(out, "\nendstream\nendobj\n");

            offsets[pageNum] = out.size();
            writeAscii(out, pageNum + " 0 obj\n<< /Type /Page /Parent " + pagesNum
                    + " 0 R /Resources << /Font << /F1 " + fontNum + " 0 R >> >> /MediaBox "
                    + "[0 0 " + PAGE_WIDTH + " " + PAGE_HEIGHT + "] /Contents " + contentNum
                    + " 0 R >>\nendobj\n");
        }

        int xrefOffset = out.size();
        writeAscii(out, "xref\n0 " + (totalObjects + 1) + "\n");
        writeAscii(out, String.format("%010d %05d %s \n", 0, 65535, "f"));
        for (int objNum = 1; objNum <= totalObjects; objNum++) {
            writeAscii(out, String.format("%010d %05d %s \n", offsets[objNum], 0, "n"));
        }
        writeAscii(out, "trailer\n<< /Size " + (totalObjects + 1) + " /Root " + catalogNum
                + " 0 R >>\nstartxref\n" + xrefOffset + "\n%%EOF");

        return out.toByteArray();
    }

    private static byte[] buildContentStream(List<String> lines) {
        StringBuilder content = new StringBuilder();
        content.append("BT\n/F1 ").append(FONT_SIZE).append(" Tf\n")
                .append(LEFT_MARGIN).append(' ').append(TOP_START).append(" Td\n");
        boolean first = true;
        for (String line : lines) {
            if (!first) {
                content.append("0 -").append(LINE_HEIGHT).append(" Td\n");
            }
            content.append('(').append(escape(sanitize(line))).append(") Tj\n");
            first = false;
        }
        content.append("ET");
        return content.toString().getBytes(StandardCharsets.US_ASCII);
    }

    /** Every character outside printable ASCII becomes {@code ?}; line-breaking whitespace
     *  becomes a plain space, so one source line always renders as one {@code Tj} line. */
    private static String sanitize(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') {
                sb.append(' ');
            } else if (c >= 32 && c <= 126) {
                sb.append(c);
            } else {
                sb.append('?');
            }
        }
        return sb.toString();
    }

    /** PDF literal-string escaping: {@code \}, {@code (}, {@code )} each become a backslash
     *  pair. Applied AFTER {@link #sanitize}, so the input here is already pure printable
     *  ASCII. */
    private static String escape(String sanitized) {
        StringBuilder sb = new StringBuilder(sanitized.length());
        for (int i = 0; i < sanitized.length(); i++) {
            char c = sanitized.charAt(i);
            if (c == '\\' || c == '(' || c == ')') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static void writeAscii(ByteArrayOutputStream out, String ascii) {
        out.writeBytes(ascii.getBytes(StandardCharsets.US_ASCII));
    }
}
