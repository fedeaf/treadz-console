package com.treadz.console.treadmill.replay

import com.treadz.console.treadmill.DiscoveredTreadmill
import com.treadz.console.treadmill.ftms.FtmsCharacteristic
import com.treadz.console.treadmill.ftms.FtmsTransport
import com.treadz.console.treadmill.ftms.GattPacket
import com.treadz.console.treadmill.ftms.TransportDisconnect
import com.treadz.console.treadmill.ftms.hexToBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

/** A packet received from the treadmill during a recorded session, [atMs] after the session origin. */
class RecordedPacket(val atMs: Long, val characteristic: FtmsCharacteristic, val value: ByteArray)

/** A command the app issued during the recorded session. */
class RecordedCommand(val atMs: Long, val name: String)

/**
 * A fragment of a real session: the packets the treadmill sent (with their timestamps), the values read on connect
 * and the commands the app issued. Fixture format, one entry per line:
 *
 *  - `# comment`
 *  - `@read <characteristic> <hex>`
 *  - `@command <ms> <name>`
 *  - `<ms> <characteristic> <hex>`
 */
class RecordedFragment(
    val reads: Map<FtmsCharacteristic, ByteArray>,
    val packets: List<RecordedPacket>,
    val commands: List<RecordedCommand>,
) {
    companion object {
        fun parse(text: String): RecordedFragment {
            val reads = linkedMapOf<FtmsCharacteristic, ByteArray>()
            val packets = mutableListOf<RecordedPacket>()
            val commands = mutableListOf<RecordedCommand>()
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val parts = line.split(' ')
                if (parts[0] == "@read") {
                    reads[characteristic(parts[1])] = parts[2].hexToBytes()
                } else if (parts[0] == "@command") {
                    commands += RecordedCommand(parts[1].toLong(), parts[2])
                } else {
                    packets += RecordedPacket(parts[0].toLong(), characteristic(parts[1]), parts[2].hexToBytes())
                }
            }
            return RecordedFragment(reads, packets, commands)
        }

        fun load(resource: String): RecordedFragment {
            val stream = RecordedFragment::class.java.getResourceAsStream(resource)
                ?: error("Fixture not found: $resource")
            return parse(stream.use { it.readBytes().toString(Charsets.UTF_8) })
        }

        private fun characteristic(shortName: String): FtmsCharacteristic =
            FtmsCharacteristic.fromShortUuid(shortName.toInt(16)) ?: error("Unknown characteristic $shortName")
    }
}

/**
 * Transport that replays a [RecordedFragment] with virtual time: the packets are emitted with the recorded
 * timing once the controller has subscribed to the four notification characteristics. Writes to the Control
 * Point are only recorded in [written]: the treadmill's answers are already part of the fragment.
 *
 * A fragment ends where the original recording ended. With [keepSendingLastDataMs] > 0 the last Treadmill Data
 * packet keeps being repeated (about every 490 ms) after the end of the fragment, as the treadmill would do while idle.
 */
class ReplayTransport(
    private val scope: CoroutineScope,
    private val fragment: RecordedFragment,
    private val keepSendingLastDataMs: Long = 0,
    override val deviceName: String? = "REPLAY",
) : FtmsTransport {

    private val _incoming = MutableSharedFlow<GattPacket>(extraBufferCapacity = 4096)
    override val incoming: SharedFlow<GattPacket> = _incoming.asSharedFlow()

    private val _disconnections = MutableSharedFlow<TransportDisconnect>(extraBufferCapacity = 16)
    override val disconnections: SharedFlow<TransportDisconnect> = _disconnections.asSharedFlow()

    private val subscribed = mutableSetOf<FtmsCharacteristic>()
    private var started = false

    /** Packets written to the Control Point by the controller, in hex. */
    val written = mutableListOf<ByteArray>()

    /** Called once, when the replay starts (the origin of the recorded timestamps). */
    var onReplayStart: (() -> Unit)? = null

    override fun scan(): Flow<DiscoveredTreadmill> = emptyFlow()

    override suspend fun connect(address: String) = Unit

    override suspend fun subscribe(characteristic: FtmsCharacteristic) {
        subscribed += characteristic
        if (!started && subscribed.containsAll(REQUIRED)) {
            started = true
            onReplayStart?.invoke()
            scope.launch {
                var last = 0L
                for (p in fragment.packets) {
                    if (p.atMs > last) delay(p.atMs - last)
                    last = maxOf(last, p.atMs)
                    _incoming.emit(GattPacket(p.characteristic, p.value))
                }
                val lastData = fragment.packets.last { it.characteristic == FtmsCharacteristic.TreadmillData }
                var repeated = 0L
                while (repeated < keepSendingLastDataMs) {
                    delay(490)
                    repeated += 490
                    _incoming.emit(GattPacket(lastData.characteristic, lastData.value))
                }
            }
        }
    }

    override suspend fun read(characteristic: FtmsCharacteristic): ByteArray? = fragment.reads[characteristic]

    override suspend fun writeControlPoint(value: ByteArray) {
        written += value.copyOf()
    }

    override suspend fun disconnect() = Unit

    private companion object {
        val REQUIRED = setOf(
            FtmsCharacteristic.TreadmillData,
            FtmsCharacteristic.MachineStatus,
            FtmsCharacteristic.TrainingStatus,
            FtmsCharacteristic.ControlPoint,
        )
    }
}
