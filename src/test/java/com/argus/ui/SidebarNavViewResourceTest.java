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
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** Parses {@code sidebar-nav.fxml} as plain XML -- never {@code FXMLLoader}, never Glass/Prism.
 *  The {@code KeyVaultViewResourceTest} precedent, applied to the new reusable sidebar
 *  component (Part 1 of the UI redesign). */
class SidebarNavViewResourceTest {

    private static final String CLASSPATH_LOCATION = "/com/argus/ui/sidebar-nav.fxml";

    private static Document document;

    @BeforeAll
    static void parseSidebarNavView() throws Exception {
        try (InputStream in = SidebarNavViewResourceTest.class
                .getResourceAsStream(CLASSPATH_LOCATION)) {
            assertNotNull(in, "sidebar-nav.fxml not packaged at " + CLASSPATH_LOCATION);
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            document = builder.parse(new ByteArrayInputStream(in.readAllBytes()));
        }
    }

    @Test
    void sidebarNavViewIsOnTheClasspath() {
        assertNotNull(SidebarNavViewResourceTest.class.getResource(CLASSPATH_LOCATION));
    }

    @Test
    void fxControllerNamesAnExistingClass() throws ClassNotFoundException {
        String controllerAttr = document.getDocumentElement().getAttribute("fx:controller");
        assertTrue(!controllerAttr.isEmpty());
        assertEquals(SidebarNavController.class, Class.forName(controllerAttr));
    }

    @Test
    void everyFxIdHasAMatchingAnnotatedField() {
        Set<String> fxIds = collectFxIds(document.getDocumentElement());
        assertTrue(!fxIds.isEmpty());
        for (String fxId : fxIds) {
            Field field = findField(SidebarNavController.class, fxId);
            assertNotNull(field, "SidebarNavController is missing a field for fx:id=\"" + fxId + "\"");
            assertNotNull(field.getAnnotation(javafx.fxml.FXML.class));
        }
    }

    @Test
    void theControllerExposesTheFxmlHandlersTheViewReferences() {
        Set<String> handlerNames = collectOnMouseClickedHandlers(document.getDocumentElement());
        assertTrue(!handlerNames.isEmpty());
        for (String handlerName : handlerNames) {
            Method method = findMethod(SidebarNavController.class, handlerName);
            assertNotNull(method,
                    "SidebarNavController is missing a handler method '" + handlerName + "'");
            assertNotNull(method.getAnnotation(javafx.fxml.FXML.class));
        }
    }

    @Test
    void everyNavigationTargetFromTheExistingDashboardToolbarHasASidebarItem() {
        Set<String> fxIds = collectFxIds(document.getDocumentElement());
        for (String expected : Set.of("findingsItem", "diffItem", "chartsItem", "graphItem",
                "timelineItem", "scheduledScansItem", "reportItem", "notificationsItem",
                "apiKeysItem", "dashboardItem")) {
            assertTrue(fxIds.contains(expected),
                    "sidebar-nav.fxml is missing the existing navigation target: " + expected);
        }
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

    private static Set<String> collectOnMouseClickedHandlers(Node node) {
        Set<String> handlers = new HashSet<>();
        collectOnMouseClickedHandlers(node, handlers);
        return handlers;
    }

    private static void collectOnMouseClickedHandlers(Node node, Set<String> handlers) {
        if (node instanceof Element element) {
            String onClick = element.getAttribute("onMouseClicked");
            if (onClick.startsWith("#")) {
                handlers.add(onClick.substring(1));
            }
        }
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            collectOnMouseClickedHandlers(children.item(i), handlers);
        }
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
