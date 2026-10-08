package com.treadz.console.programs

import com.treadz.console.programs.model.Block
import com.treadz.console.programs.model.BlockEnd
import com.treadz.console.programs.model.BlockPhase
import com.treadz.console.programs.model.BlockTarget
import com.treadz.console.programs.model.HeartRateAdjust
import com.treadz.console.programs.model.HeartRateBasis
import com.treadz.console.programs.model.HeartRateZone
import com.treadz.console.programs.model.Program
import com.treadz.console.programs.model.ProgramKind
import com.treadz.console.programs.model.ProgramOrigin
import com.treadz.console.programs.model.Repeat

const val ID_1 = "5a0e0d2c-5b0f-4a56-9d6a-6f1f4b6d2c01"
const val ID_2 = "5a0e0d2c-5b0f-4a56-9d6a-6f1f4b6d2c02"
const val ID_3 = "5a0e0d2c-5b0f-4a56-9d6a-6f1f4b6d2c03"

fun abs(speed: Double, incline: Double = 0.0) = BlockTarget.Absolute(speed, incline)
fun rel(speedPercent: Double, inclinePercent: Double = 0.0) = BlockTarget.Relative(speedPercent, inclinePercent)
fun secs(seconds: Int) = BlockEnd.Duration(seconds)
fun meters(m: Int) = BlockEnd.Distance(m)

fun block(phase: BlockPhase, end: BlockEnd, target: BlockTarget, name: String? = null) = Block(phase, end, target, name)

/** Norwegian 4x4 with absolute values: 10 min warm-up, 4 x (4 min work + 3 min recovery), 5 min cool-down. */
fun norwegian4x4(kind: ProgramKind = ProgramKind.Free, origin: ProgramOrigin? = null) = Program(
    id = ID_1,
    name = "Norwegian 4x4",
    kind = kind,
    description = "Four hard intervals with active recovery.",
    origin = origin,
    steps = listOf(
        block(BlockPhase.WarmUp, secs(600), abs(6.0, 2.0)),
        Repeat(
            4,
            listOf(
                block(BlockPhase.Work, secs(240), abs(10.0, 6.0), "4 min hard"),
                block(BlockPhase.Recovery, secs(180), abs(6.0, 2.0)),
            ),
        ),
        block(BlockPhase.CoolDown, secs(300), abs(5.0, 0.0)),
    ),
)

/** A small relative template (standard program): 20 % / 100 % / 50 % / 0 % of the range over 60+120+120+60 s. */
fun hillTemplate() = Program(
    id = ID_2,
    name = "Hills",
    kind = ProgramKind.Standard,
    steps = listOf(
        block(BlockPhase.WarmUp, secs(60), rel(0.0, 0.0)),
        block(BlockPhase.Work, secs(120), rel(100.0, 100.0)),
        block(BlockPhase.Work, secs(120), rel(50.0, 50.0)),
        block(BlockPhase.CoolDown, secs(60), rel(0.0, 0.0)),
    ),
)

fun heartRateProgram() = Program(
    id = ID_3,
    name = "Zone 2",
    kind = ProgramKind.Free,
    steps = listOf(
        block(BlockPhase.WarmUp, secs(300), abs(5.0, 1.0)),
        block(
            BlockPhase.Work, secs(1200),
            BlockTarget.HeartRate(
                zone = HeartRateZone(HeartRateBasis.HeartRateReserve, 60, 70),
                adjust = HeartRateAdjust.Speed,
                minimum = 6.0,
                maximum = 10.0,
                start = abs(8.0, 3.0),
            ),
        ),
        block(BlockPhase.CoolDown, secs(300), abs(4.0, 0.0)),
    ),
)

/** Every feature of the model: kinds, distance blocks, names, relative and heart-rate targets, origin inputs. */
fun everythingProgram() = Program(
    id = ID_3,
    name = "Everything \"quoted\" é",
    kind = ProgramKind.Personalised,
    description = "Line one\nLine two",
    origin = ProgramOrigin("norwegian-4x4", 2, linkedMapOf("maxHeartRateBpm" to "185", "restingHeartRateBpm" to "52")),
    steps = listOf(
        block(BlockPhase.WarmUp, meters(1000), rel(20.0, 10.0), "easy km"),
        Repeat(
            3,
            listOf(
                block(BlockPhase.Work, secs(90), BlockTarget.HeartRate(HeartRateZone(HeartRateBasis.MaxHeartRate, 85, 95), HeartRateAdjust.Incline, 2.0, 8.0, abs(9.0, 4.0))),
                block(BlockPhase.Other, secs(60), BlockTarget.HeartRate(HeartRateZone(HeartRateBasis.MaxHeartRate, 60, 70), HeartRateAdjust.Speed, 10.0, 90.0, rel(40.0, 20.0))),
            ),
        ),
        block(BlockPhase.CoolDown, secs(120), abs(4.0)),
    ),
)
