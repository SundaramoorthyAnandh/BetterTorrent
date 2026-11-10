package com.bettertorrent.app

import android.app.Application
import com.bettertorrent.app.data.SettingsRepository
import com.bettertorrent.app.engine.EngineEvent
import com.bettertorrent.app.engine.TorrentEngine
import com.bettertorrent.app.service.BackgroundState
import com.bettertorrent.app.service.Notifications
import com.bettertorrent.app.service.TorrentService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class BetterTorrentApp : Application() {

    lateinit var settings: SettingsRepository
        private set
    lateinit var engine: TorrentEngine
        private set

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Must run before anything marks transfers active again in this process.
        BackgroundState.checkForKill(this)
        killAlert.value = BackgroundState.killAlertPending(this)
        settings = SettingsRepository(this)
        engine = TorrentEngine(this, settings)
        Notifications.createChannels(this)

        appScope.launch {
            engine.events.collect { e ->
                if (e is EngineEvent.Completed) Notifications.showCompleted(this@BetterTorrentApp, e.hash, e.name)
            }
        }
        // Keep the foreground service up while anything is transferring or streaming.
        appScope.launch {
            combine(engine.torrents, engine.streamServer.activeStreams) { list, streams ->
                list.any { it.state.isActive } || streams > 0
            }.distinctUntilChanged().collect { busy -> if (busy) TorrentService.start(this@BetterTorrentApp) }
        }
        appScope.launch { engine.ensureStarted() }
        appScope.launch {
            settings.settings.collect { BackgroundState.setResumeOnBoot(this@BetterTorrentApp, it.resumeAfterReboot) }
        }
    }

    /** True when the phone stopped the app mid-download; shown as a banner until dismissed. */
    val killAlert = MutableStateFlow(false)

    fun dismissKillAlert() {
        killAlert.value = false
        BackgroundState.clearKillAlert(this)
    }

    companion object {
        lateinit var instance: BetterTorrentApp
            private set
    }
}

/** App-wide one-shot signals. */
object AppEvents {
    private val _exit = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val exit: SharedFlow<Unit> = _exit.asSharedFlow()
    fun requestExit() {
        _exit.tryEmit(Unit)
    }
}
