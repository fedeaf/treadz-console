package com.treadz.console.training

import com.treadz.console.programs.plan.ResolvedBlock
import com.treadz.console.programs.plan.ResolvedPlan

/** A line of the list of a plan's blocks: one block, or a group of blocks repeated. */
sealed interface PlanRow {
    data class Single(val block: ResolvedBlock) : PlanRow

    /** [blocks] (as of the first repetition) performed [count] times. */
    data class Group(val count: Int, val blocks: List<ResolvedBlock>) : PlanRow
}

/** The plan as the program was written: the repeats are folded back into one group, shown with their first repetition. */
fun ResolvedPlan.rows(): List<PlanRow> {
    val rows = mutableListOf<PlanRow>()
    var i = 0
    while (i < blocks.size) {
        val block = blocks[i]
        val repetition = block.repetition
        if (repetition == null) {
            rows += PlanRow.Single(block)
            i++
        } else {
            val group = blocks.drop(i).takeWhile { it.stepIndex == block.stepIndex && it.repetition != null }
            rows += PlanRow.Group(repetition.count, group.filter { it.repetition?.iteration == 1 })
            i += group.size
        }
    }
    return rows
}

/** The key of a block's position as the generators report it (`"<step>"` or `"<step>.<block in repeat>"`). */
val ResolvedBlock.positionKey: String get() = if (blockInStep == null) "$stepIndex" else "$stepIndex.$blockInStep"
