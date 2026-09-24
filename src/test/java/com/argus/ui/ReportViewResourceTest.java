package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Section 7.4 (V1-V5): parses {@code report-view.fxml} as plain XML -- never {@code FXMLLoader},
 * never a {@code Node}, never Glass/Prism. The {@code TimelineViewResourceTest} twin.
 */
class ReportViewResourceTest {

    private static final String CLASSPATH_LOCATION = "/com/argus/ui/report-view.fxml";
    private static final String SIDEBAR_CLASSPATH_LOCATION = "/com/argus/ui/sidebar-nav.fxml";

    private static Document document;
    private static Document sidebarDocument;

    @BeforeAll
    static void parseReportView() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();

        try (InputStream in = ReportViewResourceTest.class.getResourceAsStream(CLASSPATH_LOCATION)) {
            assertNotNull(in, "report-view.fxml not packaged at " + CLASSPATH_LOCATION);
            document = builder.parse(new ByteArrayInputStream(in.readAllBytes()));
        }
        try (InputStream in = ReportViewResourceTest.class
                .getResourceAsStream(SIDEBAR_CLASSPATH_LOCATION)) {
            assertNotNull(in, "sidebar-nav.fxml not packaged at " + SIDEBAR_CLASSPATH_LOCATION);
            sidebarDocument = builder.parse(new ByteArrayInputStream(in.readAllBytes()));
        }
    }

    @Test
    void v1ReportViewIsOnTheClasspathAndNonEmpty() throws Exception {
        assertNotNull(ReportViewResourceTest.class.getResource(CLASSPATH_LOCATION),
                "report-view.fxml must be on the classpath at " + CLASSPATH_LOCATION);
        try (InputStream in = ReportViewResourceTest.class.getResourceAsStream(CLASSPATH_LOCATION)) {
            assertTrue(in.readAllBytes().length > 0, "report-view.fxml must not be empty");
        }
    }

    @Test
    void v2FxControllerNamesReportController() throws ClassNotFoundException {
        Element root = document.getDocumentElement();
        String controllerAttr = root.getAttribute("fx:controller");
        assertTrue(!controllerAttr.isEmpty(), "fx:controller attribute must be present");

        Class<?> controllerClass = Class.forName(controllerAttr);
        assertEquals(ReportController.class, controllerClass);
    }

    @Test
    void v3EveryFxIdHasAMatchingAnnotatedField() {
        Set<String> fxIds = collectFxIds(document.getDocumentElement());
        assertTrue(!fxIds.isEmpty(), "expected at least one fx:id in report-view.fxml");

        for (String fxId : fxIds) {
            Field field = findField(ReportController.class, fxId);
            assertNotNull(field, "ReportController is missing a field for fx:id=\"" + fxId + "\"");
            assertNotNull(field.getAnnotation(javafx.fxml.FXML.class),
                    "field '" + fxId + "' must be annotated @FXML");
        }
    }

    @Test
    void theControllerExposesTheFxmlHandlersTheViewReferences() {
        Set<String> handlerNames = collectOnActionHandlers(document.getDocumentElement());
        assertTrue(!handlerNames.isEmpty(), "expected at least one onAction handler");

        for (String handlerName : handlerNames) {
            Method method = findMethod(ReportController.class, handlerName);
            assertNotNull(method,
                    "ReportController is missing a handler method '" + handlerName + "'");
            assertNotNull(method.getAnnotation(javafx.fxml.FXML.class),
                    "handler '" + handlerName + "' must be annotated @FXML");
        }
        assertNotNull(findMethod(ReportController.class, "onPreview"));
        assertNotNull(findMethod(ReportController.class, "onExport"));
        assertNotNull(findMethod(ReportController.class, "onBack"));
    }

    @Test
    void v4TheTwoPermanentNotesArePresentVerbatim() {
        Element previewNote =
                findElementWithFxId(document.getDocumentElement(), "previewNoteLabel");
        assertNotNull(previewNote, "no element with fx:id=\"previewNoteLabel\" found");
        assertEquals(
                "preview shows the report's content; the exported file is a standalone HTML "
                        + "document",
                previewNote.getAttribute("text"));

        Element exportHint = findElementWithFxId(document.getDocumentElement(), "exportHintLabel");
        assertNotNull(exportHint, "no element with fx:id=\"exportHintLabel\" found");
        String exportHintText = exportHint.getAttribute("text");
        assertTrue(exportHintText.contains(
                "both formats are written unencrypted to the location you choose"),
                "P3-18 makes PDF export real, so the old browser-print-to-PDF hint is stale");
    }

    @Test
    void v5ExportButtonIsDeclaredDisabledInTheFxml() {
        Element exportButton = findElementWithFxId(document.getDocumentElement(), "exportButton");
        assertNotNull(exportButton, "no element with fx:id=\"exportButton\" found");
        assertEquals("true", exportButton.getAttribute("disable"),
                "exportButton must be declared disable=\"true\" -- preview before export is "
                        + "structural, not just behavioural");
    }

    @Test
    void findingsTableIsATableViewWithFourColumns() {
        Element table = findElementWithFxId(document.getDocumentElement(), "findingsTable");
        assertNotNull(table, "no element with fx:id=\"findingsTable\" found");
        assertEquals("TableView", table.getTagName());

        int columnCount = countDescendantsByTag(table, "TableColumn");
        assertEquals(4, columnCount, "expected 4 TableColumn children");

        Element placeholder = findChildByTag(table, "placeholder");
        assertNotNull(placeholder, "findingsTable must declare a <placeholder>");
    }

    @Test
    void everyStyleClassUsedByTheViewExistsInTheme() throws Exception {
        Set<String> styleClasses = collectStyleClasses(document.getDocumentElement());
        assertTrue(!styleClasses.isEmpty(), "expected at least one styleClass in report-view.fxml");

        String css;
        try (InputStream in =
                ReportViewResourceTest.class.getResourceAsStream("/com/argus/ui/theme.css")) {
            assertNotNull(in, "theme.css not on the classpath");
            css = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        for (String styleClass : styleClasses) {
            Pattern selector = Pattern.compile("\\." + Pattern.quote(styleClass) + "\\b");
            assertTrue(selector.matcher(css).find(),
                    "theme.css has no ." + styleClass + " selector used by report-view.fxml");
        }
    }

    @Test
    void theViewDeclaresNoStylesheetsElement() {
        NodeList stylesheets = document.getElementsByTagName("stylesheets");
        assertEquals(0, stylesheets.getLength(),
                "report-view.fxml must not declare its own <stylesheets> -- "
                        + "Theme.applyTo(scene) is the single attachment point");
    }

    @Test
    void theViewNeverUsesTheWordSeverity() {
        assertTrue(!textContentLowercase().contains("severity"),
                "report-view.fxml must not contain the word \"severity\" anywhere");
    }

    /**
     * P1 UI redesign: the dashboard's per-screen toolbar buttons were replaced by the shared
     * sidebar ({@code sidebar-nav.fxml}), which now owns the single navigation entry point to
     * every screen, including this one -- {@code DashboardController.setOpenReportHandler}
     * still exists unchanged and forwards to the sidebar's own {@code reportItem}.
     */
    @Test
    void sidebarNavViewDeclaresAReportItemWiredToOnReport() {
        Element item = findElementWithFxId(sidebarDocument.getDocumentElement(), "reportItem");
        assertNotNull(item, "no element with fx:id=\"reportItem\" found in sidebar-nav.fxml");
        assertEquals("HBox", item.getTagName());
        assertEquals("#onReport", item.getAttribute("onMouseClicked"));
    }

    private String textContentLowercase() {
        return document.getDocumentElement().getTextContent().toLowerCase(java.util.Locale.ROOT)
                + attributeTextLowercase(document.getDocumentElement());
    }

    private String attributeTextLowercase(Node node) {
        StringBuilder builder = new StringBuilder();
        if (node instanceof Element element) {
            org.w3c.dom.NamedNodeMap attributes = element.getAttributes();
            for (int i = 0; i < attributes.getLength(); i++) {
                builder.append(attributes.item(i).getNodeValue().toLowerCase(java.util.Locale.ROOT))
                        .append(' ');
            }
        }
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            builder.append(attributeTextLowercase(children.item(i)));
        }
        return builder.toString();
    }

    private static Element findChildByTag(Element parent, String tagName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element element && element.getTagName().equals(tagName)) {
                return element;
            }
        }
        return null;
    }

    private static int countDescendantsByTag(Node node, String tagName) {
        int count = 0;
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element element) {
                if (element.getTagName().equals(tagName)) {
                    count++;
                }
                count += countDescendantsByTag(child, tagName);
            }
        }
        return count;
    }

    private static Set<String> collectFxIds(Node node) {
        Set<String> ids = new HashSet<>();
        collectFxIds(node, ids);
        return ids;
    }

    private static void collectFxIds(Node node, Set<String> ids) {
        if (node instanceof Element element) {
            String fxId = element.getAttribute("fx:id");
            if (!fxId.isEmpty()) {
                ids.add(fxId);
            }
        }
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            collectFxIds(children.item(i), ids);
        }
    }

    private static Set<String> collectOnActionHandlers(Node node) {
        Set<String> handlers = new HashSet<>();
        collectOnActionHandlers(node, handlers);
        return handlers;
    }

    private static void collectOnActionHandlers(Node node, Set<String> handlers) {
        if (node instanceof Element element) {
            String onAction = element.getAttribute("onAction");
            if (onAction.startsWith("#")) {
                handlers.add(onAction.substring(1));
            }
        }
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            collectOnActionHandlers(children.item(i), handlers);
        }
    }

    private static Set<String> collectStyleClasses(Node node) {
        Set<String> classes = new HashSet<>();
        collectStyleClasses(node, classes);
        return classes;
    }

    private static void collectStyleClasses(Node node, Set<String> classes) {
        if (node instanceof Element element) {
            String styleClass = element.getAttribute("styleClass");
            if (!styleClass.isEmpty()) {
                for (String token : styleClass.trim().split("\\s+")) {
                    if (!token.isEmpty()) {
                        classes.add(token);
                    }
                }
            }
        }
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            collectStyleClasses(children.item(i), classes);
        }
    }

    private static Element findElementWithFxId(Node node, String fxId) {
        if (node instanceof Element element && fxId.equals(element.getAttribute("fx:id"))) {
            return element;
        }
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Element found = findElementWithFxId(children.item(i), fxId);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static Field findField(Class<?> type, String name) {
        for (Field field : type.getDeclaredFields()) {
            if (field.getName().equals(name)) {
                return field;
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> type, String name) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                return method;
            }
        }
        return null;
    }
}
