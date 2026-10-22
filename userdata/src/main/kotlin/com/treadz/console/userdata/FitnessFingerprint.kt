package com.treadz.console.userdata

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.math.BigDecimal
import java.security.MessageDigest

/**
 * The fingerprint of the inputs of a user's derived fitness profile (`fitness.json` in the data repository): a SHA-256
 * of the **id and content of every fitness test result** of the user present on the device (deleted ones are not
 * present) and of the **version of the calculation rules** ([FitnessProfiles.RULES_VERSION]).
 *
 * It is deterministic, so every device that has the same results computes the same fingerprint:
 *  - results are taken in the order of their id;
 *  - the content is read as JSON and written again in a canonical form (fields sorted by name, no spaces, numbers in
 *    plain decimal notation), so two documents that mean the same hash the same whatever the order of their fields or
 *    the way a device formatted their numbers;
 *  - nothing else goes in: not the versions (`updated_at`/`updated_by`) of the results, not the sex or the birth date
 *    of the user, not the weights, the resting heart rates or anything the fitness profile does not depend on.
 */
object FitnessFingerprint {

    /** [results]: `(id, document)` of each result, in any order. */
    fun of(results: List<Pair<String, String>>, rulesVersion: Int = FitnessProfiles.RULES_VERSION): String {
        val text = StringBuilder("treadz-fitness-rules:").append(rulesVersion).append('\n')
        for ((id, document) in results.sortedBy { it.first }) {
            text.append(id).append('\n').append(canonical(document)).append('\n')
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toString().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** [document] as canonical JSON; a text that is not JSON is used as it is. */
    internal fun canonical(document: String): String {
        val out = StringBuilder()
        try {
            write(JSONObject(document), out)
        } catch (e: JSONException) {
            return document
        }
        return out.toString()
    }

    private fun write(value: Any?, out: StringBuilder) {
        when (value) {
            null, JSONObject.NULL -> out.append("null")
            is JSONObject -> {
                out.append('{')
                value.keys().asSequence().sorted().forEachIndexed { i, key ->
                    if (i > 0) out.append(',')
                    out.append(JSONObject.quote(key)).append(':')
                    write(value.get(key), out)
                }
                out.append('}')
            }
            is JSONArray -> {
                out.append('[')
                for (i in 0 until value.length()) {
                    if (i > 0) out.append(',')
                    write(value.get(i), out)
                }
                out.append(']')
            }
            is String -> out.append(JSONObject.quote(value))
            is Boolean -> out.append(value)
            is Double, is Float -> out.append(plain(BigDecimal.valueOf((value as Number).toDouble())))
            is Number -> out.append(plain(BigDecimal(value.toString())))
            else -> out.append(JSONObject.quote(value.toString()))
        }
    }

    private fun plain(number: BigDecimal): String = if (number.signum() == 0) "0" else number.stripTrailingZeros().toPlainString()
}
