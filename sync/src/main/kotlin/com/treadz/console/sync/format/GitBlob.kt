package com.treadz.console.sync.format

import java.security.MessageDigest

/** Git object ids computed locally, to know whether a file changed without uploading it. */
internal object GitBlob {
    /** SHA-1 of `blob <size>\0<content>`, as Git (and GitHub) name a file's content. */
    fun sha(content: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update("blob ${content.size}\u0000".toByteArray(Charsets.US_ASCII))
        digest.update(content)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
