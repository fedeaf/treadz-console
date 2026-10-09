package com.treadz.console.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
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
import com.treadz.console.BuildConfig
import com.treadz.console.MainViewModel
import com.treadz.console.R
import com.treadz.console.controls.ControlAxis
import com.treadz.console.controls.SessionKind
import com.treadz.console.history.HistoryViewModel
import com.treadz.console.history.WorkoutDetailViewModel
import com.treadz.console.lifecycle.ExitPrompt
import com.treadz.console.lifecycle.ExitViewModel
import com.treadz.console.nav.NavigationViewModel
import com.treadz.console.nav.Screen
import com.treadz.console.settings.SettingsViewModel
import com.treadz.console.sync.service.SyncSetup
import com.treadz.console.treadmill.RecordingStatus
import com.treadz.console.treadmill.TreadmillAlert
import com.treadz.console.training.FitnessResultViewModel
import com.treadz.console.training.FitnessStartViewModel
import com.treadz.console.training.FitnessTestsViewModel
import com.treadz.console.training.ProgramEditorViewModel
import com.treadz.console.training.ProgramStartViewModel
import com.treadz.console.training.ProgramsViewModel
import com.treadz.console.training.RunViewModel
import com.treadz.console.users.ProfileEditorViewModel
import com.treadz.console.users.UserSelectionViewModel
import com.treadz.console.weight.WeightViewModel
import kotlinx.coroutines.flow.map
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.getValue

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
    exit: ExitViewModel = viewModel(
        factory = viewModelFactory { initializer { ExitViewModel(dependencies.appLifecycle, dependencies.controller) } },
    ),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val stack by navigation.stack.collectAsStateWithLifecycle()
    val screen = stack?.lastOrNull()

    val stackSize = stack?.size ?: 0
    BackHandler(enabled = stackSize > 1) { navigation.pop() }
    // Back on the root screen: ask before leaving (or say the belt must be stopped first), never close silently.
    BackHandler(enabled = stackSize == 1, onBack = exit::onBack)
    val exitPrompt by exit.prompt.collectAsStateWithLifecycle()

    val controls = viewModel.controls
    val training = dependencies.training
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
            if (exitPrompt == ExitPrompt.BeltMoving) BeltMovingNotice(onDismiss = exit::dismiss)
            // Anything but the main screen: STOP and the speed stay in view while the belt is not at rest.
            if (screen != null && screen != Screen.Main && ui.beltActive) SessionBar(ui, controlActions)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (screen) {
                    null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

                    Screen.Main -> {
                        val visible by training.visible.collectAsStateWithLifecycle()
                        if (visible == SessionKind.None) {
                            MainScreen(
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
                                    onOpenSettingsScreen = { navigation.push(Screen.Settings) },
                                    onOpenUsers = { navigation.push(Screen.UserSelection) },
                                    onOpenWeight = { navigation.push(Screen.Weight) },
                                    onOpenHistory = { navigation.push(Screen.History) },
                                    onOpenPrograms = { navigation.push(Screen.Programs) },
                                    onPersonalize = { ui.activeUser?.let { navigation.openEditor(it.id) } },
                                ),
                            )
                        } else {
                            // A program or a fitness test is on (or has just ended): its screen takes the place of the live data.
                            val runVm = viewModel<RunViewModel>(
                                factory = viewModelFactory { initializer { RunViewModel(training, dependencies.userData) } },
                            )
                            val run by runVm.uiState.collectAsStateWithLifecycle()
                            val runActions = RunActions(
                                onSkipPrevious = runVm::skipToPrevious,
                                onSkipNext = runVm::skipToNext,
                                onContinue = runVm::continueProgram,
                                onAbandon = runVm::abandon,
                                onExhausted = runVm::exhausted,
                                onEndTest = runVm::endTest,
                                onDismiss = runVm::dismiss,
                                onUseMeasuredHeartRateMax = runVm::useMeasuredHeartRateMax,
                                onKeepManualHeartRateMax = runVm::keepManualHeartRateMax,
                            )
                            if (visible == SessionKind.Program) ProgramRunScreen(ui, run, controlActions, runActions)
                            else FitnessRunScreen(ui, run, controlActions, runActions)
                        }
                    }

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

                    Screen.Settings -> {
                        val vm = viewModel<SettingsViewModel>(
                            factory = viewModelFactory {
                                initializer { SettingsViewModel(dependencies.sync, dependencies.language, viewModel.uiState) }
                            },
                        )
                        val state by vm.uiState.collectAsStateWithLifecycle()
                        val activity = LocalActivity.current
                        LaunchedEffect(Unit) { vm.refreshLanguage() }
                        // A token that was typed but not linked is not kept around when the screen is left (a rotation or a
                        // language change recreates the screen and keeps it).
                        DisposableEffect(Unit) {
                            onDispose { if (activity?.isChangingConfigurations != true) vm.clearToken() }
                        }
                        val recording = ui.recording as? RecordingStatus.Saved
                        SettingsScreen(
                            state = state,
                            permissions = permissions,
                            versionName = BuildConfig.VERSION_NAME,
                            actions = SettingsActions(
                                onBack = navigation::pop,
                                sync = SyncSettingsActions(
                                    onToken = vm::setToken,
                                    onTokenVisible = vm::setTokenVisible,
                                    onSaveToken = vm::saveToken,
                                    onSelectRepository = vm::selectRepository,
                                    onConfirmFirstLink = vm::confirmFirstLink,
                                    onCancelFirstLink = vm::cancelFirstLink,
                                    onCancelChoice = vm::cancelChoice,
                                    onDismissError = vm::dismissError,
                                    onSyncNow = vm::syncNow,
                                    onUnlink = vm::requestUnlink,
                                    onConfirmUnlink = vm::confirmUnlink,
                                    onCancelUnlink = vm::cancelUnlink,
                                ),
                                onOpenUsers = { navigation.push(Screen.UserSelection) },
                                onForgetTreadmill = viewModel::forgetTreadmill,
                                onDarkTheme = viewModel::setDarkTheme,
                                onLanguage = vm::setLanguage,
                                onOpenAppSettings = actions.onOpenSettings,
                                onOpenDownloads = actions.onOpenDownloads,
                                onShareRecording = recording?.let { saved -> { actions.onShare(saved.info) } },
                            ),
                        )
                    }

                    is Screen.ProfileEditor -> {
                        val vm = viewModel<ProfileEditorViewModel>(
                            key = "editor-${screen.profileId}-${screen.token}",
                            factory = viewModelFactory {
                                initializer {
                                    ProfileEditorViewModel(
                                        screen.profileId, dependencies.userData.profiles, dependencies.controller, dependencies::today,
                                        syncLinked = dependencies.sync.status.map { it.setup == SyncSetup.Linked },
                                        fitnessTests = dependencies.userData.fitnessTests,
                                    )
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
                                onUseMeasuredHeartRateMax = vm::useMeasuredHeartRateMax,
                                onKeepManualHeartRateMax = vm::keepManualHeartRateMax,
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
                            factory = viewModelFactory { initializer { HistoryViewModel(dependencies.userData, dependencies.sync) } },
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
                                onOpenTest = { navigation.push(Screen.FitnessResult(it)) },
                            ),
                        )
                    }

                    Screen.Programs -> {
                        val context = LocalContext.current
                        val vm = viewModel<ProgramsViewModel>(
                            factory = viewModelFactory {
                                initializer { ProgramsViewModel(dependencies.userData) { name -> context.getString(R.string.program_copy_name, name) } }
                            },
                        )
                        val state by vm.uiState.collectAsStateWithLifecycle()
                        ProgramsScreen(
                            state = state,
                            actions = ProgramsActions(
                                onBack = navigation::pop,
                                onOpen = { navigation.push(Screen.ProgramStart(it)) },
                                onEdit = { navigation.openProgramEditor(it) },
                                onNew = { navigation.openProgramEditor(null) },
                                onDuplicate = { id -> vm.duplicate(id) },
                                onDelete = vm::requestDelete,
                                onConfirmDelete = vm::confirmDelete,
                                onCancelDelete = vm::cancelDelete,
                                onOpenFitnessTests = { navigation.push(Screen.FitnessTests) },
                            ),
                        )
                    }

                    is Screen.ProgramStart -> {
                        val vm = viewModel<ProgramStartViewModel>(
                            key = "program-start-${screen.programId}",
                            factory = viewModelFactory {
                                initializer { ProgramStartViewModel(screen.programId, dependencies.userData, training, dependencies.controller) }
                            },
                        )
                        val state by vm.uiState.collectAsStateWithLifecycle()
                        ProgramStartScreen(
                            state = state,
                            actions = ProgramStartActions(
                                onBack = navigation::pop,
                                onDurationStep = vm::stepDuration,
                                onEasySpeed = vm::setEasySpeed,
                                onEasySpeedStep = vm::stepEasySpeed,
                                onMaxSpeed = vm::setMaxSpeed,
                                onMaxSpeedStep = vm::stepMaxSpeed,
                                onMaxIncline = vm::setMaxIncline,
                                onMaxInclineStep = vm::stepMaxIncline,
                                onAdjustmentStep = vm::stepAdjustment,
                                onStart = { vm.start(onStarted = { navigation.replaceAll(Screen.Main) }) },
                                onSaveAsFree = { name -> vm.saveAsFreeProgram(name) { id -> navigation.openProgramEditor(id) } },
                            ),
                        )
                    }

                    is Screen.ProgramEditor -> {
                        val vm = viewModel<ProgramEditorViewModel>(
                            key = "program-editor-${screen.programId}-${screen.token}",
                            factory = viewModelFactory {
                                initializer { ProgramEditorViewModel(screen.programId, dependencies.userData, dependencies.controller) }
                            },
                        )
                        val state by vm.uiState.collectAsStateWithLifecycle()
                        BackHandler { vm.requestBack(navigation::pop) }
                        ProgramEditorScreen(
                            state = state,
                            actions = ProgramEditorActions(
                                onBack = { vm.requestBack(navigation::pop) },
                                onName = vm::setName,
                                onDescription = vm::setDescription,
                                onAddBlock = vm::addBlock,
                                onAddRepeat = vm::addRepeat,
                                onRemoveStep = vm::removeStep,
                                onMoveStep = vm::moveStep,
                                onDuplicateStep = vm::duplicateStep,
                                onEditBlock = vm::editBlock,
                                onRepeatCount = vm::setRepeatCount,
                                onAddBlockToRepeat = vm::addBlockToRepeat,
                                onRemoveBlockFromRepeat = vm::removeBlockFromRepeat,
                                onMoveBlockInRepeat = vm::moveBlockInRepeat,
                                onSave = { vm.save(onSaved = navigation::pop) },
                                onDelete = vm::requestDelete,
                                onConfirmDelete = { vm.confirmDelete(onDeleted = navigation::pop) },
                                onCancelDelete = vm::cancelDelete,
                                onDiscard = navigation::pop,
                                onCancelDiscard = vm::cancelDiscard,
                            ),
                        )
                    }

                    Screen.FitnessTests -> {
                        val vm = viewModel<FitnessTestsViewModel>(
                            factory = viewModelFactory { initializer { FitnessTestsViewModel(dependencies.userData) } },
                        )
                        val state by vm.uiState.collectAsStateWithLifecycle()
                        FitnessTestsScreen(
                            state = state,
                            actions = FitnessTestsActions(
                                onBack = navigation::pop,
                                onStartTest = { navigation.push(Screen.FitnessStart(it)) },
                                onOpenResult = { navigation.push(Screen.FitnessResult(it)) },
                                onDelete = vm::requestDelete,
                                onConfirmDelete = vm::confirmDelete,
                                onCancelDelete = vm::cancelDelete,
                                onUseMeasuredHeartRateMax = vm::useMeasuredHeartRateMax,
                                onKeepManualHeartRateMax = vm::keepManualHeartRateMax,
                            ),
                        )
                    }

                    is Screen.FitnessStart -> {
                        val vm = viewModel<FitnessStartViewModel>(
                            key = "fitness-start-${screen.type}",
                            factory = viewModelFactory {
                                initializer { FitnessStartViewModel(screen.type, dependencies.userData, training, dependencies.controller, dependencies::today) }
                            },
                        )
                        val state by vm.uiState.collectAsStateWithLifecycle()
                        FitnessStartScreen(
                            state = state,
                            actions = FitnessStartActions(
                                onBack = navigation::pop,
                                onSpeed = vm::setSpeed,
                                onSpeedStep = vm::stepSpeed,
                                onStart = { vm.start(onStarted = { navigation.replaceAll(Screen.Main) }) },
                            ),
                        )
                    }

                    is Screen.FitnessResult -> {
                        val vm = viewModel<FitnessResultViewModel>(
                            key = "fitness-result-${screen.testId}",
                            factory = viewModelFactory { initializer { FitnessResultViewModel(screen.testId, dependencies.userData) } },
                        )
                        val state by vm.uiState.collectAsStateWithLifecycle()
                        FitnessResultScreen(
                            state = state,
                            actions = FitnessResultActions(
                                onBack = navigation::pop,
                                onDelete = vm::requestDelete,
                                onConfirmDelete = { vm.confirmDelete(onDeleted = navigation::pop) },
                                onCancelDelete = vm::cancelDelete,
                                onUseMeasuredHeartRateMax = vm::useMeasuredHeartRateMax,
                                onKeepManualHeartRateMax = vm::keepManualHeartRateMax,
                            ),
                        )
                    }
                }
            }
        }
        ExitPromptHost(
            prompt = exitPrompt,
            onExit = { exit.exit(onExiting = actions.onFinish) },
            onKeepInBackground = { exit.keepInBackground(actions.onMoveToBackground) },
            onDismiss = exit::dismiss,
        )
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
