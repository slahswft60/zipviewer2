package com.example

import org.junit.Assert.assertEquals
import org.junit.Test

class FileCategoryTest {

    @Test
    fun testFileCategories() {
        val dir = ZipEntryItem("assets/images/", 0, 0, true)
        assertEquals(FileCategory.DIRECTORY, resolveFileCategory(dir))

        val img = ZipEntryItem("assets/images/photo.png", 5000, 4000, false)
        assertEquals(FileCategory.IMAGE, resolveFileCategory(img))

        val audio = ZipEntryItem("sounds/effect.mp3", 15000, 12000, false)
        assertEquals(FileCategory.AUDIO, resolveFileCategory(audio))

        val video = ZipEntryItem("movies/clip.mp4", 150000, 120000, false)
        assertEquals(FileCategory.VIDEO, resolveFileCategory(video))

        val doc = ZipEntryItem("notes/readme.md", 500, 300, false)
        assertEquals(FileCategory.DOCUMENT, resolveFileCategory(doc))

        val code = ZipEntryItem("src/Main.kt", 1200, 800, false)
        assertEquals(FileCategory.CODE, resolveFileCategory(code))

        val zip = ZipEntryItem("nested/archive.zip", 20000, 18000, false)
        assertEquals(FileCategory.ARCHIVE, resolveFileCategory(zip))

        val generic = ZipEntryItem("data/unknown.xyz", 100, 80, false)
        assertEquals(FileCategory.GENERIC, resolveFileCategory(generic))
    }
}
