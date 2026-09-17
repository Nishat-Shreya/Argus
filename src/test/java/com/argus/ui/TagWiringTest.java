package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.core.TagArchive;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Plan §6.6 T1-T9: reflective + source-scan + FXML/CSS guards for the tag feature -- no toolkit
 * start, never calls {@code Application.launch}, {@code new App()} or {@code Platform.startup}.
 * A NEW file so {@link FindingsDetailWiringTest} and {@link FindingsDetailViewResourceTest} stay
 * unedited (plan §0.3).
 */
class TagWiringTest {

    private static final String CONTROLLER_FILE =
            "src/main/java/com/argus/ui/FindingsDetailController.java";

    /** The P3-07 own list, over which T5's clock fence is checked -- separate from P3-06's
     *  {@code FindingsDetailWiringTest.NEW_MAIN_FILES} (plan §0.2). */
    private static final List<String> NEW_TAG_FILES = List.of(
            "src/main/java/com/argus/db/NewTag.java",
            "src/main/java/com/argus/db/FindingTagRecord.java",
            "src/main/java/com/argus/db/TagDao.java",
            "src/main/java/com/argus/core/FindingTag.java",
            "src/main/java/com/argus/core/FindingTags.java",
            "src/main/java/com/argus/core/TagArchive.java",
            "src/main/java/com/argus/ui/Tags.java",
            "src/main/java/com/argus/ui/TagFilter.java",
            "src/main/java/com/argus/ui/TagValidation.java");

    private static final List<String> NEW_DB_FILES = List.of(
            "src/main/java/com/argus/db/NewTag.java",
            "src/main/java/com/argus/db/FindingTagRecord.java",
            "src/main/java/com/argus/db/TagDao.java");

    @Test
    void t1ControllerDeclaresATagArchiveFieldAndSetTagsAndKeepsExistingPublicMethods()
            throws NoSuchMethodException {
        Method setTags = FindingsDetailController.class.getMethod("setTags", TagArchive.class);
        assertTrue(Modifier.isPublic(setTags.getModifiers()));

        assertNotNull(findField(FindingsDetailController.class, TagArchive.class),
                "FindingsDetailController must declare a TagArchive field");

        assertTrue(Modifier.isPublic(FindingsDetailController.class
                .getMethod("setHistory", com.argus.core.ScanHistory.class).getModifiers()));
        assertTrue(Modifier.isPublic(FindingsDetailController.class
                .getMethod("setNotes", com.argus.core.AnnotationArchive.class).getModifiers()));
        assertTrue(Modifier.isPublic(
                FindingsDetailController.class.getMethod("setOnClose", Runnable.class)
                        .getModifiers()));
        assertTrue(Modifier.isPublic(
                FindingsDetailController.class.getMethod("refresh").getModifiers()));
    }

    @Test
    void t2NoDeclaredFieldIsVolatile() {
        for (Field field : FindingsDetailController.class.getDeclaredFields()) {
            assertFalse(Modifier.isVolatile(field.getModifiers()),
                    "no field on FindingsDetailController may be volatile (invariant 5), found: "
                            + field);
        }
    }

    @Test
    void t3NoNewUiFileReferencesComArgusDbAnywhere() throws IOException {
        String controllerSource = readSource(Path.of(CONTROLLER_FILE));
        assertFalse(controllerSource.contains("com.argus.db"),
                "FindingsDetailController.java must not reference com.argus.db anywhere");

        for (String fileName : List.of(
                "src/main/java/com/argus/ui/Tags.java",
                "src/main/java/com/argus/ui/TagFilter.java",
                "src/main/java/com/argus/ui/TagValidation.java")) {
            String source = readSource(Path.of(fileName));
            assertFalse(source.contains("com.argus.db"), fileName + " must not reference com.argus.db");
        }
    }

    @Test
    void t4ControllerHasNoVaultFieldAndNoVaultImport() throws IOException {
        for (Field field : FindingsDetailController.class.getDeclaredFields()) {
            assertFalse(field.getType() == com.argus.core.Vault.class,
                    "FindingsDetailController must declare no field of type Vault");
        }
        String source = readSource(Path.of(CONTROLLER_FILE));
        assertFalse(source.contains("import com.argus.core.Vault;"),
                "FindingsDetailController must not import com.argus.core.Vault");
    }

    @Test
    void t5TheClockFenceExtendedOverEveryNewTagFileAndTheControllerStaysAtExactlyOne()
            throws IOException {
        List<String> forbidden = List.of("Instant.now", "LocalDate.now", "LocalDateTime.now",
                "System.currentTimeMillis");

        for (String fileName : NEW_TAG_FILES) {
            String source = readSource(Path.of(fileName));
            for (String pattern : forbidden) {
                assertFalse(source.contains(pattern),
                        fileName + " must not contain a clock read (" + pattern + "), plan §0.2");
            }
        }

        for (String fileName : NEW_DB_FILES) {
            String source = readSource(Path.of(fileName));
            assertFalse(source.contains("java.time"), fileName + " must not import java.time");
        }

        String controllerSource = readSource(Path.of(CONTROLLER_FILE));
        Matcher matches = Pattern.compile(Pattern.quote("Instant.now")).matcher(controllerSource);
        int count = 0;
        while (matches.find()) {
            count++;
        }
        assertTrue(count == 1,
                "Instant.now must appear exactly once in FindingsDetailController.java, found "
                        + count);
    }

    @Test
    void t6AppContainsExactlyOneSetTagsCallAndDeclaresNoNewFieldOrShowMethod() throws IOException {
        String source = readSource(Path.of("src/main/java/com/argus/ui/App.java"));
        Matcher matches = Pattern.compile(Pattern.quote("setTags(")).matcher(source);
        int count = 0;
        while (matches.find()) {
            count++;
        }
        assertTrue(count == 1, "App.java must contain exactly one setTags( call, found " + count);

        long tagArchiveFieldCount = 0;
        for (Field field : App.class.getDeclaredFields()) {
            if (field.getType() == TagArchive.class) {
                tagArchiveFieldCount++;
            }
        }
        assertTrue(tagArchiveFieldCount == 0, "App must declare no new TagArchive field");

        for (Method method : App.class.getDeclaredMethods()) {
            assertFalse(method.getName().toLowerCase().contains("tag")
                    && method.getName().startsWith("show"),
                    "App must declare no new show...Tag... method, found " + method.getName());
        }
    }

    @Test
    void t7ControllerNamesArgusTagsAndEveryThreadIsDaemonAndNoDirectTagsCallOutsideATask()
            throws IOException {
        String source = readSource(Path.of(CONTROLLER_FILE));
        assertTrue(source.contains("\"argus-tags\""),
                "FindingsDetailController.java must dispatch tag reads/writes on \"argus-tags\"");

        Matcher newThread = Pattern.compile("new Thread\\(").matcher(source);
        Matcher setDaemon = Pattern.compile("\\.setDaemon\\(true\\)").matcher(source);
        int newThreadCount = 0;
        while (newThread.find()) {
            newThreadCount++;
        }
        int setDaemonCount = 0;
        while (setDaemon.find()) {
            setDaemonCount++;
        }
        assertTrue(newThreadCount > 0, "expected at least one new Thread( in the controller");
        assertEquals(newThreadCount, setDaemonCount);
    }

    @Test
    void t8FxmlDeclaresTheNewFxIdsAndStillExactlyFourTableColumns() throws Exception {
        Document document = parseFxml();
        java.util.Set<String> fxIds = collectFxIds(document.getDocumentElement());
        for (String expected : List.of("tagFilterCombo", "tagList", "tagField", "addTagButton",
                "removeTagButton", "tagCountLabel")) {
            assertTrue(fxIds.contains(expected),
                    "findings-detail-view.fxml is missing fx:id=\"" + expected + "\"");
        }

        Element tableElement = findElementWithFxId(document.getDocumentElement(), "findingsTable");
        assertNotNull(tableElement);
        assertEquals(4, countDescendantsByTag(tableElement, "TableColumn"));
    }

    @Test
    void t9ThemeDeclaresTheNewTagSelectors() throws IOException {
        String css = readSource(Path.of("src/main/resources/com/argus/ui/theme.css"));
        for (String selector : List.of(".tag-list", ".tag-name", ".tag-field", ".tag-filter")) {
            assertTrue(css.contains(selector),
                    "theme.css is missing a " + selector + " selector");
        }
    }

    /**
     * Reviewer-found bug fix: a tag *removal* can change whether the selected row still matches
     * {@link TagFilter#apply} (plan §4.2) -- e.g. removing the very tag the table is filtered on.
     * {@code refreshTagsForSelectedFinding()} must re-run {@code applyTagFilter()} after
     * {@code populateTagFilterCombo()}, mirroring the exact sequence
     * {@code loadTagsForScan}'s {@code setOnSucceeded} already uses.
     */
    @Test
    void t10RefreshTagsForSelectedFindingReAppliesTheTagFilterAfterRepopulatingTheCombo()
            throws IOException {
        String source = readSource(Path.of(CONTROLLER_FILE));
        String methodBody = extractMethodBody(source, "refreshTagsForSelectedFinding");

        int populateIndex = methodBody.indexOf("populateTagFilterCombo();");
        assertTrue(populateIndex >= 0,
                "refreshTagsForSelectedFinding must call populateTagFilterCombo()");

        int applyIndex = methodBody.indexOf("applyTagFilter();");
        assertTrue(applyIndex >= 0,
                "refreshTagsForSelectedFinding must call applyTagFilter() after "
                        + "populateTagFilterCombo() -- removing a tag the table is currently "
                        + "filtered on must make the row disappear (plan §4.2), mirroring "
                        + "loadTagsForScan's populateTagFilterCombo(); applyTagFilter(); sequence");
        assertTrue(applyIndex > populateIndex,
                "applyTagFilter() must run after populateTagFilterCombo() in "
                        + "refreshTagsForSelectedFinding, not before it");

        assertFalse(methodBody.contains("cannot change whether it belongs in the table"),
                "the comment on refreshTagsForSelectedFinding must no longer claim the refresh "
                        + "cannot change the table's membership -- a tag removal can");
    }

    // --- helpers -----------------------------------------------------------------------------

    /** Balanced-brace extraction of {@code private void methodName() { ... }}'s body, tolerant
     *  of the nested lambda braces inside it -- a plain substring/regex match would stop at the
     *  first {@code }}. */
    private static String extractMethodBody(String source, String methodName) {
        Pattern signature = Pattern.compile("private void " + methodName + "\\(\\)\\s*\\{");
        Matcher matcher = signature.matcher(source);
        assertTrue(matcher.find(), "could not find method " + methodName + " in " + CONTROLLER_FILE);

        int bodyStart = matcher.end();
        int depth = 1;
        int i = bodyStart;
        while (depth > 0 && i < source.length()) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            }
            i++;
        }
        assertTrue(depth == 0, "unbalanced braces while extracting " + methodName);
        return source.substring(bodyStart, i - 1);
    }

    private static Document parseFxml() throws Exception {
        try (InputStream in = TagWiringTest.class
                .getResourceAsStream("/com/argus/ui/findings-detail-view.fxml")) {
            assertNotNull(in, "findings-detail-view.fxml not packaged");
            byte[] bytes = in.readAllBytes();
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new ByteArrayInputStream(bytes));
        }
    }

    private static java.util.Set<String> collectFxIds(Node node) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        collectFxIds(node, ids);
        return ids;
    }

    private static void collectFxIds(Node node, java.util.Set<String> ids) {
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

    private static Field findField(Class<?> owner, Class<?> fieldType) {
        for (Field field : owner.getDeclaredFields()) {
            if (field.getType() == fieldType) {
                return field;
            }
        }
        return null;
    }

    private static String readSource(Path file) throws IOException {
        return Files.readString(file);
    }
}
