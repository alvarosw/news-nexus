package com.beyondlevi.nexus.news

import android.view.KeyEvent
import com.anezium.rokidbus.client.plugin.NexusCard
import com.anezium.rokidbus.client.plugin.NexusPluginService
import com.anezium.rokidbus.client.plugin.NexusReader
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.client.plugin.NexusSurfaceSession
import com.anezium.rokidbus.shared.plugin.NexusInputEvent

/**
 * The one exported Nexus plugin service: an adapter, nothing more. The hub can
 * cold-start it after the process was stopped, so it owns no state of its own and
 * builds its runtime on open.
 *
 * Input is the R08 Access Bridge contract: the ring is a system input provider, so
 * its four verbs arrive here as DPAD-style key events. Both directional aliases of
 * each verb are handled and only ACTION_DOWN is acted on — the hub already
 * deduplicates paired directional aliases.
 */
class NewsPluginService : NexusPluginService() {

    private var runtime: NewsRuntime? = null
    private var surface: NexusSurfaceSession? = null

    override fun onNexusOpen() {
        val session = nexusSurfaceSession(NewsSurfaces.SURFACE_ID)
        surface = session
        val active = NewsRuntime(FeedStore(applicationContext))
        runtime = active
        active.open(
            object : NewsRuntime.Host {
                override fun showCard(card: NexusCard): NexusSdkResult =
                    session?.showCard(card) ?: NexusSdkResult.NOT_REGISTERED

                override fun updateCard(card: NexusCard): NexusSdkResult =
                    session?.updateCard(card) ?: NexusSdkResult.NOT_REGISTERED

                override fun showReader(reader: NexusReader): NexusSdkResult =
                    session?.showReader(reader) ?: NexusSdkResult.NOT_REGISTERED

                override fun updateReader(reader: NexusReader): NexusSdkResult =
                    session?.updateReader(reader) ?: NexusSdkResult.NOT_REGISTERED

                override fun hideSurface() {
                    session?.hide()
                }

                override fun dataPlaneUp(): Boolean = nexusClient?.supportsImageSurface == true
            },
        )
    }

    override fun onNexusClose() {
        runtime?.close()
        runtime = null
        surface = null
    }

    override fun onNexusInput(event: NexusInputEvent) {
        if (event.action != KeyEvent.ACTION_DOWN) return
        val active = runtime ?: return
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_DOWN,
            -> active.onNext()

            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_UP,
            -> active.onPrev()

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            -> active.onSelect()

            KeyEvent.KEYCODE_BACK -> active.onBack()

            else -> return
        }
    }
}
