package app.televault.data

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class ModelDownloadFilesTest {
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun completeVerifiedPartialInstallsWithoutAnyNetworkRequest() {
        val dir = Files.createTempDirectory("model-test").toFile()
        try {
            val target = File(dir, "eng.traineddata")
            val part = File(target.path + ".partial")
            val bytes = "public synthetic model bytes".toByteArray()
            part.writeBytes(bytes)
            assertTrue(promoteVerifiedModelPart(part, target, bytes.size.toLong(), sha(bytes)))
            assertArrayEquals(bytes, target.readBytes())
            assertFalse(part.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun corruptFullPartialNeverReplacesAWorkingModel() {
        val dir = Files.createTempDirectory("model-test").toFile()
        try {
            val target = File(dir, "face.onnx")
            target.writeText("working")
            val part = File(target.path + ".partial")
            part.writeText("corrupt")
            assertFalse(promoteVerifiedModelPart(part, target, 7, sha("working".toByteArray())))
            assertEquals("working", target.readText())
            assertFalse(part.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun incompletePrefixRemainsAvailableForRangeResume() {
        val dir = Files.createTempDirectory("model-test").toFile()
        try {
            val target = File(dir, "scene.onnx")
            val part = File(target.path + ".partial")
            part.writeText("pre")
            assertFalse(promoteVerifiedModelPart(part, target, 6, sha("prefix".toByteArray())))
            assertEquals("pre", part.readText())
            assertFalse(target.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun helperCannotMoveAnUnrelatedFile() {
        val dir = Files.createTempDirectory("model-test").toFile()
        try {
            val unrelated = File(dir, "original.jpg")
            unrelated.writeText("photo")
            try {
                promoteVerifiedModelPart(unrelated, File(dir, "face.onnx"), 5, sha("photo".toByteArray()))
                fail()
            } catch (_: IllegalArgumentException) { }
            assertEquals("photo", unrelated.readText())
        } finally {
            dir.deleteRecursively()
        }
    }
}
