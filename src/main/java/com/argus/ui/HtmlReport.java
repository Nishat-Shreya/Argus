package com.argus.ui;

import java.util.List;
import java.util.Objects;

/**
 * Builds a complete, standalone HTML5 document from a {@link ReportModel} (plan §3.3). THE
 * SECURITY-CRITICAL CLASS OF THIS ITEM (plan §5.1): a finding's subject arrives verbatim from
 * crt.sh, a public certificate-transparency log anyone can write into, so every interpolated
 * value -- target, timestamps, summary line, and all four columns of every row -- passes
 * through {@link #escape(String)} without exception. The document is fully self-contained: one
 * inline {@code <style>} block using the theme's hex literals (it cannot reference
 * {@code theme.css}, since a JavaFX stylesheet is not valid CSS for a browser and the document
 * must stand alone), no {@code <script>}, no {@code <link>}, no {@code <img>}, and no
 * {@code http://}/{@code https://} anywhere. Vocabulary tokens (finding type/state) reach the
 * document verbatim from {@code SnapshotRows}' output -- this class hard-codes none of them.
 */
final class HtmlReport {

    private static final String STYLE =
            "body{background:#0d0f0d;color:#c9d6c9;"
            + "font-family:Consolas,'Courier New',monospace;margin:24px;}"
            + "h1{color:#3ddc5f;font-size:20px;}"
            + "dl{display:grid;grid-template-columns:140px auto;gap:4px 12px;max-width:760px;}"
            + "dt{color:#8fa88f;}"
            + "dd{margin:0;color:#c9d6c9;}"
            + "table{border-collapse:collapse;width:100%;margin-top:16px;}"
            + "th,td{border:1px solid #1f2b1f;padding:4px 10px;text-align:left;font-size:13px;}"
            + "th{background:#0a0d0a;color:#8fa88f;}"
            + "tr:nth-child(even) td{background:#111511;}"
            + ".footer{color:#5a6b5a;font-size:11px;margin-top:24px;}";

    private HtmlReport() {
    }

    /** A complete, standalone HTML5 document. Deterministic: same model -&gt; same bytes. */
    static String render(ReportModel model) {
        Objects.requireNonNull(model, "model");

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n");
        html.append("<html>\n<head>\n");
        html.append("<meta charset=\"utf-8\">\n");
        html.append("<title>argus report -- ").append(escape(model.target())).append("</title>\n");
        html.append("<style>").append(STYLE).append("</style>\n");
        html.append("</head>\n<body>\n");
        html.append("<h1>argus attack surface report</h1>\n");

        html.append("<dl>\n");
        appendDefinition(html, "target", model.target());
        appendDefinition(html, "scan id", String.valueOf(model.scanId()));
        appendDefinition(html, "started", model.startedAt());
        appendDefinition(html, "finished",
                model.finishedAt().isEmpty() ? "(not finished)" : model.finishedAt());
        appendDefinition(html, "summary", model.summaryLine());
        html.append("</dl>\n");

        html.append("<table>\n<thead><tr>")
                .append("<th>type</th><th>subject</th><th>port</th><th>state</th>")
                .append("</tr></thead>\n<tbody>\n");
        appendRows(html, model.rows());
        html.append("</tbody>\n</table>\n");

        html.append("<p class=\"footer\">argus is discovery-only: this report lists observed "
                + "ports and names, and assigns no severity or exploitability score.</p>\n");
        html.append("</body>\n</html>\n");
        return html.toString();
    }

    /** Escapes {@code & < > " '} -- applied to EVERY interpolated value without exception. A
     *  single left-to-right pass over the original characters, so the ampersand in the input is
     *  always escaped from the SOURCE text, never re-escaped from already-emitted markup. */
    static String escape(String text) {
        Objects.requireNonNull(text, "text");
        StringBuilder escaped = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&#39;");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }

    private static void appendDefinition(StringBuilder html, String label, String value) {
        html.append("<dt>").append(escape(label)).append("</dt><dd>").append(escape(value))
                .append("</dd>\n");
    }

    private static void appendRows(StringBuilder html, List<FindingRow> rows) {
        for (FindingRow row : rows) {
            html.append("<tr><td>").append(escape(row.type())).append("</td><td>")
                    .append(escape(row.subject())).append("</td><td>")
                    .append(escape(row.port())).append("</td><td>")
                    .append(escape(row.state())).append("</td></tr>\n");
        }
    }
}
