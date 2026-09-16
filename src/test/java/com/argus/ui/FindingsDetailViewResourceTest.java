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
 * Plan §6.6 V1-V5: parses {@code findings-detail-view.fxml} as plain XML -- never calls
 * {@code FXMLLoader}, never constructs a {@code Node}, never starts Glass or Prism. The
 * {@code ScanDiffViewResourceTest} twin.
 */
class FindingsDetailViewResourceTest {

    private static final String CLASSPATH_LOCATION = "/com/argus/ui/findings-detail-view.fxml";

    private static Document document;

    @BeforeAll
    static void parseFindingsDetailView() throws Exception {
        try (InputStream in =
                FindingsDetailViewResourceTest.class.getResourceAsStream(CLASSPATH_LOCATION)) {
            assertNotNull(in, "findings-detail-view.fxml not packaged at " + CLASSPATH_LOCATION);
            byte[] bytes = in.readAllBytes();

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            document = builder.parse(new ByteArrayInputStream(bytes));
        }
    }

    @Test
    void v1FindingsDetailViewIsOnTheClasspathAndNamesTheController() throws ClassNotFoundException {
        assertNotNull(FindingsDetailViewResourceTest.class.getResource(CLASSPATH_LOCATION));
        Element root = document.getDocumentElement();
        String controllerAttr = root.getAttribute("fx:controller");
        assertTrue(!controllerAttr.isEmpty(), "fx:controller attribute must be present");
        assertEquals(FindingsDetailController.class, Class.forName(controllerAttr));
    }

    @Test
    void v2EveryFxIdHasAMatchingAnnotatedFieldBothDirections() {
        Set<String> fxIds = collectFxIds(document.getDocumentElement());
        assertTrue(!fxIds.isEmpty(), "expected at least one fx:id in findings-detail-view.fxml");

        for (String fxId : fxIds) {
            Field field = findField(FindingsDetailController.class, fxId);
            assertNotNull(field,
                    "FindingsDetailController is missing a field for fx:id=\"" + fxId + "\"");
            assertNotNull(field.getAnnotation(javafx.fxml.FXML.class),
                    "field '" + fxId + "' must be annotated @FXML");
        }

        for (Field field : FindingsDetailController.class.getDeclaredFields()) {
            if (field.getAnnotation(javafx.fxml.FXML.class) != null) {
                assertTrue(fxIds.contains(field.getName()),
                        "FindingsDetailController field '" + field.getName()
                                + "' is @FXML but has no matching fx:id in the FXML");
            }
        }
    }

    @Test
    void v3EveryOnActionHandlerExistsAsAnFxmlAnnotatedMethod() {
        Set<String> handlerNames = collectOnActionHandlers(document.getDocumentElement());
        assertTrue(!handlerNames.isEmpty(), "expected at least one onAction handler");

        for (String handlerName : handlerNames) {
            Method method = findMethod(FindingsDetailController.class, handlerName);
            assertNotNull(method,
                    "FindingsDetailController is missing a handler method '" + handlerName + "'");
            assertNotNull(method.getAnnotation(javafx.fxml.FXML.class),
                    "handler '" + handlerName + "' must be annotated @FXML");
        }
    }

    @Test
    void v4EveryStyleClassUsedByTheViewExistsInTheme() throws Exception {
        Set<String> styleClasses = collectStyleClasses(document.getDocumentElement());
        assertTrue(!styleClasses.isEmpty(),
                "expected at least one styleClass in findings-detail-view.fxml");

        String css;
        try (InputStream in = FindingsDetailViewResourceTest.class
                .getResourceAsStream("/com/argus/ui/theme.css")) {
            assertNotNull(in, "theme.css not on the classpath");
            css = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        for (String styleClass : styleClasses) {
            Pattern selector = Pattern.compile("\\." + Pattern.quote(styleClass) + "\\b");
            assertTrue(selector.matcher(css).find(),
                    "theme.css has no ." + styleClass
                            + " selector used by findings-detail-view.fxml");
        }
    }

    @Test
    void v5NoteFieldDeclaresWrapTextAndPrefHeightEqualToTheControllersCollapsedConstant()
            throws Exception {
        Element noteField = findElementWithFxId(document.getDocumentElement(), "noteField");
        assertNotNull(noteField, "no element with fx:id=\"noteField\" found");
        assertEquals("TextArea", noteField.getTagName());
        assertEquals("true", noteField.getAttribute("wrapText"));
        assertEquals("-Infinity", noteField.getAttribute("minHeight"));
        assertEquals("-Infinity", noteField.getAttribute("maxHeight"));

        double fxmlPrefHeight = Double.parseDouble(noteField.getAttribute("prefHeight"));
        Field collapsed = FindingsDetailController.class.getDeclaredField("COLLAPSED_HEIGHT");
        collapsed.setAccessible(true);
        double collapsedHeight = collapsed.getDouble(null);
        assertEquals(collapsedHeight, fxmlPrefHeight, 0.0);
    }

    @Test
    void findingsTableIsATableViewWithExactlyFourColumns() {
        Element tableElement = findElementWithFxId(document.getDocumentElement(), "findingsTable");
        assertNotNull(tableElement, "no element with fx:id=\"findingsTable\" found");
        assertEquals("TableView", tableElement.getTagName());
        assertEquals(4, countDescendantsByTag(tableElement, "TableColumn"));
    }

    @Test
    void theViewDeclaresNoStylesheetsElement() {
        NodeList stylesheets = document.getElementsByTagName("stylesheets");
        assertEquals(0, stylesheets.getLength(),
                "findings-detail-view.fxml must not declare its own <stylesheets> -- "
                        + "Theme.applyTo(scene) is the single attachment point");
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
