package com.treadz.console.programs.json

import com.treadz.console.programs.everythingProgram
import com.treadz.console.programs.heartRateProgram
import com.treadz.console.programs.hillTemplate
import com.treadz.console.programs.norwegian4x4
import com.treadz.console.programs.model.BlockEnd
import com.treadz.console.programs.model.InvalidProgramException
import com.treadz.console.programs.model.Program
import com.treadz.console.programs.model.ProgramKind
import com.treadz.console.programs.model.ProgramOrigin
import com.treadz.console.programs.model.Block
import com.treadz.console.treadmill.recording.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProgramJsonTest {

    @Test
    fun `every sample program survives a round trip`() {
        listOf(
            norwegian4x4(),
            norwegian4x4(ProgramKind.Personalised, ProgramOrigin("norwegian-4x4", 1, mapOf("maxHeartRateBpm" to "185"))),
            hillTemplate(),
            heartRateProgram(),
            everythingProgram(),
        ).forEach { p ->
            val text = ProgramJson.encode(p)
            assertEquals(p, ProgramJson.decode(text), p.name)
            // The text is stable: encoding what was decoded gives the same document
            assertEquals(text, ProgramJson.encode(ProgramJson.decode(text)))
        }
    }

    @Test
    fun `the document is readable and uses explicit units`() {
        val text = ProgramJson.encode(norwegian4x4())
        assertTrue(text.contains("\"schemaVersion\": 1"))
        assertTrue(text.contains("\"durationSeconds\": 240"))
        assertTrue(text.contains("\"speedKmh\": 10.0"))
        assertTrue(text.contains("\"inclinePercent\": 6.0"))
        assertTrue(text.contains("\"phase\": \"warmUp\""))
        assertTrue(text.contains("\"kind\": \"free\""))
        assertTrue(text.lines().size > 8 && text.lines().maxOf { it.length } <= 135, "pretty printed, bounded line width")
        assertFalse(text.contains("origin"), "absent optional fields are not written")
    }

    @Test
    fun `heart-rate bounds are named after what they bound`() {
        val text = ProgramJson.encode(everythingProgram())
        assertTrue(text.contains("minInclinePercent") && text.contains("maxInclinePercent"))
        assertTrue(text.contains("minSpeedPercent") && text.contains("maxSpeedPercent"))
        assertTrue(ProgramJson.encode(heartRateProgram()).contains("minSpeedKmh"))
    }

    @Test
    fun `distance blocks are written with distanceMeters`() {
        val p = ProgramJson.decode(ProgramJson.encode(everythingProgram()))
        assertEquals(BlockEnd.Distance(1000), (p.steps[0] as Block).end)
        assertTrue(ProgramJson.encode(everythingProgram()).contains("\"distanceMeters\": 1000"))
    }

    @Test
    fun `unknown fields are ignored at every level`() {
        val text = """
            {
              "schemaVersion": 1, "futureField": {"a": [1, 2]},
              "id": "5a0e0d2c-5b0f-4a56-9d6a-6f1f4b6d2c01", "name": "Tolerant", "kind": "free", "color": "red",
              "steps": [
                {"type": "block", "phase": "work", "durationSeconds": 60, "icon": "bolt",
                 "target": {"type": "absolute", "speedKmh": 8, "inclinePercent": 2, "note": "hi"}},
                {"type": "repeat", "count": 2, "label": "x", "blocks": [
                  {"phase": "recovery", "durationSeconds": 30, "target": {"type": "relative", "speedPercent": 10, "inclinePercent": 0, "x": null}}
                ]}
              ]
            }
        """.trimIndent()
        val p = ProgramJson.decode(text)
        assertEquals("Tolerant", p.name)
        assertEquals(2, p.steps.size)
        assertEquals(1 + 2, p.expandedBlockCount)
    }

    @Test
    fun `integers are accepted where doubles are expected and the other way round when whole`() {
        val text = """{"schemaVersion":1,"id":"5a0e0d2c-5b0f-4a56-9d6a-6f1f4b6d2c01","name":"n","kind":"free","steps":[
            {"phase":"work","durationSeconds":60.0,"target":{"type":"absolute","speedKmh":8,"inclinePercent":2}}]}"""
        assertEquals(60, ((ProgramJson.decode(text).steps[0] as Block).end as BlockEnd.Duration).seconds)
    }

    private fun failure(text: String): String =
        assertThrows(ProgramFormatException::class.java) { ProgramJson.decode(text) }.message!!

    @Test
    fun `malformed documents are reported with their location`() {
        assertTrue(failure("{").contains("Not valid JSON"))
        assertTrue(failure("[1, 2]").contains("must be a JSON object"))
        assertTrue(failure("{}").contains("schemaVersion"))
        val base = """{"schemaVersion":1,"id":"5a0e0d2c-5b0f-4a56-9d6a-6f1f4b6d2c01","name":"n","kind":"free","steps":%s}"""
        assertTrue(failure(base.format("""[{"phase":"work","target":{"type":"absolute","speedKmh":8,"inclinePercent":2}}]""")).contains("durationSeconds"))
        assertTrue(failure(base.format("""[{"phase":"work","durationSeconds":60,"distanceMeters":100,"target":{"type":"absolute","speedKmh":8,"inclinePercent":2}}]""")).contains("not both"))
        assertTrue(failure(base.format("""[{"phase":"sprint","durationSeconds":60,"target":{"type":"absolute","speedKmh":8,"inclinePercent":2}}]""")).contains("steps[0].phase"))
        assertTrue(failure(base.format("""[{"phase":"work","durationSeconds":60,"target":{"type":"absolute","speedKmh":"fast","inclinePercent":2}}]""")).contains("steps[0].target.speedKmh"))
        assertTrue(failure(base.format("""[{"phase":"work","durationSeconds":60.5,"target":{"type":"absolute","speedKmh":8,"inclinePercent":2}}]""")).contains("whole number"))
        assertTrue(failure(base.format("""[{"phase":"work","durationSeconds":60,"target":{"type":"mystery"}}]""")).contains("steps[0].target.type"))
        assertTrue(failure(base.format("""[{"type":"loop","blocks":[]}]""")).contains("steps[0].type"))
        assertTrue(failure(base.format("""[{"type":"repeat","count":2,"blocks":[{"phase":"work","durationSeconds":60}]}]""")).contains("steps[0].blocks[0].target"))
        assertTrue(failure(base.format("{}")).contains("steps"))
    }

    @Test
    fun `a newer schema version is rejected clearly`() {
        val msg = failure("""{"schemaVersion":2,"id":"x","name":"n","kind":"free","steps":[]}""")
        assertTrue(msg.contains("newer"), msg)
    }

    @Test
    fun `decoded programs are validated unless asked not to`() {
        val text = """{"schemaVersion":1,"id":"5a0e0d2c-5b0f-4a56-9d6a-6f1f4b6d2c01","name":"n","kind":"free","steps":[
            {"phase":"work","durationSeconds":0,"target":{"type":"absolute","speedKmh":8,"inclinePercent":2}}]}"""
        val e = assertThrows(InvalidProgramException::class.java) { ProgramJson.decode(text) }
        assertEquals("steps[0].durationSeconds", e.issues.single().path)
        assertEquals(0, ((ProgramJson.decode(text, validate = false).steps[0] as Block).end as BlockEnd.Duration).seconds)
    }

    @Test
    fun `strings with quotes, newlines and accents round trip`() {
        val p = everythingProgram()
        val back = ProgramJson.decode(ProgramJson.encode(p))
        assertEquals(p.name, back.name)
        assertEquals("Line one\nLine two", back.description)
        // the document is plain JSON that the project's own parser also reads
        assertTrue(Json.parseObject(ProgramJson.encode(p)).containsKey("steps"))
    }

    @Test
    fun `schema version of the program is written and read back`() {
        val p: Program = norwegian4x4()
        assertEquals(Program.SCHEMA_VERSION, ProgramJson.decode(ProgramJson.encode(p)).schemaVersion)
    }
}
