package me.fleey.futon.data.daemon.annotations

/**
 * Annotation to trigger generation of a Kotlin wrapper for an AIDL interface.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class AidlWrapper(
  val aidlClassName: String,
)

/**
 * Interface for executing AIDL calls with custom logic (e.g., error handling, timeouts).
 */
interface AidlCallExecutor<T> {
  suspend fun <R> execute(block: (T) -> R): Result<R>
}

