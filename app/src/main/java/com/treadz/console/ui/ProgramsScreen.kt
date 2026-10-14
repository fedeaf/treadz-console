package com.treadz.console.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import com.treadz.console.programs.model.ProgramKind
import com.treadz.console.training.ProgramCardState
import com.treadz.console.training.ProgramsUiState

data class ProgramsActions(
    val onBack: () -> Unit = {},
    val onOpen: (String) -> Unit = {},
    val onEdit: (String) -> Unit = {},
    val onNew: () -> Unit = {},
    val onDuplicate: (String) -> Unit = {},
    val onDelete: (String) -> Unit = {},
    val onConfirmDelete: () -> Unit = {},
    val onCancelDelete: () -> Unit = {},
    val onOpenFitnessTests: () -> Unit = {},
    /** Opens the generator screen of a personalised program (new session). */
    val onOpenGenerator: (String) -> Unit = {},
    /** Opens the generator screen with the inputs of a saved personalised program, to generate it again with today's data. */
    val onRegenerate: (generatorId: String, programId: String) -> Unit = { _, _ -> },
    /** Unfolds or folds the saved sessions of a generator. */
    val onToggleSessions: (String) -> Unit = {},
)

/** The standard programs and the active user's own programs. */
@Composable
fun ProgramsScreen(state: ProgramsUiState, actions: ProgramsActions, modifier: Modifier = Modifier) {
    val layout = LocalLayout.current
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader(
            title = stringResource(R.string.programs_title),
            subtitle = state.user?.displayName(),
            onBack = actions.onBack,
        )
        LazyVerticalGrid(
            columns = if (layout.wide) GridCells.Adaptive(minSize = 400.dp) else GridCells.Fixed(1),
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("programs_list"),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { FitnessEntry(actions.onOpenFitnessTests) }
            item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel(stringResource(R.string.programs_standard)) }
            items(state.standard, key = { it.id }) { card -> ProgramCard(card, actions) }
            personalisedItems(state, actions)
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionLabel(stringResource(R.string.programs_mine), Modifier.weight(1f))
                    if (state.user != null) RowButton(stringResource(R.string.programs_new), actions.onNew, Modifier.testTag("new_program_button"))
                }
            }
            if (state.mine.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        stringResource(if (state.user == null) R.string.no_user_chosen else R.string.programs_mine_empty),
                        Modifier.testTag("programs_mine_empty"),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(state.mine, key = { it.id }) { card -> ProgramCard(card, actions) }
            }
            if (state.failed) item(span = { GridItemSpan(maxLineSpan) }) { ErrorText(stringResource(R.string.error_storage)) }
        }
    }
    state.confirmingDeleteId?.let { id ->
        val name = state.nameOf(id).orEmpty()
        ConfirmDialog(
            title = stringResource(R.string.program_delete_title, name),
            text = stringResource(R.string.program_delete_body),
            confirmText = stringResource(R.string.action_delete),
            onConfirm = actions.onConfirmDelete,
            onDismiss = actions.onCancelDelete,
        )
    }
}

@Composable
private fun FitnessEntry(onOpen: () -> Unit) {
    Panel(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpen).testTag("fitness_entry"),
        borderColor = MaterialTheme.colorScheme.primary,
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.fitness_entry_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.fitness_entry_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RowButton(stringResource(R.string.fitness_entry_open), onOpen)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProgramCard(card: ProgramCardState, actions: ProgramsActions) {
    val name = if (card.standardKey != null) stringResource(standardNameRes(card.standardKey)) else card.name
    val description = if (card.standardKey != null) stringResource(standardDescriptionRes(card.standardKey)) else card.description
    Panel(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = { actions.onOpen(card.id) }).testTag("program_${card.id}")) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (card.kind == ProgramKind.Personalised) Tag(card.kind.label(), MaterialTheme.colorScheme.secondary)
            }
            if (!description.isNullOrBlank()) {
                Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            PlanChart(
                segments = card.segments,
                description = stringResource(R.string.program_shape_description, name),
                modifier = Modifier.fillMaxWidth().height(56.dp).testTag("shape_${card.id}"),
                showAxes = false,
            )
            Text(meta(card), Modifier.testTag("program_meta_${card.id}"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (card.kind != ProgramKind.Standard) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (card.editable) RowButton(stringResource(R.string.action_edit), { actions.onEdit(card.id) }, Modifier.testTag("edit_program_${card.id}"))
                    RowButton(
                        stringResource(if (card.kind == ProgramKind.Personalised) R.string.saved_duplicate else R.string.program_duplicate),
                        { actions.onDuplicate(card.id) }, Modifier.testTag("duplicate_program_${card.id}"),
                    )
                    RowButton(stringResource(R.string.action_delete), { actions.onDelete(card.id) }, Modifier.testTag("delete_program_${card.id}"), destructive = true)
                }
            }
        }
    }
}

@Composable
private fun meta(card: ProgramCardState): String {
    val min = card.minDurationSeconds
    val max = card.maxDurationSeconds
    return if (min != null && max != null) {
        stringResource(R.string.program_meta_standard, min / 60, max / 60, card.durationSeconds / 60)
    } else {
        stringResource(R.string.program_meta_free, Format.duration(card.durationSeconds), Format.distanceKm(card.distanceMeters))
    }
}
