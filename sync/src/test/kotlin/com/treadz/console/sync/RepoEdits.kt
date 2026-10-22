package com.treadz.console.sync

import java.security.MessageDigest

private fun sha1(seed: String): String = MessageDigest.getInstance("SHA-1").digest(seed.toByteArray()).joinToString("") { "%02x".format(it) }

/** Adds or replaces files of the main branch with one new commit made by "somebody else" (another device, or by hand). */
internal fun FakeGitHub.Repo.editFiles(replace: Map<String, String> = emptyMap(), remove: Set<String> = emptySet(), message: String = "edited by hand") {
    val head = refs.getValue("main")
    var files = trees.getValue(commits.getValue(head).tree)
    for ((path, text) in replace) {
        val sha = sha1(path + text.length + text.hashCode())
        blobs[sha] = text.toByteArray()
        files = files + (path to sha)
    }
    files = files - remove
    val tree = sha1("tree$files$head")
    trees[tree] = files
    val commit = sha1("commit$tree$head$message")
    commits[commit] = FakeGitHub.Commit(tree, listOf(head), message)
    refs["main"] = commit
}

internal fun FakeGitHub.Repo.replaceFile(path: String, text: String) = editFiles(replace = mapOf(path to text))

internal fun FakeGitHub.Repo.removeFile(path: String) = editFiles(remove = setOf(path))
