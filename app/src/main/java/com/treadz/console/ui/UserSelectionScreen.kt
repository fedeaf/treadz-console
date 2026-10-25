package com.treadz.console.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.treadz.console.R
import com.treadz.console.users.UserCard
import com.treadz.console.users.UserSelectionUiState

/** Actions of the user selection screen. */
data class UserSelectionActions(
    val onSelect: (Long) -> Unit = {},
    val onEdit: (Long) -> Unit = {},
    val onAdd: () -> Unit = {},
    /** `null` on the first screen of the app, where there is nowhere to go back to. */
    val onBack: (() -> Unit)? = null,
)

@Composable
fun UserSelectionScreen(
    state: UserSelectionUiState,
    actions: UserSelectionActions,
    modifier: Modifier = Modifier,
) {
    val layout = LocalLayout.current
    val hasActive = state.users.any { it.isActive }
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader(
            title = stringResource(R.string.users_title),
            subtitle = stringResource(if (hasActive || !state.loaded) R.string.users_subtitle else R.string.users_subtitle_nobody),
            onBack = actions.onBack,
        )
        if (state.switchLocked) {
            Panel(Modifier.fillMaxWidth().testTag("switch_locked_notice"), color = MaterialTheme.extra.warning, borderColor = MaterialTheme.extra.warning) {
                Text(
                    stringResource(R.string.users_locked),
                    Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.extra.onWarning,
                )
            }
        }
        if (state.failed) ErrorText(stringResource(R.string.error_storage), Modifier.testTag("selection_error"))
        LazyVerticalGrid(
            columns = if (layout.wide) GridCells.Adaptive(minSize = 360.dp) else GridCells.Fixed(1),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(state.users, key = { it.profile.id }) { card ->
                UserCardItem(
                    card = card,
                    enabled = !state.switchLocked || card.isActive,
                    onSelect = { actions.onSelect(card.profile.id) },
                    onEdit = { actions.onEdit(card.profile.id) },
                )
            }
        }
        BigOutlinedButton(
            text = stringResource(R.string.users_add),
            onClick = actions.onAdd,
            modifier = Modifier.fillMaxWidth().testTag("add_user_button"),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UserCardItem(card: UserCard, enabled: Boolean, onSelect: () -> Unit, onEdit: () -> Unit) {
    val profile = card.profile
    val name = profile.displayName()
    val compact = LocalLayout.current.compact
    val accent = if (card.isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val editButton: @Composable (Modifier) -> Unit = { modifier ->
        RowButton(
            text = stringResource(if (profile.isGeneric) R.string.users_personalize else R.string.action_edit),
            onClick = onEdit,
            modifier = modifier.testTag("edit_user_${profile.id}"),
        )
    }
    Panel(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(enabled = enabled, role = Role.Button, onClick = onSelect)
            .testTag("user_card_${profile.id}"),
        borderColor = if (card.isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.heightIn(min = 72.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                UserAvatar(name, profile.avatarId, accent, size = 64.dp, initialSize = 30.sp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        stringResource(R.string.user_age, card.ageYears),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (card.isActive || profile.isGeneric) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (card.isActive) Tag(stringResource(R.string.tag_active), MaterialTheme.colorScheme.primary)
                            if (profile.isGeneric) Tag(stringResource(R.string.tag_generic), MaterialTheme.extra.warning)
                        }
                    }
                }
                // On a phone the button goes below, so the name keeps the full width.
                if (!compact) editButton(Modifier)
            }
            if (compact) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { editButton(Modifier) }
        }
    }
}
