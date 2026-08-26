package com.manga.translate.platform

import androidx.annotation.StringRes

/**
 * A file-import failure that carries a user-facing resource id.
 * It is used by the import pipeline so callers can show a specific
 * message instead of a generic "import failed".
 */
internal class ImportFileException(
    @param:StringRes val messageRes: Int,
    cause: Throwable? = null
) : Exception(cause)
