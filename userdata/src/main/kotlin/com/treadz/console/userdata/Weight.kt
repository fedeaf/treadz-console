package com.treadz.console.userdata

import java.time.LocalDate

/**
 * A manual weight entry. A user may have several entries on the same date.
 *
 * ## Order and "latest"
 * Entries are ordered by [date], then by [createdAtMillis] (when the entry was created, which an edit does not
 * change), then by the global uuid of the entry (not exposed; only decides when two entries of a date were created
 * in the very same millisecond). The latest entry (the user's current weight) is the last one in that order.
 * Every field of the rule is synchronised, so all devices sharing a repository show the same current weight; the
 * local [id] is deliberately not part of it because it differs from one device to another.
 */
data class WeightEntry(
    val id: Long,
    val userId: Long,
    val date: LocalDate,
    val weightKg: Double,
    /**
     * `true` for the made-up weight (80 kg) of the generic profile. It is not the user's real weight: the UI may
     * show it differently. It disappears when the user gets a real entry or personalises the profile, and
     * editing it with [WeightRepository.update] turns it into a normal entry.
     */
    val isPlaceholder: Boolean = false,
    /** When the entry was created, in epoch milliseconds (see the ordering rule above). */
    val createdAtMillis: Long = 0,
)
