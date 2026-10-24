package com.treadz.console.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.treadz.console.R
import com.treadz.console.analysis.thresholds.ThresholdQuality
import com.treadz.console.analysis.trends.EpochDays
import com.treadz.console.analysis.trends.TrailingMean
import com.treadz.console.analysis.workout.RecoveryContext
import com.treadz.console.performance.PerformanceCard
import com.treadz.console.performance.PerformanceTrends
import com.treadz.console.performance.PerformanceUiState
import java.time.LocalDate
import kotlin.math.roundToInt

data class PerformanceActions(
    val onBack: () -> Unit = {},
    /** Opens or closes the chart of a card. */
    val onToggle: (PerformanceCard) -> Unit = {},
)

/**
 * The trends of the active user, one card each: the current figure with a compact indication of change and, when the card
 * is opened, its charts. A card without data says so in one passive line (and does not open); nothing here asks anything.
 */
@Composable
fun PerformanceScreen(state: PerformanceUiState, actions: PerformanceActions, modifier: Modifier = Modifier) {
    val layout = LocalLayout.current
    Column(modifier.fillMaxSize().testTag("performance_screen"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader(title = stringResource(R.string.perf_title), subtitle = state.user?.displayName(), onBack = actions.onBack)
        val trends = state.trends
        when {
            !state.loaded -> Unit
            state.user == null || trends == null -> Text(
                stringResource(R.string.no_user_chosen),
                Modifier.testTag("performance_no_user"),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            else -> {
                val cards = PerformanceCards(state, trends, actions)
                if (layout.wide) {
                    Row(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            cards.Vo2max(); cards.FitnessIndex(); cards.Recovery(); cards.WeightVo2()
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            cards.Thresholds(); cards.Load(); cards.Resting()
                        }
                    }
                } else {
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        cards.Vo2max(); cards.Thresholds(); cards.FitnessIndex(); cards.Load(); cards.Recovery(); cards.Resting(); cards.WeightVo2()
                    }
                }
            }
        }
    }
}

/** A change with its sign ("+1.5", "−2"); a change that rounds to nothing is plain "0". */
private fun signed(value: Double, decimals: Int): String {
    val text = Format.number("%.${decimals}f", value)
    return if (text.trimStart('-').all { it == '0' || it == '.' }) text.trimStart('-') else if (value > 0) "+$text" else text.replace('-', '−')
}

private fun whole(value: Double): String = Format.whole(value.roundToInt())

private fun LocalDate.axis(withYear: Boolean, locale: java.util.Locale) = Format.axisDate(this, withYear, locale)

private class PerformanceCards(private val state: PerformanceUiState, private val t: PerformanceTrends, private val actions: PerformanceActions) {

    // ---------------------------------------------------------------------------------------------------- VO2max

    @Composable
    fun Vo2max() {
        val tests = t.tests.vo2max
        val profile = state.profile?.vo2max
        val value = profile?.valueMlKgMin ?: tests.lastOrNull()?.value
        Card(PerformanceCard.Vo2max, stringResource(R.string.perf_vo2max_title), expandable = tests.isNotEmpty(), summary = {
            if (value == null) {
                EmptyLine(stringResource(R.string.perf_vo2max_empty), "perf_vo2max_empty")
            } else {
                Headline(Format.weight(value), stringResource(R.string.unit_vo2max), "perf_vo2max_value") {
                    profile?.classification?.let { Tag(it.category.label(), MaterialTheme.colorScheme.secondary, Modifier.testTag("perf_vo2max_category")) }
                    if (tests.lastOrNull()?.isLowerBound == true) Tag(stringResource(R.string.perf_lower_bound_tag), MaterialTheme.extra.warning, Modifier.testTag("perf_vo2max_lower_bound"))
                }
                t.vo2maxChange?.let { ChangeLine(stringResource(R.string.perf_change_vs_test, signed(it, 1)), "perf_vo2max_change") }
            }
        }) {
            val locale = currentLocale()
            val x = tests.map { it.epochDay.toDouble() }
            val withYear = needsYear(x.first(), x.last())
            val color = MaterialTheme.colorScheme.primary
            val title = stringResource(R.string.perf_chart_vo2max)
            ChartBlock(title, "perf_vo2max_chart") { mod ->
                TrendChart(
                    series = listOf(TrendSeries(color, tests.map { ChartPoint(it.epochDay.toDouble(), it.value, hollow = it.isLowerBound) })),
                    band = tests.mapNotNull { p -> if (p.low != null && p.high != null) BandPoint(p.epochDay.toDouble(), p.low!!, p.high!!) else null },
                    bandColor = color,
                    xLabel = epochDayLabel(withYear, locale), yLabel = { Format.weight(it) }, description = title, modifier = mod,
                )
            }
            ChartLegend(
                buildList {
                    add(LegendEntry(stringResource(R.string.result_vo2max), color))
                    if (tests.any { it.low != null }) add(LegendEntry(stringResource(R.string.perf_legend_see), color, band = true))
                    if (tests.any { it.isLowerBound }) add(LegendEntry(stringResource(R.string.perf_legend_lower_bound), color, hollow = true))
                },
            )
        }
    }

    // ------------------------------------------------------------------------------------- Thresholds and zones

    @Composable
    fun Thresholds() {
        val thresholds = state.profile?.thresholds
        val vt1 = thresholds?.vt1
        val vt2 = thresholds?.vt2
        val history = t.tests.thresholds
        Card(PerformanceCard.Thresholds, stringResource(R.string.perf_thresholds_title), expandable = state.zones != null || history.isNotEmpty(), summary = {
            if (vt1 == null) {
                EmptyLine(stringResource(R.string.perf_thresholds_empty), "perf_thresholds_empty")
            } else {
                FigureRow {
                    Figure(stringResource(R.string.perf_vt1_short), Format.whole(vt1.heartRateBpm), stringResource(R.string.unit_heart_rate), "perf_vt1_value")
                    if (vt2 != null) {
                        Figure(stringResource(R.string.perf_vt2_short), Format.whole(vt2.heartRateBpm), stringResource(R.string.unit_heart_rate), "perf_vt2_value")
                    } else {
                        Figure(stringResource(R.string.perf_vt2_short), Format.NO_VALUE, null, "perf_vt2_value", note = stringResource(R.string.perf_thresholds_vt2_missing))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tag(vt1.quality.label(), vt1.quality.color(), Modifier.testTag("perf_thresholds_quality"))
                }
                t.vt1Change?.let {
                    ChangeLine(stringResource(R.string.perf_change_vs_test, "${stringResource(R.string.perf_vt1_short)} ${signed(it, 0)} ${stringResource(R.string.unit_heart_rate)}"), "perf_thresholds_change")
                }
            }
            // Opened, the zone list below says it.
            if (PerformanceCard.Thresholds !in state.expanded) state.zones?.let { Passive(it.source.label(), "perf_zones_source") }
            // The workouts say the thresholds may have moved: a test is recommended (the profile never changes by itself).
            TestRecommendationNotes(state.recommendations)
        }) {
            state.zones?.let { ZoneList(it) }
            if (history.isNotEmpty()) {
                val locale = currentLocale()
                val x = history.map { it.epochDay.toDouble() }
                val withYear = needsYear(x.first(), x.last())
                val vt1Color = MaterialTheme.colorScheme.secondary
                val vt2Color = MaterialTheme.extra.warning
                val title = stringResource(R.string.perf_chart_thresholds)
                ChartBlock(title, "perf_thresholds_chart") { mod ->
                    TrendChart(
                        series = listOf(
                            TrendSeries(vt1Color, history.filter { it.vt1Bpm != null }.map { ChartPoint(it.epochDay.toDouble(), it.vt1Bpm!!, hollow = it.quality == ThresholdQuality.Low) }),
                            TrendSeries(vt2Color, history.filter { it.vt2Bpm != null }.map { ChartPoint(it.epochDay.toDouble(), it.vt2Bpm!!, hollow = it.quality == ThresholdQuality.Low) }),
                        ),
                        xLabel = epochDayLabel(withYear, locale), yLabel = { Format.whole(it.roundToInt()) }, description = title, modifier = mod,
                    )
                }
                ChartLegend(
                    buildList {
                        add(LegendEntry(stringResource(R.string.perf_vt1_short), vt1Color))
                        add(LegendEntry(stringResource(R.string.perf_vt2_short), vt2Color))
                        if (history.any { it.quality == ThresholdQuality.Low }) add(LegendEntry(stringResource(R.string.perf_legend_low_quality), vt1Color, hollow = true))
                    },
                )
            }
        }
    }

    // ----------------------------------------------------------------------------------------------- Fitness index

    @Composable
    fun FitnessIndex() {
        val trend = t.fitnessIndex
        val last = trend.points.lastOrNull()
        Card(PerformanceCard.FitnessIndex, stringResource(R.string.perf_fitness_title), expandable = last != null, summary = {
            if (last == null) {
                EmptyLine(stringResource(R.string.perf_fitness_empty), "perf_fitness_empty")
            } else {
                Headline(Format.weight(last.rollingMedian), stringResource(R.string.unit_vo2max), "perf_fitness_value")
                val slope = trend.slopePer30Days
                ChangeLine(
                    if (slope != null) stringResource(R.string.perf_fitness_per_30, signed(slope, 1)) else stringResource(R.string.perf_fitness_sessions, trend.points.size),
                    "perf_fitness_change",
                )
                Passive(stringResource(R.string.perf_fitness_trend_note), "perf_fitness_note")
            }
        }) {
            val locale = currentLocale()
            if (trend.points.isNotEmpty()) {
                val x = trend.points.map { it.epochDay.toDouble() }
                val withYear = needsYear(x.first(), x.last())
                val sessionColor = MaterialTheme.colorScheme.onSurfaceVariant
                val medianColor = MaterialTheme.colorScheme.primary
                val title = stringResource(R.string.perf_chart_fitness)
                ChartBlock(title, "perf_fitness_chart") { mod ->
                    TrendChart(
                        series = listOf(
                            TrendSeries(sessionColor, trend.points.map { ChartPoint(it.epochDay.toDouble(), it.estimate) }, line = false),
                            TrendSeries(medianColor, trend.points.map { ChartPoint(it.epochDay.toDouble(), it.rollingMedian) }, dots = false),
                        ),
                        xLabel = epochDayLabel(withYear, locale), yLabel = { Format.weight(it) }, description = title, modifier = mod,
                    )
                }
                ChartLegend(listOf(LegendEntry(stringResource(R.string.perf_legend_session), sessionColor), LegendEntry(stringResource(R.string.perf_legend_median), medianColor)))
                Passive(stringResource(R.string.perf_fitness_trend_note))
            }
        }
    }

    // ---------------------------------------------------------------------------------------------- Training load

    @Composable
    fun Load() {
        Card(PerformanceCard.Load, stringResource(R.string.perf_load_title), expandable = t.hasLoad, summary = {
            if (!t.hasLoad) {
                EmptyLine(stringResource(R.string.perf_load_empty), "perf_load_empty")
            } else {
                Passive(stringResource(R.string.perf_load_last_7), "perf_load_period")
                Headline(whole(t.trimpLast7Days), stringResource(R.string.perf_load_trimp_unit), "perf_load_value")
                if (t.trimpPrevious7Days > 0.0) {
                    ChangeLine(stringResource(R.string.perf_load_vs_previous, signed(t.trimpLast7Days - t.trimpPrevious7Days, 0)), "perf_load_change")
                }
                t.loadDays.lastOrNull()?.let { day ->
                    Passive(stringResource(R.string.perf_load_balance_line, signed(day.balance, 0), whole(day.fitness), whole(day.fatigue)), "perf_load_balance")
                }
                if (t.workoutsWithoutTrimpLast7Days > 0) {
                    Passive(stringResource(R.string.perf_load_missing_hr, t.workoutsWithoutTrimpLast7Days), "perf_load_missing", MaterialTheme.extra.warning)
                }
            }
        }) {
            val locale = currentLocale()
            val barColor = MaterialTheme.colorScheme.primary
            val flagColor = MaterialTheme.extra.warning
            val weeks = t.loadWeeks
            if (weeks.isNotEmpty()) {
                val title = stringResource(R.string.perf_chart_weekly_trimp)
                ChartBlock(title, "perf_load_weekly_chart") { mod ->
                    WeeklyBarChart(
                        bars = weeks.map { BarItem(it.trimp, flagged = it.workoutsWithoutTrimp > 0) }, color = barColor, flagColor = flagColor,
                        yLabel = { whole(it) },
                        firstLabel = LocalDate.ofEpochDay(weeks.first().weekStartEpochDay).axis(false, locale),
                        lastLabel = LocalDate.ofEpochDay(weeks.last().weekStartEpochDay).axis(false, locale),
                        description = title, modifier = mod,
                    )
                }
                if (t.workoutsWithoutTrimpShown > 0) ChartLegend(listOf(LegendEntry(stringResource(R.string.perf_legend_without_hr), flagColor)))
            }
            val days = ChartMath.downsample(t.loadDays.map { ChartPoint(it.epochDay.toDouble(), it.fitness) }, 200)
            if (days.isNotEmpty()) {
                val fitnessColor = MaterialTheme.colorScheme.secondary
                val fatigueColor = MaterialTheme.extra.warning
                val balanceColor = MaterialTheme.colorScheme.primary
                val title = stringResource(R.string.perf_chart_banister)
                val x = t.loadDays.map { it.epochDay.toDouble() }
                ChartBlock(title, "perf_load_banister_chart") { mod ->
                    TrendChart(
                        series = listOf(
                            TrendSeries(fitnessColor, days, dots = false),
                            TrendSeries(fatigueColor, ChartMath.downsample(t.loadDays.map { ChartPoint(it.epochDay.toDouble(), it.fatigue) }, 200), dots = false),
                            TrendSeries(balanceColor, ChartMath.downsample(t.loadDays.map { ChartPoint(it.epochDay.toDouble(), it.balance) }, 200), dots = false),
                        ),
                        xLabel = epochDayLabel(needsYear(x.first(), x.last()), locale), yLabel = { whole(it) }, description = title, modifier = mod,
                    )
                }
                ChartLegend(
                    listOf(
                        LegendEntry(stringResource(R.string.perf_legend_fitness), fitnessColor),
                        LegendEntry(stringResource(R.string.perf_legend_fatigue), fatigueColor),
                        LegendEntry(stringResource(R.string.perf_legend_balance), balanceColor),
                    ),
                )
            }
            val zoneWeeks = t.zoneWeeks
            if (zoneWeeks.any { it.totalSeconds > 0 }) {
                val title = stringResource(R.string.perf_chart_zone_weeks)
                val colors = (1..5).map { zoneColor(it) }
                ChartBlock(title, "perf_load_zones_chart") { mod ->
                    StackedShareChart(
                        shares = zoneWeeks.map { it.fractions }, colors = colors,
                        firstLabel = LocalDate.ofEpochDay(zoneWeeks.first().weekStartEpochDay).axis(false, locale),
                        lastLabel = LocalDate.ofEpochDay(zoneWeeks.last().weekStartEpochDay).axis(false, locale),
                        description = title, modifier = mod,
                    )
                }
                ChartLegend((1..5).map { LegendEntry(stringResource(R.string.zone_name, it), colors[it - 1]) })
                Passive(stringResource(R.string.perf_zones_note))
            }
        }
    }

    // ------------------------------------------------------------------------------------------------- Recovery

    @Composable
    fun Recovery() {
        val latest = t.latestRecovery
        Card(PerformanceCard.Recovery, stringResource(R.string.perf_recovery_title), expandable = latest != null, summary = {
            if (latest == null) {
                EmptyLine(stringResource(R.string.perf_recovery_empty), "perf_recovery_empty")
            } else {
                val (context, point) = latest
                FigureRow {
                    Figure(stringResource(R.string.perf_recovery_1min), point.drop60Bpm?.let { whole(it) } ?: Format.NO_VALUE, stringResource(R.string.unit_heart_rate), "perf_recovery_60")
                    Figure(stringResource(R.string.perf_recovery_2min), point.drop120Bpm?.let { whole(it) } ?: Format.NO_VALUE, stringResource(R.string.unit_heart_rate), "perf_recovery_120")
                }
                Row { Tag(context.label(), MaterialTheme.colorScheme.secondary, Modifier.testTag("perf_recovery_context")) }
                val before = t.previousRecoverySameContext
                val change = if (before?.drop60Bpm != null && point.drop60Bpm != null) point.drop60Bpm!! - before.drop60Bpm!! else null
                change?.let {
                    ChangeLine(stringResource(R.string.perf_recovery_change, "${signed(it, 0)} ${stringResource(R.string.unit_heart_rate)}"), "perf_recovery_change")
                }
            }
        }) {
            val locale = currentLocale()
            val c1 = MaterialTheme.colorScheme.primary
            val c2 = MaterialTheme.colorScheme.secondary
            RecoveryContext.entries.forEach { context ->
                val points = t.recovery[context].orEmpty()
                if (points.isEmpty()) return@forEach
                val x = points.map { it.epochDay.toDouble() }
                val title = "${stringResource(R.string.perf_chart_recovery)} · ${context.label()}"
                ChartBlock(title, "perf_recovery_chart_${context.name}") { mod ->
                    TrendChart(
                        series = listOf(
                            TrendSeries(c1, points.filter { it.drop60Bpm != null }.map { ChartPoint(it.epochDay.toDouble(), it.drop60Bpm!!) }),
                            TrendSeries(c2, points.filter { it.drop120Bpm != null }.map { ChartPoint(it.epochDay.toDouble(), it.drop120Bpm!!) }),
                        ),
                        xLabel = epochDayLabel(needsYear(x.first(), x.last()), locale), yLabel = { whole(it) }, description = title, modifier = mod, yFloor = 0.0,
                    )
                }
            }
            ChartLegend(listOf(LegendEntry(stringResource(R.string.perf_recovery_1min), c1), LegendEntry(stringResource(R.string.perf_recovery_2min), c2)))
            Passive(stringResource(R.string.perf_recovery_apart))
        }
    }

    // -------------------------------------------------------------------------------------------------- Resting

    @Composable
    fun Resting() {
        val resting = t.restingMean
        val rmssd = t.rmssdMean
        Card(PerformanceCard.Resting, stringResource(R.string.perf_resting_title), expandable = resting.isNotEmpty() || rmssd.isNotEmpty(), summary = {
            if (resting.isEmpty() && rmssd.isEmpty()) {
                EmptyLine(stringResource(R.string.perf_resting_empty), "perf_resting_empty")
            } else {
                resting.lastOrNull()?.let {
                    Passive(stringResource(R.string.perf_resting_mean))
                    Headline(whole(it.mean), stringResource(R.string.unit_heart_rate), "perf_resting_value")
                    t.restingChange?.let { change -> ChangeLine(stringResource(R.string.perf_resting_change, "${signed(change, 0)} ${stringResource(R.string.unit_heart_rate)}"), "perf_resting_change") }
                }
                rmssd.lastOrNull()?.let {
                    FactRow(
                        stringResource(R.string.resting_rmssd),
                        stringResource(R.string.resting_rmssd_value, whole(it.mean)) + (t.rmssdChange?.let { c -> " (${signed(c, 0)} ${stringResource(R.string.unit_ms)})" } ?: ""),
                        tag = "perf_rmssd_value",
                    )
                }
                Passive(stringResource(R.string.perf_resting_informative))
            }
        }) {
            val locale = currentLocale()
            MeanChart(stringResource(R.string.perf_chart_resting), "perf_resting_chart", resting, MaterialTheme.colorScheme.error, locale)
            MeanChart(stringResource(R.string.perf_chart_rmssd), "perf_rmssd_chart", rmssd, MaterialTheme.colorScheme.secondary, locale)
        }
    }

    @Composable
    private fun MeanChart(title: String, tag: String, means: List<TrailingMean>, color: Color, locale: java.util.Locale) {
        if (means.isEmpty()) return
        val x = means.map { it.epochDay.toDouble() }
        ChartBlock(title, tag) { mod ->
            TrendChart(
                series = listOf(TrendSeries(color, means.map { ChartPoint(it.epochDay.toDouble(), it.mean) })),
                xLabel = epochDayLabel(needsYear(x.first(), x.last()), locale), yLabel = { whole(it) }, description = title, modifier = mod,
            )
        }
    }

    // ------------------------------------------------------------------------------------ Weight and absolute VO2

    @Composable
    fun WeightVo2() {
        val absolute = t.absoluteVo2
        val weights = t.weights
        Card(PerformanceCard.WeightVo2, stringResource(R.string.perf_weight_title), expandable = absolute.isNotEmpty(), summary = {
            val last = absolute.lastOrNull()
            if (last == null) {
                EmptyLine(stringResource(R.string.perf_weight_empty), "perf_weight_empty")
            } else {
                Headline(Format.number("%.2f", last.litresPerMinute), stringResource(R.string.perf_abs_unit), "perf_abs_value")
                t.absoluteVo2Change?.let { ChangeLine(stringResource(R.string.perf_abs_change, signed(it, 2)), "perf_abs_change") }
                Passive(stringResource(R.string.perf_abs_detail, Format.weight(last.vo2maxMlKgMin), Format.weight(last.weightKg)), "perf_abs_detail")
            }
        }) {
            val locale = currentLocale()
            Passive(stringResource(R.string.perf_abs_help))
            val absX = absolute.map { it.epochDay.toDouble() }
            val absTitle = stringResource(R.string.perf_chart_abs)
            ChartBlock(absTitle, "perf_abs_chart") { mod ->
                TrendChart(
                    series = listOf(TrendSeries(MaterialTheme.colorScheme.primary, absolute.map { ChartPoint(it.epochDay.toDouble(), it.litresPerMinute) })),
                    xLabel = epochDayLabel(needsYear(absX.first(), absX.last()), locale), yLabel = { Format.number("%.2f", it) }, description = absTitle, modifier = mod,
                )
            }
            if (weights.isNotEmpty()) {
                val wx = weights.map { it.epochDay.toDouble() }
                val wTitle = stringResource(R.string.perf_chart_weight)
                ChartBlock(wTitle, "perf_weight_chart") { mod ->
                    TrendChart(
                        series = listOf(TrendSeries(MaterialTheme.colorScheme.secondary, weights.map { ChartPoint(it.epochDay.toDouble(), it.value) }, dots = false)),
                        xLabel = epochDayLabel(needsYear(wx.first(), wx.last()), locale), yLabel = { Format.weight(it) }, description = wTitle, modifier = mod,
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------- Frame

    @Composable
    private fun Card(
        card: PerformanceCard,
        title: String,
        expandable: Boolean,
        summary: @Composable ColumnScope.() -> Unit,
        details: @Composable ColumnScope.() -> Unit = {},
    ) {
        val open = expandable && card in state.expanded
        Panel(Modifier.fillMaxWidth().testTag("perf_card_${card.name}")) {
            Column {
                Column(
                    Modifier
                        .then(if (expandable) Modifier.clickable(role = Role.Button, onClick = { actions.onToggle(card) }).testTag("perf_toggle_${card.name}") else Modifier)
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        if (expandable) {
                            Text(
                                stringResource(if (open) R.string.perf_hide_chart else R.string.perf_show_chart),
                                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Chevron(open, MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    summary()
                }
                if (open) {
                    Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 16.dp).testTag("perf_details_${card.name}"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        details()
                    }
                }
            }
        }
    }
}

@Composable
private fun Chevron(open: Boolean, color: Color) {
    Canvas(Modifier.size(18.dp)) {
        val w = 2.5.dp.toPx()
        val y1 = if (open) size.height * 0.68f else size.height * 0.32f
        val y2 = if (open) size.height * 0.32f else size.height * 0.68f
        drawLine(color, Offset(size.width * 0.15f, y1), Offset(size.width * 0.5f, y2), strokeWidth = w, cap = StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.5f, y2), Offset(size.width * 0.85f, y1), strokeWidth = w, cap = StrokeCap.Round)
    }
}

@Composable
private fun RecoveryContext.label(): String = stringResource(
    when (this) {
        RecoveryContext.BeltMoving -> R.string.perf_context_moving
        RecoveryContext.BeltStopped -> R.string.perf_context_stopped
    },
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Headline(value: String, unit: String?, tag: String, extra: @Composable () -> Unit = {}) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.Bottom) {
        Text(value, Modifier.testTag(tag), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, maxLines = 1)
        if (unit != null) Text(unit, Modifier.padding(bottom = 6.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { extra() }
    }
}

@Composable
private fun FigureRow(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.Bottom) { content() }
}

@Composable
private fun Figure(label: String, value: String, unit: String?, tag: String, note: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(value, Modifier.testTag(tag), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, maxLines = 1)
            if (unit != null) Text(unit, Modifier.padding(bottom = 6.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ChangeLine(text: String, tag: String) {
    Text(text, Modifier.testTag(tag), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun EmptyLine(text: String, tag: String) {
    Text(text, Modifier.testTag(tag), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Passive(text: String, tag: String? = null, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, if (tag != null) Modifier.testTag(tag) else Modifier, style = MaterialTheme.typography.bodySmall, color = color)
}

/** A titled chart of the height the layout asks for: [content] gets the modifier to draw with. */
@Composable
private fun ChartBlock(title: String, tag: String, content: @Composable (Modifier) -> Unit) {
    val height: Dp = if (LocalLayout.current.wide) 200.dp else 190.dp
    Column(Modifier.fillMaxWidth().testTag(tag), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content(Modifier.fillMaxWidth().height(height))
    }
}
