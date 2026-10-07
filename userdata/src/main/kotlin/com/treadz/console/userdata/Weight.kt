package com.treadz.console.userdata

import java.time.LocalDate

/**
 * A manual weight entry. A user may have several entries on the same date; they are ordered by date and, within
 * a date, by creation order ([id]).
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
)
