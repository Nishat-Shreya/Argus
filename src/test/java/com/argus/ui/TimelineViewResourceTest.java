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
 * Section 7.4: parses {@code timeline-view.fxml} as plain XML -- never {@code FXMLLoader},
 * never a {@code Node}, never Glass/Prism. The {@code ChartsViewResourceTest} twin.
 */
class TimelineViewResourceTest {

    private static final String CLASSPATH_LOCATION = "/com/argus/ui/timeline-view.fxml";
    private static final String SIDEBAR_CLASSPATH_LOCATION = "/com/argus/ui/sidebar-nav.fxml";

    private static Document document;
    private static Document sidebarDocument;

    @BeforeAll
    static void parseTimelineView() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();

        try (InputStream in = TimelineViewResourceTest.class.getResourceAsStream(CLASSPATH_LOCATION)) {
            assertNotNull(in, "timeline-view.fxml not packaged at " + CLASSPATH_LOCATION);
            document = builder.parse(new ByteArrayInputStream(in.readAllBytes()));
        }
        try (InputStream in = TimelineViewResourceTest.class
                .getResourceAsStream(SIDEBAR_CLASSPATH_LOCATION)) {
            assertNotNull(in, "sidebar-nav.fxml not packaged at " + SIDEBAR_CLASSPATH_LOCATION);
            sidebarDocument = builder.parse(new ByteArrayInputStream(in.readAllBytes()));
        }
    }

    @Test
    void timelineViewIsOnTheClasspath() {
        assertNotNull(TimelineViewResourceTest.class.getResource(CLASSPATH_LOCATION),
                "timeline-view.fxml must be on the classpath at " + CLASSPATH_LOCATION);
    }

    @Test
    void isWellFormedXml() {
        assertNotNull(document);
        assertNotNull(document.getDocumentElement());
    }

    @Test
    void fxControllerNamesTimelineController() throws ClassNotFoundException {
        Element root = document.getDocumentElement();
        String controllerAttr = root.getAttribute("fx:controller");
        assertTrue(!controllerAttr.isEmpty(), "fx:controller attribute must be present");

        Class<?> controllerClass = Class.forName(controllerAttr);
        assertEquals(TimelineController.class, controllerClass);
    }

    @Test
    void everyFxIdHasAMatchingAnnotatedField() {
        Set<String> fxIds = collectFxIds(document.getDocumentElement());
        assertTrue(!fxIds.isEmpty(), "expected at least one fx:id in timeline-view.fxml");

        for (String fxId : fxIds) {
            Field field = findField(TimelineController.class, fxId);
            assertNotNull(field, "TimelineController is missing a field for fx:id=\"" + fxId + "\"");
            assertNotNull(field.getAnnotation(javafx.fxml.FXML.class),
                    "field '" + fxId + "' must be annotated @FXML");
        }
    }

    @Test
    void theControllerExposesTheFxmlHandlersTheViewReferences() {
        Set<String> handlerNames = collectOnActionHandlers(document.getDocumentElement());
        assertTrue(!handlerNames.isEmpty(), "expected at least one onAction handler");

        for (String handlerName : handlerNames) {
            Method method = findMethod(TimelineController.class, handlerName);
            assertNotNull(method,
                    "TimelineController is missing a handler method '" + handlerName + "'");
            assertNotNull(method.getAnnotation(javafx.fxml.FXML.class),
                    "handler '" + handlerName + "' must be annotated @FXML");
        }
        assertNotNull(findMethod(TimelineController.class, "onLoad"));
        assertNotNull(findMethod(TimelineController.class, "onBack"));
    }

    @Test
    void timelineSliderIsASliderWithUnitTicksSnappedAndNoTickLabels() {
        Element slider = findElementWithFxId(document.getDocumentElement(), "timelineSlider");
        assertNotNull(slider, "no element with fx:id=\"timelineSlider\" found");
        assertEquals("Slider", slider.getTagName());
        assertEquals("true", slider.getAttribute("snapToTicks"));
        assertEquals("1", slider.getAttribute("majorTickUnit"));
        assertEquals("0", slider.getAttribute("minorTickCount"));
        assertEquals("false", slider.getAttribute("showTickLabels"));
        assertEquals("1", slider.getAttribute("blockIncrement"));
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
        assertTrue(!styleClasses.isEmpty(), "expected at least one styleClass in timeline-view.fxml");

        String css;
        try (InputStream in =
                TimelineViewResourceTest.class.getResourceAsStream("/com/argus/ui/theme.css")) {
            assertNotNull(in, "theme.css not on the classpath");
            css = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        for (String styleClass : styleClasses) {
            Pattern selector = Pattern.compile("\\." + Pattern.quote(styleClass) + "\\b");
            assertTrue(selector.matcher(css).find(),
                    "theme.css has no ." + styleClass + " selector used by timeline-view.fxml");
        }
    }

    @Test
    void theViewDeclaresNoStylesheetsElement() {
        NodeList stylesheets = document.getElementsByTagName("stylesheets");
        assertEquals(0, stylesheets.getLength(),
                "timeline-view.fxml must not declare its own <stylesheets> -- "
                        + "Theme.applyTo(scene) is the single attachment point");
    }

    @Test
    void theViewNeverUsesTheWordSeverity() {
        assertTrue(!textContentLowercase().contains("severity"),
                "timeline-view.fxml must not contain the word \"severity\" anywhere");
    }

    /**
     * P1 UI redesign: the dashboard's per-screen toolbar buttons were replaced by the shared
     * sidebar ({@code sidebar-nav.fxml}), which now owns the single navigation entry point to
     * every screen, including this one -- {@code DashboardController.setOpenTimelineHandler}
     * still exists unchanged and forwards to the sidebar's own {@code timelineItem}.
     */
    @Test
    void sidebarNavViewDeclaresATimelineItemWiredToOnTimeline() {
        Element item = findElementWithFxId(sidebarDocument.getDocumentElement(), "timelineItem");
        assertNotNull(item, "no element with fx:id=\"timelineItem\" found in sidebar-nav.fxml");
        assertEquals("HBox", item.getTagName());
        assertEquals("#onTimeline", item.getAttribute("onMouseClicked"));
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
