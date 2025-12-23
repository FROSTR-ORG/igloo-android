package com.frostr.igloo.bridges

/**
 * Result type for storage operations.
 *
 * Replaces ambiguous null returns with explicit error handling.
 * This allows callers to distinguish between:
 * - Value exists (Success)
 * - Key not found (NotFound)
 * - Operation failed (Error)
 *
 * Usage:
 * ```kotlin
 * when (val result = storageBridge.getItemResult("local", "key")) {
 *     is StorageResult.Success -> println("Found: ${result.value}")
 *     is StorageResult.NotFound -> println("Key not found: ${result.key}")
 *     is StorageResult.Error -> println("Error: ${result.message}")
 * }
 * ```
 */
sealed class StorageResult<out T> {
    /**
     * Operation succeeded with a value.
     */
    data class Success<T>(val value: T) : StorageResult<T>()

    /**
     * Key was not found in storage.
     */
    data class NotFound(val key: String) : StorageResult<Nothing>()

    /**
     * An error occurred during the storage operation.
     */
    data class Error(val message: String, val cause: Exception? = null) : StorageResult<Nothing>()

    /**
     * Get the value or null if not successful.
     */
    fun getOrNull(): T? = when (this) {
        is Success -> value
        else -> null
    }

    /**
     * Get the value or throw if not successful.
     *
     * @throws NoSuchElementException if key not found
     * @throws StorageException if an error occurred
     */
    fun getOrThrow(): T = when (this) {
        is Success -> value
        is NotFound -> throw NoSuchElementException("Key not found: $key")
        is Error -> throw StorageException(message, cause)
    }

    /**
     * Get the value or a default if not successful.
     */
    fun getOrDefault(default: @UnsafeVariance T): T = when (this) {
        is Success -> value
        else -> default
    }

    /**
     * Transform the value if successful.
     */
    inline fun <R> map(transform: (T) -> R): StorageResult<R> = when (this) {
        is Success -> Success(transform(value))
        is NotFound -> this
        is Error -> this
    }

    /**
     * Execute block if successful.
     */
    inline fun onSuccess(block: (T) -> Unit): StorageResult<T> {
        if (this is Success) block(value)
        return this
    }

    /**
     * Execute block if not found.
     */
    inline fun onNotFound(block: (String) -> Unit): StorageResult<T> {
        if (this is NotFound) block(key)
        return this
    }

    /**
     * Execute block if error.
     */
    inline fun onError(block: (String, Exception?) -> Unit): StorageResult<T> {
        if (this is Error) block(message, cause)
        return this
    }

    /**
     * Check if operation was successful.
     */
    val isSuccess: Boolean get() = this is Success

    /**
     * Check if key was not found.
     */
    val isNotFound: Boolean get() = this is NotFound

    /**
     * Check if an error occurred.
     */
    val isError: Boolean get() = this is Error
}

/**
 * Exception thrown when storage operation fails.
 */
class StorageException(message: String, cause: Exception? = null) : Exception(message, cause)
