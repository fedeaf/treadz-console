package com.treadz.console.fakes

import com.treadz.console.settings.RememberedTreadmill
import com.treadz.console.settings.SettingsStore

class InMemorySettingsStore(
    override var lastTreadmill: RememberedTreadmill? = null,
    override var darkTheme: Boolean = true,
) : SettingsStore {
    private val strapAnswered = mutableSetOf<Long>()
    private val peakAnswers = mutableMapOf<Long, Long>()
    private val generators = mutableMapOf<Pair<Long, String>, Map<String, String>>()

    override fun strapQuestionAnswered(userId: Long) = userId in strapAnswered

    override fun setStrapQuestionAnswered(userId: Long) {
        strapAnswered += userId
    }

    override fun peakAnsweredAtMillis(userId: Long): Long? = peakAnswers[userId]

    override fun setPeakAnsweredAtMillis(userId: Long, atMillis: Long) {
        peakAnswers[userId] = atMillis
    }

    override fun generatorInputs(userId: Long, generatorId: String): Map<String, String> = generators[userId to generatorId].orEmpty()

    override fun setGeneratorInputs(userId: Long, generatorId: String, inputs: Map<String, String>) {
        generators[userId to generatorId] = inputs
    }
}
