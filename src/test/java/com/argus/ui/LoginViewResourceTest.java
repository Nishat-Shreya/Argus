package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * Section 6.7: parses {@code login-view.fxml} as plain XML -- never calls {@code FXMLLoader},
 * never constructs a {@code Node}, never starts Glass or Prism. Stays inside P0-02's "no
 * toolkit test until P1-06" boundary (plan §7.16).
 */
class LoginViewResourceTest {

    private static final String CLASSPATH_LOCATION = "/com/argus/ui/login-view.fxml";

    private static Document document;
    private static String rawXml;

    @BeforeAll
    static void parseLoginView() throws Exception {
        try (InputStream in =
                LoginViewResourceTest.class.getResourceAsStream(CLASSPATH_LOCATION)) {
            assertNotNull(in, "login-view.fxml not packaged at " + CLASSPATH_LOCATION);
            byte[] bytes = in.readAllBytes();
            rawXml = new String(bytes, StandardCharsets.UTF_8);

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            document = builder.parse(new java.io.ByteArrayInputStream(bytes));
        }
    }

    @Test
    void loginViewIsOnTheClasspath() {
        assertNotNull(LoginViewResourceTest.class.getResource(CLASSPATH_LOCATION),
                "login-view.fxml must be on the classpath at " + CLASSPATH_LOCATION);
    }

    @Test
    void loginViewIsWellFormedXml() {
        assertNotNull(document);
        assertNotNull(document.getDocumentElement());
    }

    @Test
    void fxControllerNamesAnExistingClass() throws ClassNotFoundException {
        Element root = document.getDocumentElement();
        String controllerAttr = root.getAttribute("fx:controller");
        assertTrue(!controllerAttr.isEmpty(), "fx:controller attribute must be present");

        Class<?> controllerClass = Class.forName(controllerAttr);
        assertEquals(LoginController.class, controllerClass);
    }

    @Test
    void everyFxIdHasAMatchingAnnotatedField() {
        Set<String> fxIds = collectFxIds(document.getDocumentElement());
        assertTrue(!fxIds.isEmpty(), "expected at least one fx:id in login-view.fxml");

        for (String fxId : fxIds) {
            Field field = findField(LoginController.class, fxId);
            assertNotNull(field, "LoginController is missing a field for fx:id=\"" + fxId + "\"");
            assertNotNull(field.getAnnotation(javafx.fxml.FXML.class),
                    "field '" + fxId + "' must be annotated @FXML");
        }
    }

    @Test
    void theControllerExposesTheFxmlHandlersTheViewReferences() {
        Set<String> handlerNames = collectOnActionHandlers(document.getDocumentElement());
        assertTrue(!handlerNames.isEmpty(), "expected at least one onAction handler");

        for (String handlerName : handlerNames) {
            Method method = findMethod(LoginController.class, handlerName);
            assertNotNull(method,
                    "LoginController is missing a handler method '" + handlerName + "'");
            assertNotNull(method.getAnnotation(javafx.fxml.FXML.class),
                    "handler '" + handlerName + "' must be annotated @FXML");
        }
    }

    @Test
    void passwordFieldIsAPasswordField() {
        Element passwordElement = findElementWithFxId(document.getDocumentElement(),
                "masterPasswordField");
        assertNotNull(passwordElement, "no element with fx:id=\"masterPasswordField\" found");
        assertEquals("PasswordField", passwordElement.getTagName());
    }

    @Test
    void everyStyleClassUsedByTheViewExistsInTheme() throws Exception {
        Set<String> styleClasses = collectStyleClasses(document.getDocumentElement());
        assertTrue(!styleClasses.isEmpty(), "expected at least one styleClass in login-view.fxml");

        String css;
        try (InputStream in = LoginViewResourceTest.class
                .getResourceAsStream("/com/argus/ui/theme.css")) {
            assertNotNull(in, "theme.css not on the classpath");
            css = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        for (String styleClass : styleClasses) {
            Pattern selector = Pattern.compile("\\." + Pattern.quote(styleClass) + "\\b");
            assertTrue(selector.matcher(css).find(),
                    "theme.css has no ." + styleClass + " selector used by login-view.fxml");
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
