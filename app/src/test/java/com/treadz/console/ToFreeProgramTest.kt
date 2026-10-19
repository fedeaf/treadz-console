package com.treadz.console

import com.treadz.console.programs.model.Block
import com.treadz.console.programs.model.BlockEnd
import com.treadz.console.programs.model.BlockPhase
import com.treadz.console.programs.model.BlockTarget
import com.treadz.console.programs.model.Program
import com.treadz.console.programs.model.ProgramKind
import com.treadz.console.programs.plan.ProgramResolver
import com.treadz.console.programs.plan.StartParameters
import com.treadz.console.training.toFreeProgram
import org.junit.Assert.assertEquals
import org.junit.Test

/** Saving a plan as a free program keeps the values a block leaves to "keep" instead of freezing their expected numbers. */
class ToFreeProgramTest {

    @Test fun keptValuesStayKept() {
        val program = Program(
            id = "11111111-1111-4111-8111-111111111111",
            name = "Walk",
            kind = ProgramKind.Free,
            steps = listOf(
                Block(BlockPhase.Work, BlockEnd.Duration(600), BlockTarget.Absolute(5.5, 8.0)),
                Block(BlockPhase.CoolDown, BlockEnd.Duration(300), BlockTarget.Absolute(null, 0.0)),
            ),
        )
        val saved = ProgramResolver.resolve(program, StartParameters()).toFreeProgram("Walk")
        assertEquals(BlockTarget.Absolute(5.5, 8.0), (saved.steps[0] as Block).target)
        assertEquals(BlockTarget.Absolute(null, 0.0), (saved.steps[1] as Block).target)
        assertEquals(2, saved.schemaVersion)
    }
}
