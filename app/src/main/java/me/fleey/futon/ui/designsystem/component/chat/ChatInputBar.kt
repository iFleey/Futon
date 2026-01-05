/*
 * Futon - Futon Daemon Client
 * Copyright (C) 2025 Fleey
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package me.fleey.futon.ui.designsystem.component.chat

import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import me.fleey.futon.R
import me.fleey.futon.ui.designsystem.component.FutonIcons
import me.fleey.futon.ui.designsystem.theme.FutonTheme
import sv.lib.squircleshape.SquircleShape
import java.util.UUID

/**
 * Represents a slash command that can be triggered by typing `/`.
 */
data class SlashCommand(
  val id: String,
  val trigger: String,
  val expansion: String,
  val description: String = "",
)

sealed interface ChatAttachment {
  val id: String

  data class Image(
    override val id: String = UUID.randomUUID().toString(),
    val uri: Uri,
    val thumbnailUri: Uri? = null,
  ) : ChatAttachment

  data class File(
    override val id: String = UUID.randomUUID().toString(),
    val uri: Uri,
    val fileName: String,
    val mimeType: String,
  ) : ChatAttachment
}

@Composable
fun ChatInputBar(
  value: String,
  onValueChange: (String) -> Unit,
  onSend: () -> Unit,
  onStop: () -> Unit,
  isRunning: Boolean,
  isEnabled: Boolean,
  modifier: Modifier = Modifier,
  attachments: List<ChatAttachment> = emptyList(),
  onAttachmentClick: (() -> Unit)? = null,
  onRemoveAttachment: ((String) -> Unit)? = null,
  maxAttachments: Int = 5,
  slashCommands: List<SlashCommand> = emptyList(),
  onSlashCommandSelected: ((SlashCommand) -> Unit)? = null,
) {
  val canSend = (value.isNotBlank() || attachments.isNotEmpty()) && isEnabled && !isRunning
  val hasAttachments = attachments.isNotEmpty()
  val canAddMoreAttachments = attachments.size < maxAttachments

  val matchingCommands by remember(value, slashCommands) {
    derivedStateOf {
      if (!value.startsWith("/")) return@derivedStateOf emptyList()

      slashCommands.asSequence()
        .filter { command ->
          command.trigger.startsWith(value, ignoreCase = true) ||
            (value == "/" && command.trigger.startsWith("/"))
        }
        .take(5)
        .toList()
    }
  }

  val showSlashPopup = matchingCommands.isNotEmpty() && isEnabled && !isRunning

  Column(
    modifier = modifier
      .fillMaxWidth()
      .background(FutonTheme.colors.background)
      .padding(horizontal = 16.dp, vertical = 12.dp),
  ) {
    AnimatedVisibility(
      visible = showSlashPopup,
      enter = slideInVertically(initialOffsetY = { it / 2 }) + expandVertically() + fadeIn(
        animationSpec = tween(150),
      ),
      exit = slideOutVertically(targetOffsetY = { it / 2 }) + shrinkVertically() + fadeOut(
        animationSpec = tween(100),
      ),
    ) {
      SlashCommandPopup(
        commands = matchingCommands,
        onCommandSelected = { command ->
          onSlashCommandSelected?.invoke(command)
        },
        modifier = Modifier
          .fillMaxWidth()
          .padding(bottom = 8.dp),
      )
    }

    AnimatedVisibility(
      visible = hasAttachments,
      enter = expandVertically(
        animationSpec = spring(
          dampingRatio = Spring.DampingRatioMediumBouncy,
          stiffness = Spring.StiffnessMedium,
        ),
      ) + fadeIn(),
      exit = shrinkVertically(
        animationSpec = spring(
          dampingRatio = Spring.DampingRatioNoBouncy,
          stiffness = Spring.StiffnessMedium,
        ),
      ) + fadeOut(),
    ) {
      AttachmentPreviewRow(
        attachments = attachments,
        onRemoveAttachment = onRemoveAttachment,
        isEnabled = isEnabled && !isRunning,
        modifier = Modifier
          .fillMaxWidth()
          .padding(bottom = 8.dp),
      )
    }

    Surface(
      modifier = Modifier
        .fillMaxWidth()
        .border(
          width = 1.dp,
          color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
          shape = InputContainerShape,
        ),
      shape = InputContainerShape,
      color = FutonTheme.colors.backgroundSecondary,
      tonalElevation = 0.dp,
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .defaultMinSize(minHeight = 52.dp)
          .padding(all = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        if (onAttachmentClick != null) {
          AttachmentButton(
            onClick = onAttachmentClick,
            hasAttachments = hasAttachments,
            enabled = isEnabled && !isRunning && canAddMoreAttachments,
          )
        }

        ChatTextField(
          value = value,
          onValueChange = onValueChange,
          onSend = { if (canSend) onSend() },
          enabled = isEnabled && !isRunning,
          modifier = Modifier
            .weight(1f)
            .padding(horizontal = 8.dp),
        )

        AnimatedContent(
          targetState = isRunning,
          transitionSpec = {
            (fadeIn(animationSpec = tween(200)) + scaleIn(
              initialScale = 0.8f,
              animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow,
              ),
            )).togetherWith(
              fadeOut(animationSpec = tween(150)) + scaleOut(
                targetScale = 0.8f,
                animationSpec = tween(150),
              ),
            )
          },
          label = "SendStopButton",
        ) { running ->
          if (running) {
            StopButton(onClick = onStop)
          } else {
            SendButton(onClick = onSend, enabled = canSend)
          }
        }
      }
    }

    Spacer(modifier = Modifier.height(8.dp))

    Text(
      text = stringResource(R.string.ai_disclaimer),
      style = MaterialTheme.typography.labelSmall,
      color = FutonTheme.colors.textMuted.copy(alpha = 0.5f),
      modifier = Modifier.align(Alignment.CenterHorizontally),
    )
  }
}

@Composable
private fun ChatTextField(
  value: String,
  onValueChange: (String) -> Unit,
  onSend: () -> Unit,
  enabled: Boolean,
  modifier: Modifier = Modifier,
) {
  val primaryColor = MaterialTheme.colorScheme.primary

  val cursorBrush = remember(primaryColor) { SolidColor(primaryColor) }

  val hintColor = FutonTheme.colors.textMuted.copy(alpha = 0.6f)
  val textColor = FutonTheme.colors.textNormal

  BasicTextField(
    value = value,
    onValueChange = onValueChange,
    modifier = modifier.heightIn(min = 40.dp, max = 160.dp),
    enabled = enabled,
    textStyle = MaterialTheme.typography.bodyLarge.copy(color = textColor),
    cursorBrush = cursorBrush,
    keyboardOptions = KeyboardOptions(
      capitalization = KeyboardCapitalization.Sentences,
      imeAction = ImeAction.Send,
    ),
    keyboardActions = KeyboardActions(onSend = { onSend() }),
    decorationBox = { innerTextField ->
      Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart,
      ) {
        if (value.isEmpty()) {
          Text(
            text = stringResource(R.string.chat_input_hint),
            style = MaterialTheme.typography.bodyLarge,
            color = hintColor,
          )
        }
        innerTextField()
      }
    },
  )
}

@Composable
private fun AttachmentButton(
  onClick: () -> Unit,
  hasAttachments: Boolean,
  enabled: Boolean,
  modifier: Modifier = Modifier,
) {
  val backgroundColor by animateColorAsState(
    targetValue = if (hasAttachments) {
      MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    } else {
      FutonTheme.colors.interactiveMuted.copy(alpha = 0.5f)
    },
    animationSpec = tween(200),
    label = "AttachmentButtonBackground",
  )

  val iconColor by animateColorAsState(
    targetValue = when {
      !enabled -> FutonTheme.colors.textMuted.copy(alpha = 0.4f)
      hasAttachments -> MaterialTheme.colorScheme.primary
      else -> FutonTheme.colors.textMuted
    },
    animationSpec = tween(200),
    label = "AttachmentButtonIconColor",
  )

  Surface(
    onClick = onClick,
    enabled = enabled,
    modifier = modifier.size(40.dp),
    shape = CircleShape,
    color = backgroundColor,
  ) {
    Box(contentAlignment = Alignment.Center) {
      Icon(
        imageVector = FutonIcons.AttachFile,
        contentDescription = stringResource(R.string.chat_input_attach),
        modifier = Modifier.size(22.dp),
        tint = iconColor,
      )
    }
  }
}

@Composable
private fun AttachmentPreviewRow(
  attachments: List<ChatAttachment>,
  onRemoveAttachment: ((String) -> Unit)?,
  isEnabled: Boolean,
  modifier: Modifier = Modifier,
) {
  LazyRow(
    modifier = modifier,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    contentPadding = PaddingValues(horizontal = 4.dp),
  ) {
    items(
      items = attachments,
      key = { it.id },
    ) { attachment ->
      AttachmentThumbnail(
        attachment = attachment,
        onRemove = if (isEnabled && onRemoveAttachment != null) {
          { onRemoveAttachment(attachment.id) }
        } else null,
        modifier = Modifier.animateItem(),
      )
    }
  }
}

@Composable
private fun AttachmentThumbnail(
  attachment: ChatAttachment,
  onRemove: (() -> Unit)?,
  modifier: Modifier = Modifier,
) {
  val thumbnailSize = 64.dp
  val removeButtonSize = 20.dp

  Box(modifier = modifier) {
    when (attachment) {
      is ChatAttachment.Image -> {
        AsyncImage(
          model = attachment.thumbnailUri ?: attachment.uri,
          contentDescription = stringResource(R.string.chat_attachment_image),
          modifier = Modifier
            .size(thumbnailSize)
            .clip(AttachmentThumbnailShape)
            .background(FutonTheme.colors.backgroundTertiary),
          contentScale = ContentScale.Crop,
        )
      }

      is ChatAttachment.File -> {
        Box(
          modifier = Modifier
            .size(thumbnailSize)
            .clip(AttachmentThumbnailShape)
            .background(FutonTheme.colors.backgroundTertiary)
            .padding(8.dp),
          contentAlignment = Alignment.Center,
        ) {
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
          ) {
            Icon(
              imageVector = FutonIcons.AttachFile,
              contentDescription = null,
              modifier = Modifier.size(20.dp),
              tint = FutonTheme.colors.textMuted,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
              text = attachment.fileName,
              style = MaterialTheme.typography.labelSmall,
              color = FutonTheme.colors.textMuted,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
      }
    }

    if (onRemove != null) {
      Surface(
        modifier = Modifier
          .align(Alignment.TopEnd)
          .offset(x = 6.dp, y = (-6).dp)
          .size(removeButtonSize)
          .clickable(onClick = onRemove),
        shape = CircleShape,
        color = FutonTheme.colors.statusDanger,
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(
            imageVector = FutonIcons.Close,
            contentDescription = stringResource(R.string.chat_input_remove_attachment),
            modifier = Modifier.size(14.dp),
            tint = FutonTheme.colors.background,
          )
        }
      }
    }
  }
}

@Composable
private fun SendButton(
  onClick: () -> Unit,
  enabled: Boolean,
  modifier: Modifier = Modifier,
) {
  val scale by animateFloatAsState(
    targetValue = if (enabled) 1f else 0.95f,
    animationSpec = spring(
      dampingRatio = Spring.DampingRatioMediumBouncy,
      stiffness = Spring.StiffnessMedium,
    ),
    label = "SendButtonScale",
  )

  val backgroundColor by animateColorAsState(
    targetValue = if (enabled) {
      MaterialTheme.colorScheme.primary
    } else {
      FutonTheme.colors.interactiveMuted.copy(alpha = 0.7f)
    },
    animationSpec = tween(200),
    label = "SendButtonBackground",
  )

  val iconColor by animateColorAsState(
    targetValue = if (enabled) {
      MaterialTheme.colorScheme.onPrimary
    } else {
      FutonTheme.colors.textMuted.copy(alpha = 0.6f)
    },
    animationSpec = tween(200),
    label = "SendButtonIconColor",
  )

  Surface(
    onClick = onClick,
    enabled = enabled,
    modifier = modifier
      .size(40.dp)
      .scale(scale),
    shape = CircleShape,
    color = backgroundColor,
  ) {
    Box(contentAlignment = Alignment.Center) {
      Icon(
        imageVector = FutonIcons.Send,
        contentDescription = stringResource(R.string.action_send),
        modifier = Modifier.size(20.dp),
        tint = iconColor,
      )
    }
  }
}

@Composable
private fun StopButton(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    onClick = onClick,
    modifier = modifier.size(40.dp),
    shape = CircleShape,
    color = FutonTheme.colors.statusDanger,
    contentColor = FutonTheme.colors.background,
  ) {
    Box(contentAlignment = Alignment.Center) {
      Icon(
        imageVector = FutonIcons.Stop,
        contentDescription = stringResource(R.string.stop_execution),
        modifier = Modifier.size(20.dp),
      )
    }
  }
}

@Composable
private fun SlashCommandPopup(
  commands: List<SlashCommand>,
  onCommandSelected: (SlashCommand) -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier
      .shadow(
        elevation = 8.dp,
        shape = SlashPopupShape,
        spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
      )
      .border(
        width = 1.dp,
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.08f),
        shape = SlashPopupShape,
      ),
    shape = SlashPopupShape,
    color = FutonTheme.colors.backgroundSecondary,
    tonalElevation = 2.dp,
  ) {
    Column {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(
          imageVector = FutonIcons.Terminal,
          contentDescription = null,
          modifier = Modifier.size(14.dp),
          tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
          text = stringResource(R.string.slash_commands_title),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.primary,
          fontWeight = FontWeight.Medium,
        )
      }

      HorizontalDivider(
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.06f),
      )

      LazyColumn(
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(max = 200.dp),
        contentPadding = PaddingValues(vertical = 4.dp),
      ) {
        items(
          items = commands,
          key = { it.id },
        ) { command ->
          SlashCommandItem(
            command = command,
            onClick = { onCommandSelected(command) },
            modifier = Modifier.animateItem(),
          )
        }
      }
    }
  }
}

@Composable
private fun SlashCommandItem(
  command: SlashCommand,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    onClick = onClick,
    modifier = modifier.fillMaxWidth(),
    color = FutonTheme.colors.backgroundSecondary,
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
      ) {
        Text(
          text = command.trigger,
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.primary,
          fontWeight = FontWeight.SemiBold,
          modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
      }

      Spacer(modifier = Modifier.width(12.dp))

      Column(
        modifier = Modifier.weight(1f),
      ) {
        Text(
          text = command.expansion,
          style = MaterialTheme.typography.bodySmall,
          color = FutonTheme.colors.textNormal,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (command.description.isNotBlank()) {
          Text(
            text = command.description,
            style = MaterialTheme.typography.labelSmall,
            color = FutonTheme.colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }

      Icon(
        imageVector = FutonIcons.KeyboardReturn,
        contentDescription = null,
        modifier = Modifier.size(16.dp),
        tint = FutonTheme.colors.textMuted.copy(alpha = 0.5f),
      )
    }
  }
}

private val InputContainerShape = SquircleShape(16.dp)
private val AttachmentThumbnailShape = SquircleShape(6.dp)
private val SlashPopupShape = RoundedCornerShape(8.dp)
