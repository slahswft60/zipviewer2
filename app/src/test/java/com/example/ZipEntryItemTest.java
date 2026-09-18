package com.example;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ZipEntryItemTest {

    @Test
    public void testFileEntryNameAndPath() {
        ZipEntryItem item = new ZipEntryItem("documents/report.pdf", 2048, 1024, false);
        assertEquals("report.pdf", item.getSimpleName());
        assertEquals("documents/report.pdf", item.getPath());
        assertFalse(item.isDirectory());
        assertEquals("2.0 KB", item.getFormattedSize());
    }

    @Test
    public void testDirectoryEntry() {
        ZipEntryItem dir = new ZipEntryItem("images/", 0, 0, true);
        assertEquals("images", dir.getSimpleName());
        assertTrue(dir.isDirectory());
        assertEquals("Folder", dir.getFormattedSize());
    }
}
