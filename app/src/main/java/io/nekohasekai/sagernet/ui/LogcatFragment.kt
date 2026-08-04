package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.CollapsingToolbarLayout
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.LayoutLogcatBinding
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.bottomsheet.LogcatMenuBottomSheet
import io.nekohasekai.sagernet.ui.toolbar.LogcatMenuController
import io.nekohasekai.sagernet.widget.ListListener
import libbox.*
import moe.matsuri.nb4a.utils.SendLog
import java.util.ArrayDeque

class LogcatFragment : ToolbarFragment(R.layout.layout_logcat),
    LogcatMenuBottomSheet.OnOptionClickListener {

    lateinit var binding: LayoutLogcatBinding

    private lateinit var menuController: LogcatMenuController
    private val logLines = ArrayDeque<String>()
    private var logClient: CommandClient? = null
    @Volatile
    private var logClientGeneration = 0

    @SuppressLint("RestrictedApi", "WrongConstant")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = LayoutLogcatBinding.bind(view)

        val collapsingToolbar = binding.collapsingToolbar
        val toolbarView = binding.toolbar
        val appBarLayout = binding.appbar

        collapsingToolbar.title = getString(R.string.menu_log)
        
        toolbar = toolbarView
        
        menuController = LogcatMenuController(
            toolbar = toolbar,
            fragmentManager = childFragmentManager,
            listener = this
        )

        if (Build.VERSION.SDK_INT >= 23) {
            binding.textview.breakStrategy = 0 // simple
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root, ListListener)

        binding.scroolview.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            appBarLayout.setExpanded(scrollY == 0)
        }

        reloadSession()
    }

    override fun onResume() {
        super.onResume()
        if (::menuController.isInitialized) {
            menuController.refresh()
        }
    }

    override fun onStart() {
        super.onStart()
        connectLogClient()
    }

    override fun onStop() {
        disconnectLogClient()
        super.onStop()
    }

    private fun connectLogClient() {
        disconnectLogClient()
        logLines.clear()
        reloadSession()
        val generation = ++logClientGeneration
        val options = CommandClientOptions().apply {
            addCommand(Libbox.CommandLog)
        }
        val client = CommandClient(newLogHandler(generation), options)
        logClient = client
        runOnDefaultDispatcher {
            try {
                client.connect()
            } catch (e: Exception) {
                if (generation == logClientGeneration) Logs.w(e)
            }
        }
    }

    private fun disconnectLogClient() {
        val client = logClient
        logClient = null
        logClientGeneration++
        if (client != null) runOnDefaultDispatcher {
            runCatching { client.disconnect() }
        }
    }

    private fun newLogHandler(generation: Int) = object : CommandClientHandler {
        override fun connected() = Unit

        override fun disconnected(message: String?) = Unit

        override fun setDefaultLogLevel(level: Int) = Unit

        override fun clearLogs() {
            runOnMainDispatcher {
                if (generation != logClientGeneration) return@runOnMainDispatcher
                logLines.clear()
                reloadSession()
            }
        }

        override fun writeLogs(messageList: LogIterator?) {
            if (messageList == null || generation != logClientGeneration) return
            val messages = mutableListOf<String>()
            while (messageList.hasNext()) {
                messages += messageList.next().message.trimEnd('\r', '\n')
            }
            runOnMainDispatcher {
                if (generation != logClientGeneration) return@runOnMainDispatcher
                val maxLines = DataStore.logBufSize.takeIf { it > 0 } ?: 50
                messages.forEach(logLines::addLast)
                while (logLines.size > maxLines) logLines.removeFirst()
                reloadSession()
            }
        }

        override fun writeGroups(message: OutboundGroupIterator?) = Unit

        override fun writeOutbounds(message: OutboundGroupItemIterator?) = Unit

        override fun writeStatus(message: StatusMessage) = Unit

        override fun initializeClashMode(modeList: StringIterator, currentMode: String) = Unit

        override fun updateClashMode(newMode: String) = Unit

        override fun writeConnectionEvents(events: ConnectionEvents?) = Unit
    }

    private fun getColorForLine(line: String): ForegroundColorSpan {
        var color = ForegroundColorSpan(Color.GRAY)
        when {
            line.contains("INFO[") || line.contains(" [Info]") -> {
                color = ForegroundColorSpan((0xFF86C166).toInt())
            }

            line.contains("ERROR[") || line.contains(" [Error]") -> {
                color = ForegroundColorSpan(Color.RED)
            }

            line.contains("WARN[") || line.contains(" [Warning]") -> {
                color = ForegroundColorSpan(Color.RED)
            }
        }
        return color
    }

    private fun reloadSession() {
        val span = SpannableString(logLines.joinToString("\n"))
        var offset = 0
        for (line in span.lines()) {
            val color = getColorForLine(line)
            span.setSpan(
                color, offset, offset + line.length, SPAN_EXCLUSIVE_EXCLUSIVE
            )
            offset += line.length + 1
        }
        binding.textview.text = span
        binding.textview.clearFocus()
        // 等 textview 完成最终 layout 再滚动到底部
        binding.textview.doOnLayout {
            binding.scroolview.scrollTo(0, binding.textview.height)
        }
    }

    override fun onOptionClicked(viewId: Int) {
        when (viewId) {
            R.id.action_clear_logcat -> {
                runOnDefaultDispatcher {
                    var error: Exception? = null
                    try {
                        Libbox.newStandaloneCommandClient().clearLogs()
                    } catch (e: Exception) {
                        error = e
                    }
                    try {
                        Runtime.getRuntime().exec("/system/bin/logcat -c")
                    } catch (e: Exception) {
                        if (error == null) error = e
                    }
                    onMainDispatcher {
                        logLines.clear()
                        reloadSession()
                        error?.let {
                            snackbar(it.readableMessage).show()
                        }
                    }
                }

            }

            R.id.action_send_logcat -> {
                val context = requireContext()
                val coreLog = logLines.joinToString("\n")
                runOnDefaultDispatcher {
                    try {
                        SendLog.sendLog(context, "NB4A", coreLog)
                    } catch (e: Exception) {
                        onMainDispatcher {
                            snackbar(e.readableMessage).show()
                        }
                    }
                }
            }

            R.id.action_refresh -> {
                connectLogClient()
            }
        }
    }

}
