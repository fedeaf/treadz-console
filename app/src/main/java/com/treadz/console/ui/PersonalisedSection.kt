package com.treadz.console.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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

/** The "Personalised" part of the programs list: the generators and the Couch to 5K plan. Their sessions are inside each one. */
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

@Composable
private fun GeneratorCard(card: GeneratorCardState, actions: ProgramsActions) {
    val id = card.descriptor.id
    Panel(Modifier.fillMaxWidth().testTag("generator_$id")) {
        Column(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = { actions.onOpenGenerator(id) }).testTag("open_generator_$id").padding(horizontal = 18.dp, vertical = 14.dp),
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
