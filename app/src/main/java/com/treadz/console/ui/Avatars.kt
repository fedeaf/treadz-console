package com.treadz.console.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.treadz.console.R

/**
 * An avatar a user can pick. [id] is what the profile stores (`UserProfile.avatarId`) and is synchronised as it is, so it
 * never changes once an avatar has been released; [drawable] is the face, drawn to be cropped to a circle.
 */
data class AvatarOption(val id: String, @DrawableRes val drawable: Int, @StringRes val description: Int)

/**
 * The avatars of the app, in the order the picker shows them. To add one: a drawable `avatar_<id>`, its description in
 * both languages and one line here, at the end; nothing else changes (an id that a version does not know shows the
 * initial of the name, and is kept in the profile).
 */
object Avatars {
    val all: List<AvatarOption> = listOf(
        AvatarOption("man", R.drawable.avatar_man, R.string.avatar_man),
        AvatarOption("woman", R.drawable.avatar_woman, R.string.avatar_woman),
    )

    /** The avatar with that id, or `null` for none or an id this version does not know. */
    fun find(id: String?): AvatarOption? = all.firstOrNull { it.id == id }
}

/**
 * The picture of a user: their avatar when they have one this version knows, else the initial of the [name] on a tinted
 * circle. The caller says the [size]; [initialSize] is the size of the letter.
 */
@Composable
fun UserAvatar(name: String, avatarId: String?, accent: Color, size: Dp, initialSize: TextUnit, modifier: Modifier = Modifier) {
    val avatar = Avatars.find(avatarId)
    if (avatar != null) {
        // Decorative: the name is always next to it.
        Image(
            painter = painterResource(avatar.drawable),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(CircleShape),
        )
    } else {
        Box(modifier.size(size).background(accent.copy(alpha = 0.2f), CircleShape), contentAlignment = Alignment.Center) {
            Text(name.take(1).uppercase(), fontSize = initialSize, fontWeight = FontWeight.Bold, color = accent)
        }
    }
}

/**
 * The avatar choice of the profile form: "no avatar" (the initial of the name) followed by every avatar. [selectedId] is
 * the id in the form; [onSelect] gets the id picked, or `null` for none.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AvatarPicker(name: String, selectedId: String?, onSelect: (String?) -> Unit, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Column(modifier.fillMaxWidth().testTag("avatar_choice"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.field_avatar), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AvatarChoice(
                selected = Avatars.find(selectedId) == null,
                description = stringResource(R.string.avatar_none),
                onClick = { onSelect(null) },
                modifier = Modifier.testTag("avatar_none"),
            ) { UserAvatar(name, null, accent, AVATAR_CHOICE_SIZE, 34.sp) }
            Avatars.all.forEach { avatar ->
                AvatarChoice(
                    selected = selectedId == avatar.id,
                    description = stringResource(avatar.description),
                    onClick = { onSelect(avatar.id) },
                    modifier = Modifier.testTag("avatar_${avatar.id}"),
                ) { UserAvatar(name, avatar.id, accent, AVATAR_CHOICE_SIZE, 34.sp) }
            }
        }
    }
}

private val AVATAR_CHOICE_SIZE = 72.dp

/** One option of the picker: the picture in a ring that is thicker and coloured when it is the selected one. */
@Composable
private fun AvatarChoice(selected: Boolean, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val ring = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier
            .size(AVATAR_CHOICE_SIZE + 16.dp)
            .clip(CircleShape)
            .border(if (selected) 4.dp else 1.dp, ring, CircleShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}
