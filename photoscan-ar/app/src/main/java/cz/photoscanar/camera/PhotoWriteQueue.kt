package cz.photoscanar.camera

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The disk writer: a bounded queue drained by dedicated threads.
 *
 * This is the one part of the app that must never be blocked by anything else.
 * AR updates, quality analysis and tag detection all run elsewhere; a photo goes
 * from the ImageReader straight into this queue and onto storage.
 *
 * The queue is bounded on purpose. If writing cannot keep up, [submit] blocks the
 * camera callback thread, which stops new captures being acquired and makes the
 * back pressure visible to the rate controller - far better than silently
 * dropping photos or running the heap out of memory.
 */
class PhotoWriteQueue(
    private val threadCount: Int = 2,
    val capacity: Int = 16
) {
    class Task(
        val bytes: ByteArray,
        val target: File,
        val onWritten: (File, Long) -> Unit,
        val onError: (File, Exception) -> Unit
    )

    private val queue = ArrayBlockingQueue<Task>(capacity)
    private val workers = ArrayList<Thread>(threadCount)
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)
    private val inFlight = AtomicInteger(0)

    val bytesWritten = AtomicLong(0)
    val filesWritten = AtomicInteger(0)
    val writeFailures = AtomicInteger(0)
    val maxObservedDepth = AtomicInteger(0)
    val totalBlockedMs = AtomicLong(0)

    val depth: Int get() = queue.size + inFlight.get()

    /** 0..1 saturation, which the rate controller uses to back off. */
    val fill: Double get() = (depth.toDouble() / capacity).coerceIn(0.0, 1.0)

    fun start() {
        if (running.getAndSet(true)) return
        for (i in 0 until threadCount) {
            val t = Thread({ loop() }, "photo-writer-$i")
            t.priority = Thread.NORM_PRIORITY + 1
            t.isDaemon = false
            workers.add(t)
            t.start()
        }
    }

    private fun loop() {
        while (running.get() || queue.isNotEmpty()) {
            val task = try {
                queue.poll(200, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                null
            } ?: continue
            inFlight.incrementAndGet()
            try {
                task.target.parentFile?.mkdirs()
                FileOutputStream(task.target).use { out ->
                    out.write(task.bytes)
                    out.flush()
                }
                val len = task.target.length()
                bytesWritten.addAndGet(len)
                filesWritten.incrementAndGet()
                task.onWritten(task.target, len)
            } catch (e: Exception) {
                writeFailures.incrementAndGet()
                Log.e(TAG, "write failed: ${task.target.name}", e)
                try { task.onError(task.target, e) } catch (_: Exception) { }
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    /**
     * Enqueues a photo, blocking the caller while the queue is full.
     * @return false only if the queue was shut down
     */
    fun submit(task: Task): Boolean {
        if (!running.get()) return false
        val d = depth
        if (d > maxObservedDepth.get()) maxObservedDepth.set(d)
        if (queue.offer(task)) return true
        val t0 = System.currentTimeMillis()
        try {
            // bounded wait so a wedged filesystem cannot hang the camera thread forever
            val ok = queue.offer(task, 5, TimeUnit.SECONDS)
            totalBlockedMs.addAndGet(System.currentTimeMillis() - t0)
            if (!ok) {
                writeFailures.incrementAndGet()
                Log.e(TAG, "queue stayed full for 5 s, photo dropped: ${task.target.name}")
            }
            return ok
        } catch (_: InterruptedException) {
            return false
        }
    }

    /** Waits for the queue to empty. */
    fun drain(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (depth > 0 && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(25) } catch (_: InterruptedException) { return false }
        }
        return depth == 0
    }

    fun shutdown(drainTimeoutMs: Long = 20_000) {
        drain(drainTimeoutMs)
        running.set(false)
        for (t in workers) {
            try { t.join(2000) } catch (_: InterruptedException) { }
        }
        workers.clear()
    }

    private companion object {
        const val TAG = "PhotoWriteQueue"
    }
}
