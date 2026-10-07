package com.treadz.console.treadmill.ftms

/** Helpers for Bluetooth LE device addresses (`AA:BB:CC:DD:EE:FF`). */
object BleAddress {

    /**
     * `true` for a **random static** address: the two most significant bits of the first byte are set. Such an
     * address cannot be a valid public one, so the system must be told its type explicitly (or have seen the device in a
     * scan); otherwise it assumes a public address and the connection fails.
     */
    fun isRandomStatic(address: String): Boolean {
        val first = address.trim().take(2).toIntOrNull(16) ?: return false
        return first and 0xC0 == 0xC0
    }

    /** Upper-case form used as a key (`aa:bb…` and `AA:BB…` are the same device). */
    fun normalize(address: String): String = address.trim().uppercase()
}
