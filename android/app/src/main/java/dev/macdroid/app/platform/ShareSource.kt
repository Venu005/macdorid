package dev.macdroid.app.platform

/**
 * How to keep reading a file the user shared (spec §4.2, ruling 2). Keep the URI when access persists. Otherwise copy
 * small files to cache, and read large ones in place while the service holds the temporary grant
 * (copying a multi-GB video would double the storage used).
 */
enum class ShareSource {
    Keep, Copy;

    companion object {
        fun plan(persisted: Boolean, size: Long, copyLimit: Long): ShareSource =
            if (!persisted && size <= copyLimit) Copy else Keep
    }
}
