package app.televault.data

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class MediaStorageScannerTest {
    @Test fun usageCountsEachStorageOwnerOnceAndKeepsOfflineAndOriginalsSeparate() {
        val root = Files.createTempDirectory("media-storage-breakdown").toFile()
        fun sized(path: String, bytes: Int) {
            File(root, path).apply {
                parentFile.mkdirs()
                writeBytes(ByteArray(bytes))
            }
        }
        try {
            sized("files/preferences", 1)
            sized("noBackup/key-wrapper", 2)
            sized("noBackup/stream-cipher-cache/block.bin", 3)
            sized("noBackup/photo-cipher-cache/old.bin", 4)
            sized("noBackup/offline-cipher/pinned.bin", 5)
            sized("noBackup/drive-cache/active-upload.bin", 6)
            sized("noBackup/drive-cache.sqlite", 7)
            sized("noBackup/drive-cache.sqlite-wal", 8)
            sized("noBackup/tdlib/files/document", 9)
            sized("noBackup/tdlib/database/data", 10)
            sized("noBackup/tdlib/other", 11)
            sized("cache/encrypted-media/range.bin", 12)
            sized("cache/shared/export/approved.jpg", 13)
            sized("cache/ongoing-export.zip", 14)
            sized("device-originals/source.jpg", 15)
            val usage = mediaStorageSnapshot(File(root, "files"), File(root, "noBackup"), File(root, "cache"), 4096)
            assertEquals(MediaStorageUsage(4096, 19, 9, 5, 33, 25, 14), usage)
            assertEquals(105L, usage.temporary + usage.native + usage.offline + usage.staging + usage.catalog + usage.other)
            assertEquals(15L, File(root, "device-originals/source.jpg").length())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun linksCannotEscapeTheAppTreeOrRecurseBackIntoIt() {
        val root = Files.createTempDirectory("media-storage-links")
        val original = Files.createTempFile("device-original", ".jpg")
        try {
            Files.write(original, ByteArray(4096))
            Files.write(root.resolve("owned"), ByteArray(17))
            Files.createSymbolicLink(root.resolve("original-link"), original)
            Files.createSymbolicLink(root.resolve("loop"), root)
            assertEquals(17L, storageTreeBytes(root.toFile()))
            assertEquals(4096L, Files.size(original))
        } finally {
            Files.deleteIfExists(root.resolve("original-link"))
            Files.deleteIfExists(root.resolve("loop"))
            root.toFile().deleteRecursively()
            Files.delete(original)
        }
    }

    @Test fun missingAndDeepTreesAreSafeWithoutRecursiveStackGrowth() {
        val root = Files.createTempDirectory("media-storage-deep").toFile()
        try {
            var directory = root
            repeat(500) { directory = File(directory, "d").apply { mkdir() } }
            File(directory, "cipher.bin").writeBytes(ByteArray(9))
            assertEquals(9L, storageTreeBytes(root))
            assertEquals(0L, storageTreeBytes(File(root, "missing")))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun derivedIndexesAndPublicLanguageModelsHaveSeparateStorageOwners() {
        val root = Files.createTempDirectory("derived-storage").toFile()
        try {
            fun sized(path: String, n: Int) {
                File(root, path).apply {
                    parentFile!!.mkdirs()
                    writeBytes(ByteArray(n))
                }
            }
            sized("noBackup/people/7/vault/catalog.bin", 3)
            sized("noBackup/ocr-index/7/vault/word.bin", 5)
            sized("noBackup/ocr-models/tessdata/ben.traineddata", 7)
            val result = mediaStorageSnapshot(File(root, "files"), File(root, "noBackup"), File(root, "cache"), 4096)
            assertEquals(8L, result.derived)
            assertEquals(7L, result.models)
            assertEquals(0L, result.other)
            assertEquals(0L, result.temporary)
        } finally {
            root.deleteRecursively()
        }
    }
}
