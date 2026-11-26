package app.prodl.server

import app.prodl.core.FileRef
import app.prodl.core.ProgressEvent
import app.prodl.core.TaskSnapshot
import app.prodl.core.TaskStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

fun nowSeconds(): Double = System.currentTimeMillis() / 1000.0

/**
 * One unit of background work. Progress is broadcast through a SharedFlow with
 * replay=1, which gives every new subscriber the current snapshot for free.
 */
class Task(val id: String, val kind: String, private val onChange: () -> Unit = {}) {
    val createdAt: Double = nowSeconds()

    @Volatile
    var status: TaskStatus = TaskStatus.QUEUED

    @Volatile
    var updatedAt: Double = createdAt

    @Volatile
    var error: String? = null

    @Volatile
    var file: FileRef? = null

    @Volatile
    var filePath: File? = null

    @Volatile
    var lastEvent: ProgressEvent? = null

    @Volatile
    var title: String? = null

    @Volatile
    var processId: String? = null

    @Volatile
    var job: Job? = null

    @Volatile
    var ffmpegProcess: Process? = null

    val cancelRequested = AtomicBoolean(false)

    private val sequence = AtomicLong(0)

    val events: SharedFlow<ProgressEvent> = MutableSharedFlow<ProgressEvent>(
        replay = 1,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val emitter = events as MutableSharedFlow<ProgressEvent>

    val isDone: Boolean get() = status.isTerminal

    /** Publishes a progress event; the first terminal event wins. Thread safe. */
    fun publish(event: ProgressEvent) {
        if (isDone) return
        val stamped = event.copy(
            taskId = id,
            seq = sequence.incrementAndGet(),
            timestamp = nowSeconds(),
            title = event.title ?: title,
            elapsed = event.elapsed ?: (nowSeconds() - createdAt),
        )
        if (event.title != null) title = event.title
        status = stamped.status
        updatedAt = nowSeconds()
        lastEvent = stamped
        emitter.tryEmit(stamped)
        onChange()
    }

    fun finish(reference: FileRef, path: File) {
        file = reference
        filePath = path
        publish(
            ProgressEvent(
                status = TaskStatus.FINISHED,
                percent = 100.0,
                message = "Ready",
                file = reference,
                filename = reference.filename,
            )
        )
    }

    fun fail(message: String) {
        error = message
        publish(ProgressEvent(status = TaskStatus.ERROR, error = message, message = message))
    }

    fun markCanceled() {
        cancelRequested.set(true)
        publish(ProgressEvent(status = TaskStatus.CANCELED, message = "Canceled"))
    }

    fun snapshot(): TaskSnapshot = TaskSnapshot(
        taskId = id,
        kind = kind,
        status = status,
        createdAt = createdAt,
        updatedAt = updatedAt,
        error = error,
        file = file,
        lastEvent = lastEvent,
    )
}

class TaskManager(
    private val maxTasks: Int = 200,
    private val ttlSeconds: Long = 3600,
) {
    private val tasks = ConcurrentHashMap<String, Task>()

    /**
     * Bumped on every create/publish/finish, so the native UI can derive its
     * task list from a StateFlow instead of polling or opening a WebSocket.
     */
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    fun create(kind: String): Task {
        prune()
        val task = Task(UUID.randomUUID().toString().replace("-", ""), kind) { bump() }
        tasks[task.id] = task
        bump()
        return task
    }

    fun get(id: String): Task? = tasks[id]

    fun all(): List<Task> = tasks.values.sortedByDescending { it.createdAt }

    /** Forget a task and delete the file it produced. */
    fun remove(id: String): Boolean {
        val task = tasks.remove(id) ?: return false
        task.job?.cancel()
        task.filePath?.delete()
        bump()
        return true
    }

    private fun bump() {
        _revision.value = _revision.value + 1
    }

    fun cancel(id: String): Task? {
        val task = tasks[id] ?: return null
        task.markCanceled()
        runCatching { task.ffmpegProcess?.destroy() }
        task.job?.cancel()
        return task
    }

    /** Drop finished tasks past their TTL and enforce the hard cap. */
    fun prune() {
        val now = nowSeconds()
        val expired = tasks.values.filter { it.isDone && now - it.updatedAt > ttlSeconds }
        expired.forEach { task ->
            task.filePath?.delete()
            tasks.remove(task.id)
        }
        if (tasks.size > maxTasks) {
            tasks.values
                .filter { it.isDone }
                .sortedBy { it.updatedAt }
                .take(tasks.size - maxTasks)
                .forEach { tasks.remove(it.id) }
        }
    }
}
