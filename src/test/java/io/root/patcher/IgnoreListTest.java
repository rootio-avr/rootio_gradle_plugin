package io.root.patcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IgnoreListTest {

    @TempDir
    File rootDir;

    @Test
    void absentFileAndNoEntriesIsEmpty() {
        IgnoreList list = IgnoreList.load(rootDir, List.of());
        assertTrue(list.toApiEntries().isEmpty());
    }

    @Test
    void loadsEntriesFromFile() throws IOException {
        Files.writeString(new File(rootDir, ".rootioignore").toPath(),
            "# comment\n\ncom.google.guava:guava@31.0-jre\norg.apache.commons:commons-lang3@3.12.0\n");
        IgnoreList list = IgnoreList.load(rootDir, List.of());
        List<String> entries = list.toApiEntries();
        assertTrue(entries.contains("com.google.guava:guava@31.0-jre"));
        assertTrue(entries.contains("org.apache.commons:commons-lang3@3.12.0"));
    }

    @Test
    void loadsEntriesFromExtraOnly() {
        IgnoreList list = IgnoreList.load(rootDir, List.of("io.test:my-lib@1.0.0"));
        assertTrue(list.toApiEntries().contains("io.test:my-lib@1.0.0"));
    }

    @Test
    void mergesAndDeduplicatesFileAndExtra() throws IOException {
        Files.writeString(new File(rootDir, ".rootioignore").toPath(), "io.test:my-lib@1.0.0\n");
        IgnoreList list = IgnoreList.load(rootDir, List.of("io.test:my-lib@1.0.0", "io.test:other@2.0.0"));
        List<String> entries = list.toApiEntries();
        assertTrue(entries.contains("io.test:my-lib@1.0.0"));
        assertTrue(entries.contains("io.test:other@2.0.0"));
        assertEquals(2, entries.size());
    }

    @Test
    void skipsMalformedEntriesWithoutFailing() throws IOException {
        Files.writeString(new File(rootDir, ".rootioignore").toPath(),
            "io.test:my-lib\ngarbage\nio.test:good@1.0.0\n");
        IgnoreList list = IgnoreList.load(rootDir, List.of());
        assertTrue(list.toApiEntries().contains("io.test:good@1.0.0"));
        assertEquals(1, list.toApiEntries().size());
    }

    @Test
    void toApiEntriesEmptyWhenNoEntries() {
        IgnoreList list = IgnoreList.load(rootDir, List.of());
        assertTrue(list.toApiEntries().isEmpty());
    }

    @Test
    void toApiEntriesReturnsAllEntries() throws IOException {
        Files.writeString(new File(rootDir, ".rootioignore").toPath(),
            "com.google.guava:guava@31.0-jre-root.io.5\n");
        IgnoreList list = IgnoreList.load(rootDir, List.of("org.example:foo@1.0-root.io.3"));
        List<String> entries = list.toApiEntries();
        assertEquals(2, entries.size());
        assertTrue(entries.contains("com.google.guava:guava@31.0-jre-root.io.5"));
        assertTrue(entries.contains("org.example:foo@1.0-root.io.3"));
    }
}
