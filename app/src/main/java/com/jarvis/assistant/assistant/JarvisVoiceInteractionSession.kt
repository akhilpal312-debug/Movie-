package com.jarvis.assistant.assistant

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.jarvis.assistant.core.model.VoiceState
import com.jarvis.assistant.voice.VoiceSessionController
import com.jarvis.assistant.voice.VoiceUiState
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The sheet shown when the assistant gesture launches JARVIS.
 *
 * Built with plain views rather than Compose because a VoiceInteractionSession window
 * has no ViewTreeLifecycleOwner, and a ComposeView there would crash. It is a real,
 * working panel: it starts listening, shows the live state and offers Stop.
 */
class JarvisVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null
    private var controller: VoiceSessionController? = null
    private var statusView: TextView? = null
    private var transcriptView: TextView? = null
    private var stopButton: Button? = null

    override fun onCreate() {
        super.onCreate()
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            AssistantEntryPoint::class.java,
        )
        controller = entryPoint.voiceSessionController()
    }

    override fun onCreateContentView(): View {
        val sheet = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(Color.parseColor("#F21A222C"))
            }
        }

        val title = TextView(context).apply {
            text = "JARVIS"
            setTextColor(Color.parseColor("#EAF2F8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            gravity = Gravity.START
        }

        statusView = TextView(context).apply {
            setTextColor(Color.parseColor("#9FB3C8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            text = "Tap the mic to start"
        }

        transcriptView = TextView(context).apply {
            setTextColor(Color.parseColor("#D7E3EE"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, dp(10), 0, dp(10))
            text = ""
        }

        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val micButton = Button(context).apply {
            text = "Mic"
            setOnClickListener { controller?.startListening() }
        }

        stopButton = Button(context).apply {
            text = "Stop"
            setOnClickListener { controller?.stopEverything() }
        }

        val closeButton = Button(context).apply {
            text = "Close"
            setOnClickListener {
                controller?.stopEverything()
                hide()
            }
        }

        controls.addView(micButton)
        controls.addView(stopButton)
        controls.addView(closeButton)

        sheet.addView(title)
        sheet.addView(statusView)
        sheet.addView(transcriptView)
        sheet.addView(controls)

        stateJob = scope.launch {
            controller?.state?.collectLatest { render(it) }
        }
        return sheet
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        controller?.startListening()
    }

    override fun onHide() {
        controller?.stopEverything()
        super.onHide()
    }

    override fun onDestroy() {
        stateJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun render(state: VoiceUiState) {
        statusView?.text = when (state.state) {
            VoiceState.IDLE -> state.statusMessage.ifBlank { "Tap Mic to speak" }
            VoiceState.LISTENING -> "Listening…"
            VoiceState.CONNECTING -> "Connecting…"
            VoiceState.PROCESSING -> "Working on it…"
            VoiceState.SPEAKING -> "Speaking — tap Stop to interrupt"
            VoiceState.RECONNECTING -> "Reconnecting…"
            VoiceState.ERROR -> state.error ?: "Something went wrong"
        }
        transcriptView?.text = listOf(state.userText, state.assistantText)
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
        stopButton?.isEnabled = state.state != VoiceState.IDLE
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AssistantEntryPoint {
    fun voiceSessionController(): VoiceSessionController
}
