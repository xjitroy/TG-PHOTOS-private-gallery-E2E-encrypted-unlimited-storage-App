package app.televault.people

import android.content.Context
import android.graphics.Bitmap
import android.util.AtomicFile
import android.util.Base64
import app.televault.data.DriveRepository
import app.televault.domain.FaceSimilarity
import app.televault.domain.ForgottenVaultPolicy
import app.televault.telegram.TelegramGateway
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

internal data class PersonCard(val id: String, val name: String, val cover: ByteArray, val members: Set<String>, val suggestion: String? = null)
internal data class PeopleState(val enabled: Boolean = false, val people: List<PersonCard> = emptyList(), val scanned: Int = 0, val busy: Boolean = false, val pendingSync: Boolean = false, val notice: String = "")
private class Person(val id: String, var name: String = "", val references: MutableList<FaceSample> = mutableListOf(), val members: MutableSet<String> = linkedSetOf(), var suggestion: String? = null, var deleted: Boolean = false, var remote: Long = 0, var dirty: Boolean = false) {
    fun wipe() {
        references.forEach { it.wipe() }
        references.clear()
        members.clear()
        name = ""
    }
}

/** Normal Photos only. Names, face crops, vectors and local membership are authenticated ciphertext. */
internal class PeopleStore(private val context: Context, private val drive: DriveRepository, private val telegram: TelegramGateway) {
    private val mutex = Mutex()
    private val publicationGuard = Any()
    private val mutable = MutableStateFlow(PeopleState())
    val state = mutable.asStateFlow()
    private val persons = linkedMapOf<String, Person>()
    private val scanned = linkedMapOf<String, String>()

    @Volatile private var binding: Pair<Long, String>? = null
    private var changedSinceSave = 0
    private var enabled = false

    private val generation = java.util.concurrent.atomic.AtomicLong()
    private var engine: FaceEngine? = null
    private fun prefix(scope: Pair<Long, String>) = "TGDRIVE_V1_PEOPLE:${scope.second}:"
    private fun file(scope: Pair<Long, String>) = AtomicFile(File(context.noBackupFilesDir, "people/${scope.first}/${scope.second}/catalog.bin"))
    private fun access(scope: Pair<Long, String>, token: Long) {
        check(token == generation.get() && drive.privateVaultBinding() == scope) { "People session changed" }
    }
    private fun encode(person: Person, remote: Boolean): JSONObject = JSONObject().put("id", person.id).put("name", person.name).put("deleted", person.deleted).put("suggestion", if (remote) JSONObject.NULL else person.suggestion ?: JSONObject.NULL).put("model", MODEL).put("remote", person.remote).put("dirty", person.dirty).put("members", JSONArray(person.members.filter { !remote || ForgottenVaultPolicy.validId(it) })).put("references", JSONArray(person.references.map { sample -> JSONObject().put("source", sample.source).put("contentHash", sample.contentHash).put("jpeg", Base64.encodeToString(sample.jpeg, Base64.NO_WRAP)).put("embedding", JSONArray(sample.embedding.map { it.toDouble() })) }))
    private fun decode(data: JSONObject): Person {
        val id = data.getString("id")
        check(ForgottenVaultPolicy.validId(id))
        check(data.getString("model") == MODEL)
        val name = data.getString("name")
        check(name.length <= 80)
        val person = Person(id, name, deleted = data.optBoolean("deleted"), remote = data.optLong("remote"), dirty = data.optBoolean("dirty"))
        try {
            val refs = data.getJSONArray("references")
            check(refs.length() <= 2)
            for (i in 0 until refs.length()) {
                val row = refs.getJSONObject(i)
                val vector = row.getJSONArray("embedding")
                check(vector.length() == 128)
                val embedding = FaceSimilarity.normalized(FloatArray(128) { vector.getDouble(it).toFloat() })
                val jpeg = Base64.decode(row.getString("jpeg"), Base64.NO_WRAP)
                check(jpeg.size in 1..24000)
                person.references += FaceSample(embedding, jpeg, row.optString("source"), row.optString("contentHash"))
            }
            val members = data.getJSONArray("members")
            check(members.length() <= 50000)
            for (i in 0 until members.length()) {
                val member = members.getString(i)
                check(member.length <= 2048)
                person.members += member
            }
            person.suggestion = data.optString("suggestion").takeIf { ForgottenVaultPolicy.validId(it) }
            return person
        } catch (error: Throwable) {
            person.wipe()
            throw error
        }
    }
    private fun publish(scope: Pair<Long, String>, token: Long, notice: String = "", busy: Boolean = false) {
        access(scope, token)
        persons.values.forEach(::sanitize)
        val cards = persons.values.filter { !it.deleted && it.members.isNotEmpty() }.map { PersonCard(it.id, it.name, it.references.firstOrNull()?.jpeg?.clone() ?: byteArrayOf(), it.members.toSet(), it.suggestion) }.sortedWith(compareBy<PersonCard> { it.name.isBlank() }.thenBy { it.name }.thenBy { it.id })
        synchronized(publicationGuard) {
            if (generation.get() != token) {
                cards.forEach { it.cover.fill(0) }
                error("People session changed")
            }
            val old = mutable.value
            mutable.value = PeopleState(enabled, cards, scanned.size, busy, persons.values.any { it.dirty }, notice)
            old.people.forEach { it.cover.fill(0) }
        }
    }
    private fun save(scope: Pair<Long, String>, token: Long) {
        access(scope, token)
        val body = JSONObject().put("version", 1).put("enabled", enabled).put("people", JSONArray(persons.values.map { encode(it, false) })).put("scanned", JSONObject(scanned.toMap())).toString().toByteArray()
        try {
            check(body.size <= 12 * 1024 * 1024) { "People index is full; remove unused groups to continue" }
            val sealed = drive.peopleEnvelope(body, "catalog", scope, false)
            try {
                val target = file(scope)
                target.baseFile.parentFile!!.mkdirs()
                val stream = target.startWrite()
                try {
                    stream.write(sealed)
                    access(scope, token)
                    target.finishWrite(stream)
                } catch (error: Throwable) {
                    target.failWrite(stream)
                    throw error
                }
            } finally {
                sealed.fill(0)
            }
        } finally {
            body.fill(0)
        }
    }
    suspend fun activate() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val scope = drive.privateVaultBinding()
            if (binding == scope) return@withLock
            clearMemory()
            binding = scope
            val token = generation.get()
            val target = file(scope)
            if (target.baseFile.exists() || File(target.baseFile.path + ".bak").exists()) {
                try {
                    check(target.baseFile.length() <= 13 * 1024 * 1024)
                    val cipher = target.openRead().use { it.readBytes() }
                    val bytes = try {
                        drive.peopleEnvelope(cipher, "catalog", scope, true)
                    } finally {
                        cipher.fill(0)
                    }
                    try {
                        val body = JSONObject(bytes.toString(Charsets.UTF_8))
                        check(body.getInt("version") == 1)
                        enabled = body.getBoolean("enabled")
                        val rows = body.getJSONArray("people")
                        check(rows.length() <= 1000)
                        for (i in 0 until rows.length()) {
                            val person = decode(rows.getJSONObject(i))
                            persons[person.id] = person
                        }
                        val records = body.getJSONObject("scanned")
                        check(records.length() <= 50000)
                        records.keys().forEach { scanned[it] = records.getString(it) }
                    } finally {
                        bytes.fill(0)
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    clearMemory()
                    binding = scope
                    publish(scope, generation.get(), "People index could not be opened. Original media is unchanged. Enable to rebuild.")
                    return@withLock
                }
            }
            persons.values.forEach(::sanitize)
            publish(scope, token)
            if (enabled) {
                try {
                    syncLocked(scope, token)
                    publish(scope, token)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    publish(scope, token, "Encrypted People sync will retry when you open People.")
                }
            }
        }
    }
    suspend fun enable(value: Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val scope = drive.privateVaultBinding()
            val token = generation.get()
            check(binding == scope)
            enabled = value
            save(scope, token)
            publish(scope, token)
            if (value) {
                try {
                    syncLocked(scope, token)
                    publish(scope, token)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    publish(scope, token, "Encrypted reference sync is pending.")
                }
            }
        }
    }
    suspend fun sync() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val scope = drive.privateVaultBinding()
            val token = generation.get()
            check(binding == scope)
            syncLocked(scope, token)
            publish(scope, token)
        }
    }
    private fun sanitize(person: Person): Boolean {
        val blockedMembers = person.members.filter { drive.privateAi.excluded(it) }.toSet()
        var changed = person.members.removeAll(blockedMembers)
        val removed = person.references.filter { drive.privateAi.excluded(it.source, it.contentHash) || it.source.isBlank() && blockedMembers.isNotEmpty() }
        removed.forEach {
            val id = drive.privateAi.localId(it.source)
            person.members.remove(id)
            scanned.remove(id)
            it.wipe()
        }
        if (removed.isNotEmpty()) {
            person.references.removeAll(removed.toSet())
            changed = true
        }
        if (person.members.isEmpty() && person.references.isNotEmpty() && blockedMembers.isNotEmpty()) {
            person.references.forEach { it.wipe() }
            person.references.clear()
            changed = true
        }
        if (person.references.isEmpty() && person.members.isNotEmpty())person.members.forEach { scanned.remove(it) }
        if (changed && (person.name.isNotBlank() || person.remote > 0))person.dirty = true
        return changed
    }
    suspend fun cleanPrivateSources() {
        activate()
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val scope = drive.privateVaultBinding()
                val token = generation.get()
                check(binding == scope)
                persons.values.forEach(::sanitize)
                save(scope, token)
                publish(scope, token)
            }
        }
    }
    private suspend fun authenticatedBody(ref: app.televault.telegram.RemoteDocument, scope: Pair<Long, String>, token: Long): JSONObject {
        val parts = ref.marker.split(':')
        check(parts.size == 4 && parts[1] == scope.second && app.televault.domain.IntelligencePrivacy.ownedPersonCaption(ref.marker, scope.second, parts[2]))
        check(ref.bytes in 1..MAX_REMOTE)
        val cipher = ByteArray(ref.bytes.toInt())
        var offset = 0
        try {
            while (offset < cipher.size) {
                access(scope, token)
                currentCoroutineContext().ensureActive()
                val chunk = telegram.readDocumentPart(ref, offset.toLong(), minOf(262144, cipher.size - offset))
                try {
                    check(chunk.isNotEmpty() && chunk.size <= cipher.size - offset)
                    chunk.copyInto(cipher, offset)
                    offset += chunk.size
                } finally {
                    chunk.fill(0)
                }
            }
            val plain = drive.peopleEnvelope(cipher, "${parts[2]}/${parts[3]}", scope, true)
            return try {
                JSONObject(plain.toString(Charsets.UTF_8)).also { body -> check(body.getLong("account") == scope.first && body.getString("vault") == scope.second && body.getString("revision") == parts[3] && body.getJSONObject("person").getString("id") == parts[2]) }
            } finally {
                plain.fill(0)
            }
        } finally {
            cipher.fill(0)
        }
    }
    private suspend fun syncLocked(scope: Pair<Long, String>, token: Long) {
        access(scope, token)
        drive.privateAi.sync() // Never upload face references against a stale online privacy ledger.
        access(scope, token)
        persons.values.forEach(::sanitize)
        val all = telegram.findDocuments(prefix(scope)).filter { ref ->
            val parts = ref.marker.split(':')
            parts.size == 4 && app.televault.domain.IntelligencePrivacy.ownedPersonCaption(ref.marker, scope.second, parts[2])
        }
        val documents = all.groupBy { it.marker.split(':')[2] }.values.mapNotNull { it.maxByOrNull { row -> row.messageId } }
        for (ref in documents) {
            access(scope, token)
            val id = ref.marker.split(':')[2]
            val old = persons[id]
            if (old != null && old.remote >= ref.messageId)continue
            val incoming = decode(authenticatedBody(ref, scope, token).getJSONObject("person"))
            if (old?.dirty == true && !incoming.deleted && !old.deleted) {
                incoming.members += old.members
                incoming.dirty = true
                if (old.remote == 0L && old.name.isNotBlank())incoming.name = old.name
            }
            if (old?.deleted == true) {
                incoming.wipe()
                old.remote = ref.messageId
                old.dirty = true
            } else {
                old?.wipe()
                incoming.remote = ref.messageId
                persons[id] = incoming
                sanitize(incoming)
            }
        }
        save(scope, token)
        for (person in persons.values.filter { it.dirty && (it.name.isNotBlank() || it.deleted || it.remote > 0) }) {
            access(scope, token)
            sanitize(person)
            val revision = UUID.randomUUID().toString()
            val body = JSONObject().put("account", scope.first).put("vault", scope.second).put("revision", revision).put("person", encode(person, true)).toString().toByteArray()
            val cipher = try {
                drive.peopleEnvelope(body, "${person.id}/$revision", scope, false)
            } finally {
                body.fill(0)
            }
            val staging = File(context.cacheDir, "people-${UUID.randomUUID()}.bin")
            try {
                check(cipher.size <= MAX_REMOTE)
                staging.writeBytes(cipher)
                access(scope, token)
                val sent = telegram.sendDocument(staging, "${prefix(scope)}${person.id}:$revision") { _, _ -> }
                authenticatedBody(sent.copy(marker = "${prefix(scope)}${person.id}:$revision"), scope, token)
                access(scope, token)
                person.remote = sent.messageId
                person.dirty = false
                save(scope, token)
            } finally {
                cipher.fill(0)
                staging.delete()
            }
        }
        // A confirmed complete current snapshot replaces older authenticated snapshots.
        // Remove old reference pixels too, rather than leaving a lower-security copy after a Private move.
        for (person in persons.values.filter { it.remote > 0 }) {
            val previous = all.filter { it.marker.split(':')[2] == person.id && it.messageId < person.remote }
            for (batch in previous.chunked(50)) {
                access(scope, token)
                batch.forEach { authenticatedBody(it, scope, token) }
                telegram.deletePeopleDocuments(batch, scope.second, person.id)
            }
        }
        publish(scope, token)
    }
    suspend fun analyze(id: String, stamp: String, load: suspend () -> Bitmap?): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val scope = drive.privateVaultBinding()
            val token = generation.get()
            if (!enabled || binding != scope || scanned[id] == stamp) return@withLock false
            check(scanned.size < 50000 && persons.count { !it.value.deleted } < 256) { "People index limit reached. Remove unused groups to continue." }
            if (changedSinceSave % 5 == 0) publish(scope, token, busy = true)
            if (!drive.normalIntelligenceMedia(id))return@withLock false
            val contentHash = drive.intelligenceContentHash(id)
            if (drive.privateAi.excluded(id, contentHash))return@withLock false
            val image = load() ?: return@withLock false
            // Thumbnails may be shared by the gallery; the engine never erases/recycles the source.
            val samples = (engine ?: FaceEngine(context).also { engine = it }).faces(image)
            try {
                access(scope, token)
                val awaiting = persons.values.filter { !it.deleted && it.references.isEmpty() && it.name.isNotBlank() && id in it.members }
                if (samples.size == 1 && awaiting.size == 1) {
                    val sample = samples.first()
                    awaiting.first().references += FaceSample(sample.embedding.clone(), sample.jpeg.clone(), drive.privateAi.identity(id), contentHash)
                    awaiting.first().dirty = true
                }
                for (sample in samples) {
                    val ranked = persons.values.filter { !it.deleted && it.references.isNotEmpty() }.map { person -> person to person.references.maxOf { FaceSimilarity.cosine(it.embedding, sample.embedding) } }.sortedByDescending { it.second }
                    val best = ranked.firstOrNull()
                    val second = ranked.getOrNull(1)?.second ?: -1f
                    if (best != null && FaceSimilarity.confident(best.second, second, best.first.references.size)) {
                        if (best.first.members.add(id) && best.first.name.isNotBlank()) best.first.dirty = true
                    } else {
                        check(persons.count { !it.value.deleted } < 256) { "People group limit reached" }
                        val person = Person(UUID.randomUUID().toString(), references = mutableListOf(FaceSample(sample.embedding.clone(), sample.jpeg.clone(), drive.privateAi.identity(id), contentHash)), members = linkedSetOf(id), suggestion = best?.takeIf { it.second >= .50f }?.first?.id ?: awaiting.singleOrNull()?.id)
                        persons[person.id] = person
                    }
                }
                scanned[id] = stamp
                changedSinceSave++
                if (changedSinceSave % 10 == 0) save(scope, token)
                if (changedSinceSave % 5 == 0) publish(scope, token)
                true
            } finally {
                samples.forEach { it.wipe() }
            }
        }
    }
    suspend fun rename(id: String, name: String) = mutate {
        val person = persons.getValue(id)
        check(!person.deleted)
        person.name = name.trim().take(80)
        person.suggestion = null
        person.dirty = true
    }
    suspend fun tag(id: String, media: Set<String>) = mutate {
        val person = persons.getValue(id)
        check(!person.deleted)
        person.members += media
        person.dirty = true
    }
    suspend fun untag(id: String, media: String) = mutate {
        persons.getValue(id).apply {
            members.remove(media)
            dirty = true
        }
    }
    suspend fun review(id: String, same: Boolean) = mutate {
        val person = persons.getValue(id)
        val target = persons[person.suggestion]
        if (same && target != null && !target.deleted && !person.deleted) {
            target.members += person.members
            person.references.take(2 - target.references.size).forEach { target.references += FaceSample(it.embedding.clone(), it.jpeg.clone(), it.source, it.contentHash) }
            target.dirty = true
            person.wipe()
            person.deleted = true
            person.dirty = true
        } else {
            person.suggestion = null
        }
    }
    suspend fun remove(id: String) = mutate {
        persons.getValue(id).apply {
            wipe()
            deleted = true
            dirty = true
        }
    }
    private suspend fun mutate(action: () -> Unit) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val scope = drive.privateVaultBinding()
            val token = generation.get()
            check(binding == scope)
            // Pull first: offline edits stay local only if this device already has the reference catalog.
            try {
                syncLocked(scope, token)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                access(scope, token)
            }
            action()
            save(scope, token)
            publish(scope, token)
            try {
                syncLocked(scope, token)
                publish(scope, token)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                publish(scope, token, "Saved encrypted on this phone. Telegram reference sync is pending; use Sync before reinstalling.")
            }
        }
    }
    private fun clearMemory() {
        generation.incrementAndGet()
        engine?.close()
        engine = null
        persons.values.forEach { it.wipe() }
        persons.clear()
        scanned.clear()
        enabled = false
        binding = null
        mutable.value.people.forEach { it.cover.fill(0) }
        mutable.value = PeopleState()
    }
    suspend fun pauseEngine() = withContext(Dispatchers.IO) {
        mutex.withLock {
            engine?.close()
            engine = null
            mutable.value = mutable.value.copy(busy = false)
        }
    }
    fun lock() {
        val token = synchronized(publicationGuard) {
            val next = generation.incrementAndGet()
            binding = null
            mutable.value.people.forEach { it.cover.fill(0) }
            mutable.value = PeopleState()
            next
        }
        CoroutineScope(Dispatchers.IO).launch {
            mutex.withLock {
                if (generation.get() == token) {
                    clearMemory()
                    engine?.close()
                    engine = null
                }
            }
        }
    }
    fun retire(owner: Long, vault: String) {
        require(ForgottenVaultPolicy.validId(vault))
        if (binding == (owner to vault)) lock()
        File(context.noBackupFilesDir, "people/$owner/$vault").deleteRecursively()
    }
    companion object {
        const val MODEL = "mobile-facenet-2046ab77-v1"
        private const val MAX_REMOTE = 2L * 1024 * 1024
    }
}
