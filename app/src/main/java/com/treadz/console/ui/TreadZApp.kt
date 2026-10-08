package com.treadz.console.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.treadz.console.AppDependencies
import com.treadz.console.MainViewModel
import com.treadz.console.controls.ControlAxis
import com.treadz.console.history.HistoryViewModel
import com.treadz.console.history.WorkoutDetailViewModel
import com.treadz.console.nav.NavigationViewModel
import com.treadz.console.nav.Screen
import com.treadz.console.treadmill.TreadmillAlert
import com.treadz.console.users.ProfileEditorViewModel
import com.treadz.console.users.UserSelectionViewModel
import com.treadz.console.weight.WeightViewModel

/**
 * App root: connects the ViewModels with the screens and hosts the navigation. The safety alert is drawn here, above
 * whatever screen is open.
 */
@Composable
fun TreadZRoot(
    dependencies: AppDependencies,
    viewModel: MainViewModel,
    permissions: PermissionsUi,
    actions: MainActions,
    navigation: NavigationViewModel = viewModel(
        factory = viewModelFactory { initializer { NavigationViewModel(dependencies.userData.profiles) } },
    ),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val stack by navigation.stack.collectAsStateWithLifecycle()
    val screen = stack?.lastOrNull()

    BackHandler(enabled = (stack?.size ?: 0) > 1) { navigation.pop() }

    val controls = viewModel.controls
    val controlActions = remember(viewModel, navigation) {
        ControlActions(
            onStop = controls::stop,
            onPause = controls::pause,
            onResume = controls::resume,
            onSpeedPress = { controls.startRepeat(ControlAxis.Speed, it) },
            onSpeedRelease = controls::stopRepeat,
            onSpeedStep = controls::stepSpeed,
            onInclinePress = { controls.startRepeat(ControlAxis.Incline, it) },
            onInclineRelease = controls::stopRepeat,
            onInclineStep = controls::stepIncline,
            onSetSpeed = controls::setSpeed,
            onSetIncline = controls::setIncline,
            onBackToMain = { navigation.replaceAll(Screen.Main) },
        )
    }

    TreadZFrame(
        darkTheme = ui.darkTheme,
        keepScreenOn = ui.keepScreenOn,
        alert = ui.alert,
        onDismissAlert = viewModel::dismissAlert,
        onStop = controls::stop.takeIf { ui.showStop },
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Anything but the main screen: STOP and the speed stay in view while the belt is not at rest.
            if (screen != null && screen != Screen.Main && ui.beltActive) SessionBar(ui, controlActions)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (screen) {
                    null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

                    Screen.Main -> MainScreen(
                        ui = ui,
                        permissions = permissions,
                        actions = actions.copy(
                            onStart = viewModel::start,
                            onScan = viewModel::startScan,
                            onStopScan = viewModel::stopScan,
                            onConnect = { viewModel.connect(it.address, it.name) },
                            onReconnectRemembered = viewModel::reconnectToRemembered,
                            onStopReconnecting = viewModel::stopReconnecting,
                            onDismissAlert = viewModel::dismissAlert,
                            onDismissRecordingNotice = viewModel::dismissRecordingNotice,
                            controls = controlActions,
                            onDarkThemeChange = viewModel::setDarkTheme,
                            onOpenUsers = { navigation.push(Screen.UserSelection) },
                            onOpenWeight = { navigation.push(Screen.Weight) },
                            onOpenHistory = { navigation.push(Screen.History) },
                            onPersonalize = { ui.activeUser?.let { navigation.openEditor(it.id) } },
                        ),
                    )

                    Screen.UserSelection -> {
                        val vm = viewModel<UserSelectionViewModel>(
                            factory = viewModelFactory {
                                initializer { UserSelectionViewModel(dependencies.userData.profiles, dependencies.controller, dependencies::today) }
                            },
                        )
                        val state by vm.uiState.collectAsStateWithLifecycle()
                        UserSelectionScreen(
                            state = state,
                            actions = UserSelectionActions(
                                onSelect = { id -> vm.select(id) { navigation.replaceAll(Screen.Main) } },
                                onEdit = { id -> navigation.openEditor(id) },
                                onAdd = { navigation.openEditor(null) },
                                onBack = if ((stack?.size ?: 0) > 1) navigation::pop else null,
                            ),
                        )
                    }

                    is Screen.ProfileEditor -> {
                        val vm = viewModel<ProfileEditorViewModel>(
                            key = "editor-${screen.profileId}-${screen.token}",
                            factory = viewModelFactory {
                                initializer {
                                    ProfileEditorViewModel(screen.profileId, dependencies.userData.profiles, dependencies.controller, dependencies::today)
                                }
                            },
                        )
                        val state by vm.uiState.collectAsStateWithLifecycle()
                        ProfileEditorScreen(
                            state = state,
                            today = dependencies.today(),
                            actions = ProfileEditorActions(
                                onBack = navigation::pop,
                                onName = vm::setName,
                                onSex = vm::setSex,
                                onHeight = vm::setHeight,
                                onBirthDate = vm::setBirthDate,
                                onRestingHeartRate = vm::setRestingHeartRate,
                                onMaxHeartRate = vm::setMaxHeartRate,
                                onFitnessLevel = vm::setFitnessLevel,
                                onGoal = vm::setGoal,
                                onWeight = vm::setWeight,
                                onSave = { vm.save(onSaved = navigation::pop) },
                                onDelete = vm::requestDelete,
                                onConfirmDelete = { vm.confirmDelete(onDeleted = navigation::onProfileDeleted) },
                                onCancelDelete = vm::cancelDelete,
                            ),
                        )
                    }

                    Screen.Weight -> {
                        val vm = viewModel<WeightViewModel>(
                            factory = viewModelFactory { initializer { WeightViewModel(dependencies.userData, dependencies::today) } },
                        )
                        val state by vm.uiState.collectAsStateWithLifecycle()
                        WeightScreen(
                            state = state,
                            today = dependencies.today(),
                            actions = WeightActions(
                                onBack = navigation::pop,
                                onAdd = vm::openAdd,
                                onEdit = vm::openEdit,
                                onDelete = vm::requestDelete,
                                onConfirmDelete = vm::confirmDelete,
                                onCancelDelete = vm::cancelDelete,
                                onSetTarget = vm::openTarget,
                                onEditorDate = vm::setDate,
                                onEditorWeight = vm::setWeightText,
                                onSaveEditor = vm::saveEditor,
                                onDismissEditor = vm::dismissEditor,
                                onClearTarget = vm::clearTarget,
                            ),
                        )
                    }

                    Screen.History -> {
                        val vm = viewModel<HistoryViewModel>(
                            factory = viewModelFactory { initializer { HistoryViewModel(dependencies.userData) } },
                        )
                        val state by vm.uiState.collectAsStateWithLifecycle()
                        HistoryScreen(
                            state = state,
                            actions = HistoryActions(
                                onBack = navigation::pop,
                                onOpen = { navigation.push(Screen.WorkoutDetail(it)) },
                                onTab = vm::selectTab,
                                onDelete = vm::requestDelete,
                                onConfirmDelete = vm::confirmDelete,
                                onCancelDelete = vm::cancelDelete,
                                onAssign = vm::requestAssign,
                                onAssignTo = vm::assignTo,
                                onCancelAssign = vm::cancelAssign,
                            ),
                        )
                    }

                    is Screen.WorkoutDetail -> {
                        val vm = viewModel<WorkoutDetailViewModel>(
                            key = "workout-${screen.workoutId}",
                            factory = viewModelFactory { initializer { WorkoutDetailViewModel(screen.workoutId, dependencies.userData, dependencies.exporter) } },
                        )
                        val state by vm.uiState.collectAsStateWithLifecycle()
                        WorkoutDetailScreen(
                            state = state,
                            actions = WorkoutDetailActions(
                                onBack = navigation::pop,
                                onDelete = vm::requestDelete,
                                onConfirmDelete = { vm.confirmDelete(onDeleted = navigation::pop) },
                                onCancelDelete = vm::cancelDelete,
                                onAssign = vm::requestAssign,
                                onAssignTo = vm::assignTo,
                                onCancelAssign = vm::cancelAssign,
                                onExport = vm::requestExport,
                                onExportFormat = { format -> vm.export(format, actions.onShareExport) },
                                onCancelExport = vm::cancelExport,
                                onDismissExportNotice = vm::dismissExportNotice,
                                onShareExport = actions.onShareExport,
                            ),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Theme, system-bar insets, window size classes and the safety alert overlay, shared by every screen. [content]
 * reads the layout from [LocalLayout].
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun TreadZFrame(
    darkTheme: Boolean,
    keepScreenOn: Boolean,
    alert: TreadmillAlert?,
    onDismissAlert: () -> Unit,
    onStop: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    TreadZTheme(darkTheme = darkTheme) {
        KeepScreenOn(keepScreenOn)
        Surface(
            Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                @Suppress("DEPRECATION")
                val sizeClass = WindowSizeClass.calculateFromSize(DpSize(maxWidth, maxHeight))
                val compact = sizeClass.widthSizeClass == WindowWidthSizeClass.Compact
                // Two panes only with a wide landscape window (tablet in landscape).
                val wide = !compact && maxWidth > maxHeight
                CompositionLocalProvider(LocalLayout provides LayoutInfo(compact = compact, wide = wide)) {
                    content()
                }
            }
            alert?.let { AlertOverlay(alert = it, onDismiss = onDismissAlert, onStop = onStop) }
        }
    }
}

@Composable
private fun KeepScreenOn(keepOn: Boolean) {
    val view = LocalView.current
    DisposableEffect(keepOn) {
        view.keepScreenOn = keepOn
        onDispose { view.keepScreenOn = false }
    }
}
