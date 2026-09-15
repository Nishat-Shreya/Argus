package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * P3-05 §6: D1-D9 pin {@link DroppedTargets#parse(String)} -- the single, shared, pure rule for
 * turning any dropped blob (file contents or a text selection) into candidate targets. No
 * {@code javafx.*}, no filesystem here (that is D13-D17, added once {@code read} lands).
 */
class DroppedTargetsTest {

    @TempDir
    Path tempDir;

    @Test
    void d1NewlineSeparatedBlobOfThreeValidDomainsAllAccepted() {
        TargetDrop result = DroppedTargets.parse("a.com\nb.com\nc.com");

        assertEquals(List.of("a.com", "b.com", "c.com"), result.accepted());
        assertTrue(result.rejected().isEmpty());
    }

    @Test
    void d2MixedSeparatorsAreAllTreatedAsOneRule() {
        TargetDrop result = DroppedTargets.parse("a.com, b.com;c.com\n d.com");

        assertEquals(List.of("a.com", "b.com", "c.com", "d.com"), result.accepted());
        assertTrue(result.rejected().isEmpty());
    }

    @Test
    void d3BlankLinesAndSurroundingWhitespaceAreIgnoredNotRejected() {
        TargetDrop result = DroppedTargets.parse("\n\n  a.com  \n\n   \nb.com\n\n");

        assertEquals(List.of("a.com", "b.com"), result.accepted());
        assertTrue(result.rejected().isEmpty());
    }

    @Test
    void d4NormalizationAndDedupUseDomainNameNormalizeAsTheKey() {
        TargetDrop result = DroppedTargets.parse("Example.COM\nexample.com.\nexample.com");

        assertEquals(List.of("example.com"), result.accepted());
        assertTrue(result.rejected().isEmpty());
    }

    @Test
    void d5MalformedTokensAreRejectedAndTheirRawFormIsPreserved() {
        TargetDrop result = DroppedTargets.parse("not a domain!!\n1.2.3.4\nexample.com");

        assertEquals(List.of("example.com"), result.accepted());
        assertEquals(List.of("not", "a", "domain!!", "1.2.3.4"), result.rejected());
    }

    @Test
    void d6AnOverLongTokenIsEchoedTruncatedToMaxEchoChars() {
        String overlong = "x".repeat(200) + "!!not-a-domain";
        TargetDrop result = DroppedTargets.parse(overlong);

        assertEquals(1, result.rejected().size());
        assertEquals(DroppedTargets.MAX_ECHO_CHARS, result.rejected().get(0).length());
        assertEquals(overlong.substring(0, DroppedTargets.MAX_ECHO_CHARS), result.rejected().get(0));
    }

    @Test
    void d7EmptyBlankOrNullBlobYieldsEmptyDropWithNoThrow() {
        assertEquals(TargetDrop.empty(), assertDoesNotThrow(() -> DroppedTargets.parse(null)));
        assertEquals(TargetDrop.empty(), assertDoesNotThrow(() -> DroppedTargets.parse("")));
        assertEquals(TargetDrop.empty(), assertDoesNotThrow(() -> DroppedTargets.parse("   \n  ")));
    }

    @Test
    void d8ABlobOfSixThousandTokensStopsAtMaxTokens() {
        StringBuilder blob = new StringBuilder();
        for (int i = 0; i < 6000; i++) {
            blob.append("t").append(i).append(".com\n");
        }

        TargetDrop result = DroppedTargets.parse(blob.toString());

        assertEquals(DroppedTargets.MAX_TOKENS, result.accepted().size());
        assertTrue(result.accepted().contains("t4999.com"));
        assertFalse(result.accepted().contains("t5000.com"));
    }

    @Test
    void d9ABlobWithOnlyInvalidTokensIsNotEmpty() {
        TargetDrop result = DroppedTargets.parse("not-valid\nalso not valid");

        assertTrue(result.accepted().isEmpty());
        assertFalse(result.rejected().isEmpty());
        assertFalse(result.isEmpty());
    }

    @Test
    void d10FilesWinWhenAContentCarriesBothFilesAndText() {
        List<Path> files = List.of(Path.of("domains.txt"));
        DroppedContent content = new DroppedContent(true, files, true, "example.com");

        assertEquals(files, DroppedTargets.filesOf(content));
        assertEquals("", DroppedTargets.textOf(content));
    }

    @Test
    void d11TextOfReturnsTextForATextOnlyDropAndEmptyStringForBlankText() {
        DroppedContent withText = DroppedContent.ofText("example.com");
        assertEquals("example.com", DroppedTargets.textOf(withText));
        assertTrue(DroppedTargets.filesOf(withText).isEmpty());

        DroppedContent blank = DroppedContent.ofText("   ");
        assertEquals("", DroppedTargets.textOf(blank));
    }

    @Test
    void d12FilesOfAndTextOfOnNoneAreEmpty() {
        DroppedContent none = DroppedContent.none();

        assertTrue(DroppedTargets.filesOf(none).isEmpty());
        assertEquals("", DroppedTargets.textOf(none));
    }

    @Test
    void d13ReadOfTwoFilesJoinsTheirContentsWithNewline() throws IOException {
        Path first = tempDir.resolve("first.txt");
        Path second = tempDir.resolve("second.txt");
        Files.writeString(first, "a.com");
        Files.writeString(second, "b.com");

        String joined = DroppedTargets.read(List.of(first, second));

        assertEquals("a.com\nb.com", joined);
    }

    @Test
    void d14ReadThrowsForAFileOverMaxFileBytesAndNamesTheFileNotThePath() throws IOException {
        Path big = tempDir.resolve("big.txt");
        Files.write(big, new byte[(int) DroppedTargets.MAX_FILE_BYTES + 1]);

        IOException thrown =
                assertThrows(IOException.class, () -> DroppedTargets.read(List.of(big)));

        assertTrue(thrown.getMessage().contains("big.txt"));
        assertFalse(thrown.getMessage().contains(tempDir.toAbsolutePath().toString()));
    }

    @Test
    void d15ReadThrowsForAMissingPathOrADirectory() throws IOException {
        Path missing = tempDir.resolve("does-not-exist.txt");
        assertThrows(IOException.class, () -> DroppedTargets.read(List.of(missing)));

        Path directory = tempDir.resolve("a-directory");
        Files.createDirectory(directory);
        assertThrows(IOException.class, () -> DroppedTargets.read(List.of(directory)));
    }

    @Test
    void d16ReadOfInvalidUtf8BytesDoesNotThrow() throws IOException {
        Path binary = tempDir.resolve("binary.dat");
        Files.write(binary, new byte[] {(byte) 0xFF, (byte) 0xFE, 0x00, (byte) 0xC0});

        String result = assertDoesNotThrow(() -> DroppedTargets.read(List.of(binary)));

        assertTrue(result != null);
    }

    @Test
    void d17ReadHonoursMaxFilesReadingOnlyTheFirstEight() throws IOException {
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            Path file = tempDir.resolve("file" + i + ".txt");
            Files.writeString(file, "t" + i + ".com", StandardCharsets.UTF_8);
            files.add(file);
        }

        String joined = DroppedTargets.read(files);

        assertTrue(joined.contains("t7.com"));
        assertFalse(joined.contains("t8.com"));
    }
}
