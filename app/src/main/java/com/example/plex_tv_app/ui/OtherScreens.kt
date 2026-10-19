@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.example.plex_tv_app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.example.plex_tv_app.plex.HomeUser
import com.example.plex_tv_app.plex.PlexClient
import com.example.plex_tv_app.plex.PlexException
import com.example.plex_tv_app.ui.theme.palette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val PagePadding = Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 28.dp)

/**
 * Your Plex Home's profiles. Selecting one opens its options:
 * - Everyone: switch to it, and show or hide it on this TV's profile picker.
 * - The owner also: add a managed profile, and remove people from the Home.
 */
@Composable
fun ManageProfilesScreen(client: PlexClient, onSwitchUser: () -> Unit) {
    val scope = rememberCoroutineScope()
    var users by remember { mutableStateOf<List<HomeUser>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    var hidden by remember { mutableStateOf(client.hiddenProfiles) }
    var selected by remember { mutableStateOf<HomeUser?>(null) }
    var adding by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reload) {
        try {
            users = client.homeUsers()
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        }
    }

    val list = users
    // The owner can add and remove people; everyone else can only change this TV's picker.
    val isOwner = list?.let { all ->
        val me = all.firstOrNull { it.id == client.profileId }
        me?.admin ?: (client.profileId == null) // not switched yet: signed in as the account owner
    } ?: false

    Column(PagePadding) {
        Text("Profiles", style = MaterialTheme.typography.headlineMedium)
        Text(
            if (isOwner) "Select a profile for its options, or add a managed profile for someone without a Plex account."
            else "Select a profile for its options.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        notice?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = palette.accent)
        }
        Spacer(Modifier.height(20.dp))
        val currentError = error
        when {
            currentError != null && list == null -> Text(currentError, color = MaterialTheme.colorScheme.error)
            list == null -> Text("Loading…")
            else -> Column(Modifier.width(680.dp).verticalScroll(rememberScrollState())) {
                val first = remember { FocusRequester() }
                val focused = remember { mutableStateOf(false) }
                list.forEachIndexed { i, user ->
                    ListItem(
                        selected = user.id == client.profileId,
                        onClick = { selected = user },
                        modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                        leadingContent = { Avatar(user.thumb, user.title, size = 40.dp) },
                        headlineContent = { Text(user.title) },
                        supportingContent = {
                            Text(
                                listOfNotNull(
                                    if (user.admin) "Owner" else if (user.restricted) "Managed" else "Member",
                                    if (user.protected) "PIN protected" else null,
                                    if (user.id == client.profileId) "Current" else null,
                                    if (!user.admin && user.id in hidden) "Hidden on this TV" else null,
                                ).joinToString(" · ")
                            )
                        },
                        trailingContent = { Text("›", fontSize = 20.sp) },
                    )
                }
                if (isOwner) {
                    Spacer(Modifier.height(8.dp))
                    ListItem(
                        selected = false,
                        onClick = { adding = true },
                        headlineContent = { Text("Add a managed profile") },
                        supportingContent = { Text("For family members without their own Plex account") },
                        leadingContent = { Text("+", fontSize = 22.sp, fontWeight = FontWeight.Bold) },
                    )
                }
                RequestInitialFocus(first, focused)
            }
        }
    }

    selected?.let { user ->
        ProfileOptions(
            user = user,
            isCurrent = user.id == client.profileId,
            isOwner = isOwner,
            hiddenHere = user.id in hidden,
            onSwitch = {
                selected = null
                onSwitchUser()
            },
            onToggleHidden = {
                hidden = if (user.id in hidden) hidden - user.id else hidden + user.id
                client.hiddenProfiles = hidden
            },
            onRemove = {
                selected = null
                scope.launch {
                    notice = try {
                        client.removeProfile(user)
                        reload++
                        "${user.title} was removed from your Plex Home."
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        "Couldn't remove ${user.title}: ${e.message}"
                    }
                }
            },
            onClose = { selected = null },
        )
    }

    if (adding) {
        AddProfileDialog(
            onCreate = { name ->
                adding = false
                scope.launch {
                    notice = try {
                        client.addManagedProfile(name)
                        reload++
                        "Added $name. Choose which libraries they can see at plex.tv → Settings → Manage Library Access."
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: PlexException) {
                        if (e.code == 401 || e.code == 403) "Only the Plex Home's owner can add profiles."
                        else "Couldn't add $name: ${e.message}"
                    } catch (e: Exception) {
                        "Couldn't add $name: ${e.message}"
                    }
                }
            },
            onClose = { adding = false },
        )
    }
}

/** The side panel for one profile. Removing asks you to press OK a second time. */
@Composable
private fun ProfileOptions(
    user: HomeUser,
    isCurrent: Boolean,
    isOwner: Boolean,
    hiddenHere: Boolean,
    onSwitch: () -> Unit,
    onToggleHidden: () -> Unit,
    onRemove: () -> Unit,
    onClose: () -> Unit,
) {
    var confirmRemove by remember { mutableStateOf(false) }
    SidePanel(title = user.title, onClose = onClose) { first ->
        if (!isCurrent) {
            PickerRow("Switch to ${user.title}", false, first) { onSwitch() }
        }
        if (!user.admin) {
            PickerRow(
                "Show on this TV's profile picker",
                false,
                if (isCurrent) first else Modifier,
                value = if (hiddenHere) "Hidden" else "Shown",
            ) { onToggleHidden() }
        }
        if (isOwner && !user.admin) {
            PickerRow(
                if (confirmRemove) "Press OK again to remove ${user.title}" else "Remove from Plex Home",
                false,
                Modifier.onFocusChanged { if (!it.isFocused) confirmRemove = false },
                value = if (user.restricted) "Deletes this profile" else "They keep their Plex account",
            ) {
                if (confirmRemove) onRemove() else confirmRemove = true
            }
        }
        if (user.admin && isCurrent) {
            Text("This is the Plex Home's owner, which is always shown.", fontSize = 13.sp, color = palette.muted)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "PINs, names and library access are changed on plex.tv → Settings → Plex Home.",
            fontSize = 12.sp,
            color = palette.muted,
        )
    }
}

/** Typing a name for a new managed profile (the TV's keyboard opens when you select the box). */
@Composable
private fun AddProfileDialog(onCreate: (String) -> Unit, onClose: () -> Unit) {
    var name by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    SidePanel(title = "Add a managed profile", onClose = onClose) { first ->
        var fieldFocused by remember { mutableStateOf(false) }
        Box(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(palette.text.copy(alpha = 0.06f))
                .border(2.dp, if (fieldFocused) palette.text else palette.text.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = name,
                onValueChange = { name = it.take(40) },
                singleLine = true,
                textStyle = TextStyle(color = palette.text, fontSize = 16.sp),
                cursorBrush = SolidColor(palette.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    keyboard?.hide()
                    if (name.isNotBlank()) onCreate(name.trim())
                }),
                modifier = first
                    .fillMaxWidth()
                    .onFocusChanged {
                        fieldFocused = it.isFocused
                        if (it.isFocused) keyboard?.show()
                    },
                decorationBox = { inner ->
                    if (name.isEmpty()) Text("Name", fontSize = 16.sp, color = palette.muted)
                    inner()
                },
            )
        }
        Spacer(Modifier.height(6.dp))
        PickerRow("Create", false, Modifier, value = if (name.isBlank()) "Type a name first" else null) {
            if (name.isNotBlank()) onCreate(name.trim())
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Managed profiles are for family members without their own Plex account. " +
                "Afterwards, choose which libraries they can see on plex.tv.",
            fontSize = 12.sp,
            color = palette.muted,
        )
    }
}

/**
 * A panel on the right on its own layer (focus can't wander behind it). [content] gets the
 * modifier for the row that should be highlighted first.
 */
@Composable
internal fun SidePanel(title: String, onClose: () -> Unit, content: @Composable (first: Modifier) -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0.25f) }
        BackHandler { onClose() }
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            repeat(4) {
                withFrameNanos { }
                if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect
            }
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
            Column(
                Modifier
                    .padding(24.dp)
                    .width(440.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(16.dp))
                    .background(palette.surface.copy(alpha = 0.97f))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
                Spacer(Modifier.height(10.dp))
                key(title) { content(Modifier.focusRequester(first)) }
            }
        }
    }
}
