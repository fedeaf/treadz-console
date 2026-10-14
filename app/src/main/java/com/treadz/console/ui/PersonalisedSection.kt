package com.treadz.console.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.treadz.console.R
import com.treadz.console.programs.personalised.CouchToFiveK
import com.treadz.console.programs.personalised.GeneratorNoticeCode
import com.treadz.console.training.CouchCardState
import com.treadz.console.training.GeneratorCardState
import com.treadz.console.training.ProgramsUiState
import com.treadz.console.training.SavedSessionState

/** The "Personalised" part of the programs list: the generators, the sessions saved from each, and the Couch to 5K plan. */
fun LazyGridScope.personalisedItems(state: ProgramsUiState, actions: ProgramsActions) {
    item(span = { GridItemSpan(maxLineSpan) }) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionLabel(stringResource(R.string.programs_personalised))
            Text(
                stringResource(if (state.user == null) R.string.no_user_chosen else R.string.programs_personalised_intro),
                Modifier.testTag("programs_personalised_intro"),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    items(state.generators.size, key = { "generator-${state.generators[it].descriptor.id}" }) { index ->
        GeneratorCard(state.generators[index], actions)
    }
    item(key = "generator-couch", span = { GridItemSpan(maxLineSpan) }) { CouchCard(state.couch, actions) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GeneratorCard(card: GeneratorCardState, actions: ProgramsActions) {
    val id = card.descriptor.id
    Panel(Modifier.fillMaxWidth().testTag("generator_$id")) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(
                Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = { actions.onOpenGenerator(id) }).testTag("open_generator_$id"),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(generatorNameRes(id)), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (card.descriptor.heartRateGuided) Tag(stringResource(R.string.generator_tag_heart_rate), MaterialTheme.colorScheme.error, Modifier.testTag("generator_hr_tag_$id"))
                }
                Text(
                    stringResource(generatorDescriptionRes(id)),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
                RowButton(stringResource(R.string.generator_new_session), { actions.onOpenGenerator(id) }, Modifier.testTag("new_session_$id"))
            }
            if (card.sessions.isNotEmpty()) {
                SectionLabel(stringResource(R.string.generator_saved_sessions, card.sessions.size), Modifier.padding(top = 4.dp))
                card.visibleSessions.forEach { SavedSessionRow(id, it, actions) }
                if (card.canFold) {
                    RowButton(
                        if (card.expanded) stringResource(R.string.generator_show_fewer) else stringResource(R.string.generator_show_all, card.hiddenCount),
                        { actions.onToggleSessions(id) },
                        Modifier.testTag("toggle_sessions_$id"),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SavedSessionRow(generatorId: String, session: SavedSessionState, actions: ProgramsActions) {
    val locale = currentLocale()
    val details = listOfNotNull(
        session.mode?.label(),
        session.durationMinutes?.let { stringResource(R.string.duration_minutes, it) },
        session.series?.let { stringResource(R.string.generator_series_count, it) },
    )
    Panel(Modifier.fillMaxWidth().testTag("saved_${session.programId}"), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                (listOf(Format.dateTime(session.createdAtMillis, locale)) + details).joinToString(" · "),
                Modifier.testTag("saved_title_${session.programId}"),
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RowButton(stringResource(R.string.saved_repeat), { actions.onOpen(session.programId) }, Modifier.testTag("repeat_${session.programId}"))
                RowButton(stringResource(R.string.saved_regenerate), { actions.onRegenerate(generatorId, session.programId) }, Modifier.testTag("regenerate_${session.programId}"))
                RowButton(stringResource(R.string.saved_duplicate), { actions.onDuplicate(session.programId) }, Modifier.testTag("duplicate_program_${session.programId}"))
                RowButton(stringResource(R.string.action_delete), { actions.onDelete(session.programId) }, Modifier.testTag("delete_program_${session.programId}"), destructive = true)
            }
        }
    }
}

/** The Couch to 5K plan as one card: the session that comes next, why, and when the last one was. */
@Composable
private fun CouchCard(couch: CouchCardState?, actions: ProgramsActions) {
    val id = CouchToFiveK.ID
    val locale = currentLocale()
    Panel(Modifier.fillMaxWidth().testTag("generator_$id"), borderColor = MaterialTheme.colorScheme.primary) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(generatorNameRes(id)), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Tag(stringResource(R.string.generator_tag_heart_rate), MaterialTheme.colorScheme.error, Modifier.testTag("generator_hr_tag_$id"))
            }
            if (couch == null) {
                Text(stringResource(generatorDescriptionRes(id)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                RowButton(stringResource(R.string.generator_new_session), { actions.onOpenGenerator(id) }, Modifier.testTag("new_session_$id"))
                return@Column
            }
            val selection = couch.selection
            Text(
                stringResource(R.string.couch_next_session, selection.session, CouchToFiveK.SESSIONS, selection.week, selection.day),
                Modifier.testTag("couch_next"),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
            )
            Text(selection.decision.text(), Modifier.testTag("couch_reason"), style = MaterialTheme.typography.bodyMedium)
            couch.lastSessionAtMillis?.let {
                Text(
                    stringResource(R.string.couch_last_session, Format.dateTime(it, locale)),
                    Modifier.testTag("couch_last"),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            couch.notices.filter { it.code == GeneratorNoticeCode.RecentSession || it.code == GeneratorNoticeCode.LongBreak }.forEach { notice ->
                Text("• " + notice.text(null, selection), Modifier.testTag("couch_notice_${notice.code.name}"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.extra.warning)
            }
            BigButton(
                stringResource(R.string.couch_start_session, selection.session),
                { actions.onOpenGenerator(id) },
                Modifier.fillMaxWidth().testTag("couch_start_button"),
            )
        }
    }
}
