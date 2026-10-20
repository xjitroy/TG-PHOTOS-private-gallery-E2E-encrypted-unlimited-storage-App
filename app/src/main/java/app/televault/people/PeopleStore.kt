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

internal data class PersonCard(val id: String, val name: String, val cover: ByteArray, val members: Set<String>, val suggestion: String? = null, val provisional: Boolean = false, val model: String = PeopleStore.MODEL, val reviewAnswer: Boolean? = null)
internal data class PeopleState(val enabled: Boolean = false, val people: List<PersonCard> = emptyList(), val scanned: Int = 0, val busy: Boolean = false, val pendingSync: Boolean = false, val notice: String = "", val faceCounts: Map<String, Int> = emptyMap(), val selfId: String? = null)
private class Person(val id: String, var name: String = "", val references: MutableList<FaceSample> = mutableListOf(), val members: MutableSet<String> = linkedSetOf(), var suggestion: String? = null, var deleted: Boolean = false, var remote: Long = 0, var dirty: Boolean = false, val different: MutableSet<String> = linkedSetOf(), var reviewed: Boolean = false, val excludedMembers: MutableSet<String> = linkedSetOf(), var pendingName: Boolean = false, val photoHashes: MutableSet<String> = linkedSetOf(), val excludedHashes: MutableSet<String> = linkedSetOf(), var selfSelected: Boolean = false, var selfChangedAt: Long = 0, var embeddingModel: String = PeopleStore.MODEL, var rescanAutomatic: Boolean = false, var reviewVote: FaceReviewVote = FaceReviewVote()) {
    fun wipe() {
        reviewVote = FaceReviewVote()
        references.forEach { it.wipe() }
        references.clear()
        members.clear()
        photoHashes.clear()
        excludedHashes.clear()
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
    private val scanQuality = linkedMapOf<String, Int>()
    private val faceCounts = linkedMapOf<String, Int>()
    private val sourceHashes = linkedMapOf<String, String>()
    private val hashCounts = linkedMapOf<String, Int>()
    private fun mediaHash(id: String) = sourceHashes[id] ?: drive.state.value.files.find { it.id == id }?.hash.orEmpty()
    private var reviewSync: Job? = null

    @Volatile private var binding: Pair<Long, String>? = null
    private var changedSinceSave = 0
    private var enabled = false
    private var restorePending = true

    private val generation = java.util.concurrent.atomic.AtomicLong()
    private var engine: FaceEngine? = null
    val models = (context.applicationContext as app.televault.TeleVaultApplication).faceModels
    private var scanModel = MODEL
    private fun prefix(scope: Pair<Long, String>) = "TGDRIVE_V1_PEOPLE:${scope.second}:"
    private fun file(scope: Pair<Long, String>) = AtomicFile(File(context.noBackupFilesDir, "people/${scope.first}/${scope.second}/catalog.bin"))
    private fun access(scope: Pair<Long, String>, token: Long) {
        check(token == generation.get() && drive.privateVaultBinding() == scope) { "People session changed" }
    }
    private fun sampleJson(sample: FaceSample) = JSONObject().put("source", sample.source).put("contentHash", sample.contentHash).put("jpeg", Base64.encodeToString(sample.jpeg, Base64.NO_WRAP)).put("embedding", JSONArray(sample.embedding.map { it.toDouble() }))
    private fun encode(person: Person, remote: Boolean): JSONObject {
        // Older app versions accept two references. Keep the public protocol compatible,
        // and store additional learned examples in a field older readers safely ignore.
        val base = if (remote) (person.references.take(1) + person.references.takeLast(1)).distinct() else person.references
        val extra = if (remote) person.references.filter { it !in base } else emptyList()
        return JSONObject().put("id", person.id).put("name", person.name).put("pendingName", !remote && person.pendingName)
            .put("photoHashes", JSONArray(person.photoHashes.sorted())).put("excludedHashes", JSONArray(person.excludedHashes.sorted())).put("contentCounts", JSONObject(hashCounts.filterKeys { it in person.photoHashes }))
            .put("selfSelected", person.selfSelected).put("selfChangedAt", person.selfChangedAt)
            .put("deleted", person.deleted).put("reviewed", person.reviewed).put("different", JSONArray(person.different.sorted()))
            .put("excludedMembers", JSONArray(person.excludedMembers.sorted()))
            .put("faceCounts", JSONObject(faceCounts.filterKeys { it in person.members && (!remote || ForgottenVaultPolicy.validId(it)) }))
            .put("suggestion", if (remote) JSONObject.NULL else person.suggestion ?: JSONObject.NULL).put("model", person.embeddingModel).put("rescanAutomatic", person.rescanAutomatic)
            .put("remote", person.remote).put("dirty", person.dirty).put("members", JSONArray(person.members.filter { !remote || ForgottenVaultPolicy.validId(it) }))
            .put("pendingReview", if (remote || person.reviewVote.count != 1) JSONObject.NULL else JSONObject().put("target", person.reviewVote.target).put("same", person.reviewVote.same))
            .put("references", JSONArray(base.map(::sampleJson))).put("reviewReferences", JSONArray(extra.map(::sampleJson)))
    }
    private fun decode(data: JSONObject): Person {
        val id = data.getString("id")
        check(ForgottenVaultPolicy.validId(id))
        val model = data.getString("model")
        check(model == MODEL || model == LEGACY_MODEL)
        val name = data.getString("name")
        check(name.length <= 80)
        val person = Person(id, name, deleted = data.optBoolean("deleted"), remote = data.optLong("remote"), dirty = data.optBoolean("dirty"), embeddingModel = model, rescanAutomatic = data.optBoolean("rescanAutomatic", model == LEGACY_MODEL))
        try {
            val refs = data.getJSONArray("references")
            val extra = data.optJSONArray("reviewReferences")
            check(refs.length() <= 4 && (extra?.length() ?: 0) <= 2)
            if (extra != null) for (i in 0 until extra.length()) refs.put(extra.getJSONObject(i))
            check(refs.length() <= 4)
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
            for ((key, target) in listOf("photoHashes" to person.photoHashes, "excludedHashes" to person.excludedHashes)) {
                val rows = data.optJSONArray(key)
                check((rows?.length() ?: 0) <= 50000)
                if (rows != null) for (i in 0 until rows.length()) target += rows.getString(i).also { check(it.length in 1..128) }
            }
            val content = data.optJSONObject("contentCounts")
            check((content?.length() ?: 0) <= 50000)
            content?.takeIf { model == MODEL }?.keys()?.forEach { hash -> content.optInt(hash, -1).takeIf { hash in person.photoHashes && it in 0..32 }?.let { hashCounts[hash] = it } }
            val counts = data.optJSONObject("faceCounts")
            check((counts?.length() ?: 0) <= 50000)
            counts?.takeIf { model == MODEL }?.keys()?.forEach { source -> counts.optInt(source, -1).takeIf { it in 0..32 }?.let { faceCounts[source] = it } }
            val excluded = data.optJSONArray("excludedMembers")
            if (excluded != null) {
                check(excluded.length() <= 50000)
                for (i in 0 until excluded.length()) person.excludedMembers += excluded.getString(i).also { check(it.length <= 4096) }
            }
            person.selfSelected = data.optBoolean("selfSelected")
            person.selfChangedAt = data.optLong("selfChangedAt", 0).coerceIn(0, 4_102_444_800_000)
            person.pendingName = data.optBoolean("pendingName")
            person.reviewed = data.optBoolean("reviewed")
            val rejected = data.optJSONArray("different")
            if (rejected != null) {
                check(rejected.length() <= 1000)
                for (i in 0 until rejected.length()) rejected.getString(i).takeIf { ForgottenVaultPolicy.validId(it) && it != id }?.let { person.different += it }
            }
            val vote = data.optJSONObject("pendingReview")
            val voteTarget = vote?.optString("target").orEmpty()
            if (ForgottenVaultPolicy.validId(voteTarget) && voteTarget != id) person.reviewVote = FaceReviewVote(voteTarget, vote!!.getBoolean("same"), 1)
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
        val ownId = selectedSelf()?.id
        val previous = mutable.value.people.associateBy { it.id }
        val reused = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<ByteArray, Boolean>())
        val cards = persons.values.filter { !it.deleted && it.members.isNotEmpty() }.map { PersonCard(it.id, it.name, it.references.firstOrNull()?.jpeg?.let { jpeg -> previous[it.id]?.cover?.takeIf { cover -> cover.contentEquals(jpeg) }?.also { cover -> reused.add(cover) } ?: jpeg.clone() } ?: byteArrayOf(), it.members.toSet(), it.suggestion, app.televault.domain.FaceGrouping.provisional(it.name, it.reviewed || it.id == ownId, if (it.photoHashes.isNotEmpty()) it.photoHashes.size else it.members.size), it.embeddingModel, it.reviewVote.takeIf { vote -> vote.count == 1 && vote.target == it.suggestion }?.same) }.sortedWith(compareBy<PersonCard> { it.name.isBlank() }.thenBy { it.name }.thenBy { it.id })
        synchronized(publicationGuard) {
            if (generation.get() != token) {
                cards.forEach { it.cover.fill(0) }
                error("People session changed")
            }
            val old = mutable.value
            mutable.value = PeopleState(enabled, cards, scanned.size, busy, persons.values.any { it.dirty }, notice, faceCounts.filterKeys { !drive.privateAi.excluded(it) }.toMap(), ownId)
            old.people.filter { it.cover !in reused }.forEach { it.cover.fill(0) }
        }
    }
    private fun save(scope: Pair<Long, String>, token: Long) {
        access(scope, token)
        val body = JSONObject().put("version", 1).put("scanModel", scanModel).put("enabled", enabled).put("restorePending", restorePending).put("people", JSONArray(persons.values.map { encode(it, false) })).put("scanned", JSONObject(scanned.toMap())).put("scanQuality", JSONObject(scanQuality.toMap())).put("faceCounts", JSONObject(faceCounts.toMap())).put("sourceHashes", JSONObject(sourceHashes.toMap())).put("hashCounts", JSONObject(hashCounts.toMap())).toString().toByteArray()
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
    suspend fun activate(syncRemote: Boolean = true) = withContext(Dispatchers.IO) {
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
                        scanModel = body.optString("scanModel", LEGACY_MODEL)
                        check(body.getInt("version") == 1)
                        enabled = body.getBoolean("enabled")
                        restorePending = body.optBoolean("restorePending", false)
                        val rows = body.getJSONArray("people")
                        check(rows.length() <= 1000)
                        for (i in 0 until rows.length()) {
                            val person = decode(rows.getJSONObject(i))
                            persons[person.id] = person
                        }
                        val records = body.getJSONObject("scanned")
                        check(records.length() <= 50000)
                        records.keys().forEach { scanned[it] = records.getString(it) }
                        val quality = body.optJSONObject("scanQuality")
                        check((quality?.length() ?: 0) <= 50000)
                        quality?.keys()?.forEach { id -> scanQuality[id] = quality.optInt(id, 0).coerceIn(0, 2) }
                        val hashes = body.optJSONObject("sourceHashes")
                        check((hashes?.length() ?: 0) <= 50000)
                        hashes?.keys()?.forEach { id -> hashes.getString(id).takeIf { it.length in 1..128 }?.let { sourceHashes[id] = it } }
                        val contentCounts = body.optJSONObject("hashCounts")
                        check((contentCounts?.length() ?: 0) <= 50000)
                        contentCounts?.keys()?.forEach { hash -> contentCounts.optInt(hash, -1).takeIf { it in 0..32 }?.let { hashCounts[hash] = it } }
                        val counts = body.optJSONObject("faceCounts")
                        if (counts != null) {
                            check(counts.length() <= 50000)
                            counts.keys().forEach { id -> counts.optInt(id, -1).takeIf { it in 0..32 }?.let { faceCounts[id] = it } }
                        }
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
            if (!target.baseFile.exists() && !File(target.baseFile.path + ".bak").exists()) {
                enabled = true
                restorePending = true
                save(scope, token)
            }
            persons.values.forEach(::sanitize)
            publish(scope, token, if (restorePending && enabled) "Restoring encrypted People references before scanning…" else "")
            if (enabled && syncRemote) {
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
    suspend fun prepareForScan(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val scope = drive.privateVaultBinding()
            val token = generation.get()
            if (binding != scope || !enabled) return@withLock false
            if (restorePending) {
                try {
                    syncLocked(scope, token)
                    publish(scope, token)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    publish(scope, token, "Waiting to restore encrypted People references. Scanning resumes automatically when connected.")
                    return@withLock false
                }
            }
            !restorePending
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
                    val synced = withTimeoutOrNull(60_000) {
                        syncLocked(scope, token)
                        true
                    } == true
                    publish(scope, token, if (synced) "" else "Encrypted reference sync is pending; automatic scanning will retry.")
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
        val blockedMembers = person.members.filter { drive.privateAi.excluded(it, mediaHash(it)) || drive.privateAi.identity(it) in person.excludedMembers }.toSet()
        blockedMembers.filter { drive.privateAi.excluded(it, mediaHash(it)) }.forEach {
            faceCounts.remove(it)
            sourceHashes.remove(it)
        }
        var changed = person.photoHashes.removeAll { drive.privateAi.excluded("", it) || it in person.excludedHashes }
        if (person.excludedHashes.removeAll { drive.privateAi.excluded("", it) }) changed = true
        if (person.members.removeAll(blockedMembers)) changed = true
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
                sourceHashes.keys.filter { drive.privateAi.excluded(it, sourceHashes[it].orEmpty()) }.toList().forEach {
                    sourceHashes.remove(it)
                    faceCounts.remove(it)
                    scanned.remove(it)
                    scanQuality.remove(it)
                }
                hashCounts.keys.filter { drive.privateAi.excluded("", it) }.toList().forEach { hashCounts.remove(it) }
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
            if (old != null && old.embeddingModel == MODEL && incoming.embeddingModel != MODEL) {
                incoming.references.forEach { it.wipe() }
                incoming.references.clear()
                old.references.forEach { incoming.references += FaceSample(it.embedding.clone(), it.jpeg.clone(), it.source, it.contentHash) }
                incoming.embeddingModel = MODEL
                incoming.members += old.members
                incoming.photoHashes += old.photoHashes
                incoming.dirty = true
            }
            if (old != null && !old.deleted && !incoming.deleted) incoming.reviewVote = old.reviewVote
            if (old != null && old.selfChangedAt > incoming.selfChangedAt) {
                incoming.selfChangedAt = old.selfChangedAt
                incoming.selfSelected = old.selfSelected
                incoming.dirty = true
            }
            if (old?.dirty == true && !incoming.deleted && !old.deleted) {
                incoming.members += old.members
                incoming.different += old.different
                incoming.excludedMembers += old.excludedMembers
                incoming.photoHashes += old.photoHashes
                incoming.excludedHashes += old.excludedHashes
                incoming.reviewed = incoming.reviewed || old.reviewed
                incoming.dirty = true
                if (old.pendingName || old.remote == 0L && old.name.isNotBlank()) {
                    incoming.name = old.name
                    incoming.pendingName = old.pendingName
                }
                if (old.reviewed && old.embeddingModel == incoming.embeddingModel) {
                    incoming.references.forEach { it.wipe() }
                    incoming.references.clear()
                    old.references.forEach { incoming.references += FaceSample(it.embedding.clone(), it.jpeg.clone(), it.source, it.contentHash) }
                }
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
        restorePending = false
        save(scope, token)
        for (person in persons.values.filter { it.dirty && (it.name.isNotBlank() || it.deleted || it.remote > 0 || it.reviewed || it.different.isNotEmpty() || it.members.any(ForgottenVaultPolicy::validId) || it.photoHashes.isNotEmpty() || it.selfChangedAt > 0) }) {
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
                person.pendingName = false
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
    suspend fun prepareAnalysis(): Boolean {
        if (!models.prepareInstalled() || !prepareForScan()) return false
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                val scope = drive.privateVaultBinding()
                val token = generation.get()
                if (!enabled || binding != scope) return@withLock false
                if (scanModel != MODEL) {
                    scanned.clear()
                    scanQuality.clear()
                    faceCounts.clear()
                    hashCounts.clear()
                    scanModel = MODEL
                }
                for (person in persons.values.filter { !it.deleted && it.embeddingModel != MODEL }) {
                    currentCoroutineContext().ensureActive()
                    access(scope, token)
                    val converted = mutableListOf<FaceSample>()
                    try {
                        for (sample in person.references) converted += app.televault.OnDeviceAnalysis.run { (engine ?: FaceEngine(context).also { engine = it }).upgrade(sample) }
                    } catch (error: Throwable) {
                        converted.forEach { it.wipe() }
                        throw error
                    }
                    person.references.forEach { it.wipe() }
                    person.references.clear()
                    person.references += converted
                    person.embeddingModel = MODEL
                    person.dirty = true
                    save(scope, token)
                    publish(scope, token, "Upgrading face references on your phone…", busy = true)
                    delay(20)
                }
                consolidateAutomaticGroups()
                save(scope, token)
                publish(scope, token)
                true
            }
        }
    }
    private fun consolidateAutomaticGroups() {
        val ownId = selectedSelf()?.id
        val pendingReviewIds = persons.values.filter { !it.deleted && it.reviewVote.count == 1 }.flatMap { listOf(it.id, it.reviewVote.target) }.toSet()
        val sources = persons.values.filter { it.id !in pendingReviewIds && !it.deleted && app.televault.domain.FaceGrouping.automatic(it.name, it.reviewed, it.id == ownId) && it.embeddingModel == MODEL }
        for (source in sources) {
            if (source.deleted || source.references.isEmpty()) continue
            val ranked = persons.values.asSequence().filter { target ->
                target.id !in pendingReviewIds && !target.deleted && target.id != source.id && target.embeddingModel == MODEL && target.references.isNotEmpty() && target.id !in source.different && source.id !in target.different && !app.televault.domain.FaceGrouping.cooccur(source.members, target.members, source.photoHashes, target.photoHashes) && !app.televault.domain.FaceGrouping.conflictingTags(source.excludedHashes, target.photoHashes, target.excludedHashes, source.photoHashes) &&
                    (target.name.isNotBlank() || target.reviewed || target.id == ownId || target.members.size > source.members.size || target.members.size == source.members.size && target.id < source.id)
            }.map { target -> target to source.references.maxOf { a -> target.references.maxOf { b -> FaceSimilarity.cosine(a.embedding, b.embedding) } } }.sortedByDescending { it.second }.toList()
            val best = ranked.firstOrNull() ?: continue
            if (best.second < .66f) continue
            val namedRival = ranked.drop(1).any { it.first.name.isNotBlank() && it.second > best.second - .08f }
            if (app.televault.domain.FaceGrouping.canCombine(best.second, app.televault.domain.FaceGrouping.cooccur(source.members, best.first.members, source.photoHashes, best.first.photoHashes), best.first.id in source.different || source.id in best.first.different, namedRival)) mergeLocked(source, best.first, confirmed = false)
        }
    }
    suspend fun analyze(id: String, stamp: String, ownedSource: Boolean = false, load: suspend () -> Bitmap?): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val scope = drive.privateVaultBinding()
            val token = generation.get()
            if (!enabled || restorePending || binding != scope || scanModel != MODEL) return@withLock false
            val upgrading = ownedSource && scanned[id] != null && (scanQuality[id] ?: 0) < 2
            val knownFaces = faceCounts[id]?.takeIf { it > 0 } ?: if (persons.values.any { !it.deleted && id in it.members }) 1 else 0
            if (!app.televault.domain.FaceScanCache.shouldScan(scanned[id], stamp, ForgottenVaultPolicy.validId(id), knownFaces, scanQuality[id] ?: 0, ownedSource)) {
                if (id !in sourceHashes) {
                    val hash = drive.intelligenceContentHash(id)
                    if (hash.isNotEmpty() && !drive.privateAi.excluded(id, hash)) {
                        sourceHashes[id] = hash
                        faceCounts[id]?.let { hashCounts[hash] = it }
                        persons.values.filter { !it.deleted && id in it.members && hash !in it.excludedHashes }.forEach { if (it.photoHashes.add(hash)) it.dirty = true }
                        changedSinceSave++
                        if (changedSinceSave % 10 == 0) save(scope, token)
                    }
                }
                return@withLock false
            }
            if (!upgrading && ForgottenVaultPolicy.validId(id) && id in faceCounts && persons.values.any { !it.deleted && it.references.isNotEmpty() && id in it.members }) {
                scanned[id] = stamp
                scanQuality[id] = 2
                changedSinceSave++
                if (changedSinceSave % 10 == 0) save(scope, token)
                return@withLock false
            }
            check(scanned.size < 50000) { "People index limit reached. Remove unused groups to continue." }
            if (changedSinceSave == 0) publish(scope, token, busy = true)
            if (!drive.normalIntelligenceMedia(id))return@withLock false
            val contentHash = drive.intelligenceContentHash(id)
            if (drive.privateAi.excluded(id, contentHash))return@withLock false
            if (contentHash.isNotEmpty()) {
                sourceHashes[id] = contentHash
                val restored = persons.values.filter { !it.deleted && contentHash in it.photoHashes && contentHash !in it.excludedHashes && drive.privateAi.identity(id) !in it.excludedMembers && contentHash !in it.excludedHashes }
                val count = hashCounts[contentHash]
                if (!upgrading && restored.isNotEmpty() && count != null) {
                    restored.forEach { if (it.members.add(id) && ForgottenVaultPolicy.validId(id)) it.dirty = true }
                    faceCounts[id] = count
                    scanned[id] = stamp
                    scanQuality[id] = 2
                    changedSinceSave++
                    if (changedSinceSave % 10 == 0) save(scope, token)
                    if (changedSinceSave % 10 == 0) publish(scope, token)
                    return@withLock true
                }
            }
            val samples = app.televault.OnDeviceAnalysis.run {
                val image = load() ?: return@run null
                try {
                    (engine ?: FaceEngine(context).also { engine = it }).faces(image)
                } finally {
                    if (ownedSource) {
                        image.eraseColor(0)
                        image.recycle()
                    }
                }
            } ?: return@withLock false
            try {
                access(scope, token)
                for (person in persons.values.filter { !it.deleted && (it.rescanAutomatic || upgrading) && it.name.isBlank() && !it.reviewed && it.id != selectedSelf()?.id && id in it.members }) {
                    person.members.remove(id)
                    if (contentHash.isNotEmpty() && person.members.none { mediaHash(it) == contentHash }) person.photoHashes.remove(contentHash)
                    person.dirty = true
                }
                val awaiting = persons.values.filter { !it.deleted && it.references.isEmpty() && id in it.members }
                if (samples.size == 1 && awaiting.size == 1) {
                    val sample = samples.first()
                    awaiting.first().references += FaceSample(sample.embedding.clone(), sample.jpeg.clone(), drive.privateAi.identity(id), contentHash)
                    awaiting.first().dirty = true
                }
                val assigned = hashSetOf<String>()
                for (sample in samples) {
                    val ranked = persons.values.filter { !it.deleted && it.embeddingModel == MODEL && it.references.isNotEmpty() && it.id !in assigned && drive.privateAi.identity(id) !in it.excludedMembers && contentHash !in it.excludedHashes }.map { person -> person to person.references.maxOf { FaceSimilarity.cosine(it.embedding, sample.embedding) } }.sortedByDescending { it.second }
                    val best = ranked.firstOrNull()
                    val second = ranked.getOrNull(1)?.second ?: -1f
                    if (best != null && FaceSimilarity.confident(best.second, second, best.first.references.size) && (ranked.getOrNull(1)?.first?.id !in best.first.different || best.second - second >= .20f)) {
                        if (best.first.members.add(id)) best.first.dirty = true
                        if (contentHash.isNotEmpty()) best.first.photoHashes += contentHash
                        if (best.second >= .72f && best.second - second >= .10f && best.first.references.none { FaceSimilarity.cosine(it.embedding, sample.embedding) > .96f }) {
                            if (best.first.references.size == 4) best.first.references.removeAt(1).wipe()
                            best.first.references += FaceSample(sample.embedding.clone(), sample.jpeg.clone(), drive.privateAi.identity(id), contentHash)
                            best.first.dirty = true
                        }
                        assigned += best.first.id
                    } else {
                        check(persons.size < 1000 && persons.count { !it.value.deleted } < 512) { "People group limit reached" }
                        val person = Person(UUID.randomUUID().toString(), references = mutableListOf(FaceSample(sample.embedding.clone(), sample.jpeg.clone(), drive.privateAi.identity(id), contentHash)), members = linkedSetOf(id), dirty = true, suggestion = best?.takeIf { it.second >= .40f }?.first?.id ?: awaiting.singleOrNull()?.id)
                        if (contentHash.isNotEmpty()) person.photoHashes += contentHash
                        persons[person.id] = person
                        assigned += person.id
                    }
                }
                faceCounts[id] = samples.size
                if (contentHash.isNotEmpty()) hashCounts[contentHash] = samples.size
                scanned[id] = stamp
                scanQuality[id] = 2
                changedSinceSave++
                if (changedSinceSave % 10 == 0) save(scope, token)
                if (changedSinceSave % 20 == 0) consolidateAutomaticGroups()
                if (changedSinceSave % 10 == 0) publish(scope, token)
                true
            } finally {
                samples.forEach { it.wipe() }
            }
        }
    }
    suspend fun finishScan() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val scope = drive.privateVaultBinding()
            val token = generation.get()
            if (binding != scope) return@withLock
            if (changedSinceSave > 0) {
                save(scope, token)
                changedSinceSave = 0
            }
            publish(scope, token)
        }
    }
    private fun selectedSelf(): Person? {
        val id = app.televault.domain.SelfIdentityChoice.current(persons.values.map { app.televault.domain.SelfIdentityChoice.Choice(it.id, it.selfSelected, it.selfChangedAt, it.deleted) })
        return persons[id]
    }
    suspend fun selectSelf(id: String, selected: Boolean) = mutate(localFirst = true) {
        val person = persons.getValue(id)
        check(!person.deleted)
        // One encrypted last-writer choice; no identity is inferred without the user's choice.
        person.selfChangedAt = maxOf(System.currentTimeMillis(), Math.addExact(persons.values.maxOfOrNull { it.selfChangedAt } ?: 0, 1))
        person.selfSelected = selected
        if (selected) person.reviewed = true
        person.dirty = true
    }
    suspend fun rename(id: String, name: String) = mutate(localFirst = true) {
        val person = persons.getValue(id)
        check(!person.deleted)
        person.name = name.trim().take(80)
        person.reviewed = true
        person.pendingName = true
        person.suggestion = null
        person.dirty = true
    }
    suspend fun tag(id: String, media: Set<String>) = mutate(localFirst = true) {
        val person = persons.getValue(id)
        check(!person.deleted)
        person.reviewed = true
        person.members += media
        media.map(::mediaHash).filter { it.isNotEmpty() }.forEach {
            person.photoHashes += it
            person.excludedHashes.remove(it)
        }
        person.excludedMembers.removeAll(media.map { drive.privateAi.identity(it) }.toSet())
        person.dirty = true
    }
    suspend fun untag(id: String, media: String) = mutate(localFirst = true) {
        val person = persons.getValue(id)
        person.members.remove(media)
        mediaHash(media).takeIf { it.isNotEmpty() }?.let {
            person.photoHashes.remove(it)
            person.excludedHashes += it
        }
        person.excludedMembers += drive.privateAi.identity(media)
        val refs = person.references.filter { it.source == drive.privateAi.identity(media) }
        person.references.removeAll(refs.toSet())
        refs.forEach { it.wipe() }
        person.dirty = true
    }
    suspend fun moveTag(source: String, target: String, media: String) = mutate(localFirst = true) {
        val from = persons.getValue(source)
        val into = persons.getValue(target)
        check(!from.deleted && !into.deleted && from.id != into.id && media in from.members)
        val refs = from.references.filter { it.source == drive.privateAi.identity(media) }
        for (sample in refs.filter { from.embeddingModel == into.embeddingModel }) {
            if (into.references.size == 4) into.references.removeAt(1).wipe()
            into.references += FaceSample(sample.embedding.clone(), sample.jpeg.clone(), sample.source, sample.contentHash)
        }
        from.references.removeAll(refs.toSet())
        refs.forEach { it.wipe() }
        val hash = mediaHash(media)
        if (hash.isNotEmpty()) {
            from.photoHashes.remove(hash)
            from.excludedHashes += hash
            into.photoHashes += hash
            into.excludedHashes.remove(hash)
        }
        from.members.remove(media)
        from.excludedMembers += drive.privateAi.identity(media)
        into.excludedMembers.remove(drive.privateAi.identity(media))
        into.members += media
        into.reviewed = true
        from.different += into.id
        into.different += from.id
        into.dirty = true
        from.dirty = true
    }
    suspend fun separateTag(source: String, media: String) = mutate(localFirst = true) {
        val from = persons.getValue(source)
        check(!from.deleted && media in from.members)
        val refs = from.references.filter { it.source == drive.privateAi.identity(media) }
        val group = Person(UUID.randomUUID().toString(), members = linkedSetOf(media), dirty = true, reviewed = true, embeddingModel = from.embeddingModel)
        refs.forEach { group.references += FaceSample(it.embedding.clone(), it.jpeg.clone(), it.source, it.contentHash) }
        from.references.removeAll(refs.toSet())
        refs.forEach { it.wipe() }
        mediaHash(media).takeIf { it.isNotEmpty() }?.let {
            from.photoHashes.remove(it)
            from.excludedHashes += it
            group.photoHashes += it
        }
        from.members.remove(media)
        from.excludedMembers += drive.privateAi.identity(media)
        from.different += group.id
        group.different += from.id
        from.dirty = true
        persons[group.id] = group
    }
    suspend fun refreshFaceTags() = mutate(localFirst = true) {
        scanned.keys.filter { id -> (faceCounts[id] ?: 0) == 0 && persons.values.none { !it.deleted && id in it.members } }.toList().forEach { scanned.remove(it) }
    }

    /** Rebuild derived scans. Only explicit full reset removes identities, via encrypted tombstones. */
    suspend fun resetRecognition(keepIdentities: Boolean) = mutate(localFirst = true) {
        scanned.clear()
        scanQuality.clear()
        faceCounts.clear()
        hashCounts.clear()
        changedSinceSave = 0
        if (keepIdentities) {
            persons.values.filter { !it.deleted }.forEach {
                it.rescanAutomatic = true
                it.suggestion = null
                it.dirty = true
            }
        } else {
            persons.values.filter { !it.deleted }.forEach {
                it.wipe()
                it.deleted = true
                it.selfSelected = false
                it.selfChangedAt = maxOf(System.currentTimeMillis(), it.selfChangedAt + 1)
                it.dirty = true
            }
            sourceHashes.clear()
        }
    }
    private fun refreshSuggestions() {
        val active = persons.values.filter { !it.deleted && it.embeddingModel == MODEL && it.references.isNotEmpty() }
        for (person in active) {
            val pendingTarget = active.find { it.id == person.reviewVote.target }
            if (person.reviewVote.count == 1 && pendingTarget != null && pendingTarget.id != person.id) {
                person.suggestion = pendingTarget.id
                continue
            }
            if (person.reviewVote.count == 1) person.reviewVote = FaceReviewVote()
            if (person.reviewed && person.suggestion == null) continue
            person.suggestion = active.asSequence().filter { other ->
                other.id != person.id && other.id !in person.different && person.id !in other.different && !app.televault.domain.FaceGrouping.cooccur(person.members, other.members, person.photoHashes, other.photoHashes) && !app.televault.domain.FaceGrouping.conflictingTags(person.excludedHashes, other.photoHashes, other.excludedHashes, person.photoHashes)
            }.map { other -> other to person.references.maxOf { a -> other.references.maxOf { b -> FaceSimilarity.cosine(a.embedding, b.embedding) } } }
                .filter { it.second >= .40f }.maxByOrNull { it.second }?.first?.id
        }
    }
    private fun mergeLocked(source: Person, target: Person, confirmed: Boolean = true) {
        check(source.id != target.id && !source.deleted && !target.deleted)
        if (selectedSelf()?.id == source.id) {
            target.selfSelected = true
            target.selfChangedAt = Math.addExact(persons.values.maxOfOrNull { it.selfChangedAt } ?: 0, 1)
        }
        target.members += source.members
        target.photoHashes += source.photoHashes
        target.excludedHashes += source.excludedHashes
        target.excludedHashes.removeAll(target.photoHashes)
        target.excludedMembers += source.excludedMembers
        target.excludedMembers.removeAll(target.members.map { drive.privateAi.identity(it) }.toSet())
        target.different += source.different.filter { it != target.id }
        target.different.remove(source.id)
        if (target.name.isBlank()) {
            target.name = source.name
            target.pendingName = true
        }
        // Never mix embeddings from different model spaces during a manual merge or restore.
        if (target.embeddingModel != source.embeddingModel && source.embeddingModel == MODEL) {
            target.references.forEach { it.wipe() }
            target.references.clear()
            target.embeddingModel = MODEL
        }
        // Keep the anchor and diverse confirmed examples; positive reviews update future matching.
        for (sample in source.references.filter { app.televault.domain.FaceGrouping.compatible(source.embeddingModel, target.embeddingModel) }) {
            if (target.references.none { FaceSimilarity.cosine(it.embedding, sample.embedding) > .995f }) {
                if (target.references.size == 4) target.references.removeAt(1).wipe()
                target.references += FaceSample(sample.embedding.clone(), sample.jpeg.clone(), sample.source, sample.contentHash)
            }
        }
        for (person in persons.values) {
            if (person.different.remove(source.id)) {
                if (person.id != target.id) person.different += target.id
                person.dirty = true
            }
            if (person.suggestion == source.id) person.suggestion = target.id.takeIf { it != person.id }
        }
        target.reviewed = target.reviewed || confirmed
        target.suggestion = null
        target.dirty = true
        source.wipe()
        source.deleted = true
        source.suggestion = null
        source.dirty = true
    }
    suspend fun merge(source: String, target: String) = mutate(localFirst = true) { mergeLocked(persons.getValue(source), persons.getValue(target)) }
    suspend fun review(id: String, same: Boolean, expectedTarget: String? = null) = mutate(localFirst = true) {
        val person = persons.getValue(id)
        check(expectedTarget == null || person.suggestion == expectedTarget) { "This match changed; review the new pair" }
        val target = persons[expectedTarget ?: person.suggestion]
        check(target != null && !target.deleted && !person.deleted && target.id != person.id) { "This pair is no longer available" }
        person.reviewVote = person.reviewVote.next(target.id, same)
        // First/contradictory answers are encrypted local draft votes only. They must
        // not train references, reject a match, create a profile or send cloud truth.
        if (!person.reviewVote.confirmed) return@mutate
        person.reviewVote = FaceReviewVote()
        if (same) {
            mergeLocked(person, target)
        } else {
            person.different += target.id
            target.different += person.id
            target.dirty = true
            person.dirty = true
            person.reviewed = true
            person.suggestion = null
        }
    }
    suspend fun remove(id: String) = mutate(localFirst = true) {
        persons.getValue(id).apply {
            wipe()
            deleted = true
            dirty = true
        }
    }
    private suspend fun mutate(localFirst: Boolean = false, action: () -> Unit) = withContext(Dispatchers.IO) {
        if (localFirst) reviewSync?.cancel()
        mutex.withLock {
            val scope = drive.privateVaultBinding()
            val token = generation.get()
            check(binding == scope)
            // Review answers are saved locally first; debounce encrypted sync while reviewing a queue.
            if (localFirst) {
                reviewSync?.cancel()
                action()
                persons.values.forEach(::sanitize)
                refreshSuggestions()
                save(scope, token)
                publish(scope, token)
                reviewSync = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    delay(5000)
                    try {
                        sync()
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) { }
                }
                return@withLock
            }
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
        scanQuality.clear()
        scanModel = MODEL
        faceCounts.clear()
        sourceHashes.clear()
        hashCounts.clear()
        enabled = false
        restorePending = true
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
        reviewSync?.cancel()
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
        const val MODEL = "opencv-yunet-sface-0ba9fbfa-v2"
        const val LEGACY_MODEL = "mobile-facenet-2046ab77-v1"
        private const val MAX_REMOTE = 2L * 1024 * 1024
    }
}
