package app.televault.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.*

/** Explicit user-selected tree only. Bounded traversal; no Library automatic upload or deletion of unselected folders. */
internal object VaultFolderSelection {
    suspend fun files(context: Context, uri: Uri): List<Pair<Uri, String>> = withContext(Dispatchers.IO) {
        val root = checkNotNull(DocumentFile.fromTreeUri(context, uri))
        val result = mutableListOf<Pair<Uri, String>>()
        val seen = mutableSetOf<Uri>()
        suspend fun visit(folder: DocumentFile, prefix: String, depth: Int) {
            currentCoroutineContext().ensureActive()
            check(depth <= 24 && seen.add(folder.uri))
            for (child in folder.listFiles()) {
                currentCoroutineContext().ensureActive()
                check(result.size < 1000)
                val name = checkNotNull(child.name).replace('/', '_')
                check(name.isNotBlank() && name !in setOf(".", ".."))
                val path = "$prefix/$name"
                check(path.length <= 512)
                if (child.isDirectory) {
                    visit(child, path, depth + 1)
                } else if (child.isFile && child.canRead()) {
                    result.add(child.uri to path)
                }
            }
        }
        visit(root, root.name.orEmpty().ifBlank { "Folder" }.replace('/', '_'), 0)
        result
    }
}
