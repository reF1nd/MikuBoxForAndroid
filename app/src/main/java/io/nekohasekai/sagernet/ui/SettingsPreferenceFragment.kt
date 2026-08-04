package io.nekohasekai.sagernet.ui

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.core.app.ActivityCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.RuleSetDownloadMode
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.ktx.FixedLinearLayoutManager
import io.nekohasekai.sagernet.ktx.needReload
import io.nekohasekai.sagernet.ktx.needRestart
import io.nekohasekai.sagernet.ktx.remove
import moe.matsuri.nb4a.ui.EditConfigPreference
import moe.matsuri.nb4a.ui.LongClickListPreference
import moe.matsuri.nb4a.ui.MTUPreference
import com.takisoft.preferencex.PreferenceFragmentCompat
import com.takisoft.preferencex.SimpleMenuPreference
import java.io.File
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import io.nekohasekai.sagernet.utils.showBlur
import io.nekohasekai.sagernet.widget.OutboundPreference

class SettingsPreferenceFragment : PreferenceFragmentCompat() {

    private lateinit var isProxyApps: SwitchPreference
    private lateinit var globalCustomConfig: EditConfigPreference
    private lateinit var rulesRemoteDownloadMode: OutboundPreference

    private val selectRuleSetDownloadProfile = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK || !::rulesRemoteDownloadMode.isInitialized) return@registerForActivityResult
        val profileId = result.data?.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0L) ?: 0L
        if (profileId <= 0L) return@registerForActivityResult
        DataStore.rulesRemoteDownloadProxy = profileId
        rulesRemoteDownloadMode.value = RuleSetDownloadMode.SPECIFIC
        rulesRemoteDownloadMode.postUpdate()
        needReload()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        listView.layoutManager = FixedLinearLayoutManager(listView)
    }

    private val reloadListener = Preference.OnPreferenceChangeListener { _, _ ->
        needReload()
        true
    }

    override fun onCreatePreferencesFix(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.preferenceDataStore = DataStore.configurationStore
        DataStore.initGlobal()
        addPreferencesFromResource(R.xml.global_preferences)

        val styleValue = DataStore.categoryStyle
        preferenceScreen?.let { screen ->
            updateAllCategoryStyles(styleValue, screen)
        }
        
        val mixedPort = findPreference<EditTextPreference>(Key.MIXED_PORT)!!
        mixedPort.setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        if (Build.VERSION.SDK_INT < 28) {
            findPreference<Preference>(Key.METERED_NETWORK)?.remove()
        }
        isProxyApps = findPreference(Key.PROXY_APPS)!!
        isProxyApps.setOnPreferenceChangeListener { _, newValue ->
            startActivity(Intent(activity, AppManagerActivity::class.java))
            if (newValue as Boolean) DataStore.dirty = true
            newValue
        }
        val profileTrafficStatistics = findPreference<SwitchPreference>(Key.PROFILE_TRAFFIC_STATISTICS)!!
        val speedInterval = findPreference<SimpleMenuPreference>(Key.SPEED_INTERVAL)!!
        profileTrafficStatistics.isEnabled = speedInterval.value != "0"
        speedInterval.setOnPreferenceChangeListener { _, newValue ->
            profileTrafficStatistics.isEnabled = newValue.toString() != "0"
            needReload()
            true
        }
        val enableClashAPI = findPreference<SwitchPreference>(Key.ENABLE_CLASH_API)!!
        enableClashAPI.setOnPreferenceChangeListener { _, _ ->
            needReload()
            true
        }
        val rulesProvider = findPreference<SimpleMenuPreference>(Key.RULES_PROVIDER)!!
        val rulesResourceMode = findPreference<SimpleMenuPreference>(Key.RULES_RESOURCE_MODE)!!
        val rulesGeositeUrl = findPreference<EditTextPreference>("rules_geosite_url")!!
        val rulesGeoipUrl = findPreference<EditTextPreference>("rules_geoip_url")!!
        val rulesGeositeRemoteUrl = findPreference<EditTextPreference>(Key.RULES_GEOSITE_REMOTE_URL)!!
        val rulesGeoipRemoteUrl = findPreference<EditTextPreference>(Key.RULES_GEOIP_REMOTE_URL)!!
        rulesRemoteDownloadMode = findPreference(Key.RULES_REMOTE_DOWNLOAD_MODE)!!
        rulesRemoteDownloadMode.setEntries(R.array.rules_remote_download_modes)
        rulesRemoteDownloadMode.setEntryValues(R.array.rules_remote_download_mode_values)
        rulesRemoteDownloadMode.summaryProvider = Preference.SummaryProvider<OutboundPreference> {
            if (it.value == RuleSetDownloadMode.SPECIFIC) {
                ProfileManager.getProfile(DataStore.rulesRemoteDownloadProxy)?.displayName()
                    ?: getString(R.string.unavailable)
            } else {
                it.entry
            }
        }
        rulesRemoteDownloadMode.setOnPreferenceChangeListener { _, newValue ->
            if (newValue == RuleSetDownloadMode.SPECIFIC) {
                val intent = Intent(requireContext(), ProfileSelectActivity::class.java)
                ProfileManager.getProfile(DataStore.rulesRemoteDownloadProxy)?.let {
                    intent.putExtra(ProfileSelectActivity.EXTRA_SELECTED, it)
                }
                selectRuleSetDownloadProfile.launch(intent)
                false
            } else {
                needReload()
                true
            }
        }
        fun updateRuleSetPreferences(mode: Int = DataStore.rulesResourceMode, provider: Int = DataStore.rulesProvider) {
            val legacyMode = mode == 0
            rulesProvider.isVisible = legacyMode
            rulesGeositeUrl.isVisible = legacyMode && provider == 5
            rulesGeoipUrl.isVisible = legacyMode && provider == 5
            rulesGeositeRemoteUrl.isVisible = !legacyMode
            rulesGeoipRemoteUrl.isVisible = !legacyMode
            rulesRemoteDownloadMode.isVisible = !legacyMode
        }
        updateRuleSetPreferences()
        rulesResourceMode.setOnPreferenceChangeListener { _, newValue ->
            updateRuleSetPreferences(mode = (newValue as String).toInt())
            needReload()
            true
        }
        rulesProvider.setOnPreferenceChangeListener { _, newValue ->
            val provider = (newValue as String).toInt()
            updateRuleSetPreferences(provider = provider)
            true
        }
        rulesGeositeRemoteUrl.onPreferenceChangeListener = reloadListener
        rulesGeoipRemoteUrl.onPreferenceChangeListener = reloadListener
        val serviceMode = findPreference<Preference>(Key.SERVICE_MODE)!!
        serviceMode.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, _ ->
            if (DataStore.serviceState.started) SagerNet.stopService()
            true
        }
        globalCustomConfig = findPreference(Key.GLOBAL_CUSTOM_CONFIG)!!
        globalCustomConfig.useConfigStore(Key.GLOBAL_CUSTOM_CONFIG)
        findPreference<LongClickListPreference>(Key.LOG_LEVEL)!!.let { logLevel ->
            logLevel.dialogLayoutResource = R.layout.layout_loglevel_help
            logLevel.setOnPreferenceChangeListener { _, _ ->
                needRestart()
                true
            }
            logLevel.setOnLongClickListener {
                context?.let { ctx ->
                    val view = EditText(ctx).apply {
                        inputType = EditorInfo.TYPE_CLASS_NUMBER
                        val size = DataStore.logBufSize.takeIf { it > 0 } ?: 50
                        setText(size.toString())
                    }
                    MaterialAlertDialogBuilder(requireContext()).setTitle("Log buffer size (kb)")
                        .setView(view)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            DataStore.logBufSize = view.text.toString().toIntOrNull() ?: 50
                            if (DataStore.logBufSize <= 0) DataStore.logBufSize = 50
                            needRestart()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .showBlur()
                }
                true
            }
        }
        val clearCache = findPreference<Preference>("clear_cache")!!
        clearCache.setOnPreferenceClickListener {
            MaterialAlertDialogBuilder(requireContext()).apply {
                setTitle(R.string.clear_cache)
                setMessage(R.string.clear_cache_confirm)
                setPositiveButton(android.R.string.ok) { _, _ ->
                    clearAppCache()
                }
                setNegativeButton(android.R.string.cancel, null)
            }.showBlur()
            true
        }
        mixedPort.onPreferenceChangeListener = reloadListener
        findPreference<SwitchPreference>(Key.APPEND_HTTP_PROXY)!!.onPreferenceChangeListener = reloadListener
        findPreference<SwitchPreference>(Key.SHOW_DIRECT_SPEED)!!.onPreferenceChangeListener = reloadListener
        findPreference<Preference>(Key.TRAFFIC_SNIFFING)!!.onPreferenceChangeListener = reloadListener
        findPreference<SwitchPreference>(Key.BYPASS_LAN)!!.onPreferenceChangeListener = reloadListener
        findPreference<SwitchPreference>(Key.BYPASS_LAN_IN_CORE)!!.onPreferenceChangeListener = reloadListener
        findPreference<MTUPreference>(Key.MTU)!!.onPreferenceChangeListener = reloadListener
        findPreference<SwitchPreference>(Key.ENABLE_FAKEDNS)!!.onPreferenceChangeListener = reloadListener
        findPreference<EditTextPreference>(Key.REMOTE_DNS)!!.onPreferenceChangeListener = reloadListener
        findPreference<EditTextPreference>(Key.DIRECT_DNS)!!.onPreferenceChangeListener = reloadListener
        findPreference<SwitchPreference>(Key.ENABLE_DNS_ROUTING)!!.onPreferenceChangeListener = reloadListener
        findPreference<Preference>(Key.IPV6_MODE)!!.onPreferenceChangeListener = reloadListener
        findPreference<Preference>(Key.ALLOW_ACCESS)!!.onPreferenceChangeListener = reloadListener
        findPreference<Preference>(Key.RESOLVE_DESTINATION)!!.onPreferenceChangeListener = reloadListener
        findPreference<SimpleMenuPreference>(Key.TUN_IMPLEMENTATION)!!.onPreferenceChangeListener = reloadListener
        findPreference<SwitchPreference>(Key.ACQUIRE_WAKE_LOCK)!!.onPreferenceChangeListener = reloadListener
        globalCustomConfig.onPreferenceChangeListener = reloadListener
    }

    override fun onResume() {
        super.onResume()
        if (::isProxyApps.isInitialized) {
            isProxyApps.isChecked = DataStore.proxyApps
        }
        if (::globalCustomConfig.isInitialized) {
            globalCustomConfig.notifyChanged()
        }
        if (::rulesRemoteDownloadMode.isInitialized) {
            rulesRemoteDownloadMode.postUpdate()
        }
    }
    
    private fun clearAppCache() {
        try {
            val cacheDir = SagerNet.application.cacheDir
            clearDirFiles(cacheDir)
            val parentDir = cacheDir.parentFile
            val relativeCache = File(parentDir, "cache")
            if (relativeCache.exists() && relativeCache.isDirectory) {
                clearDirFiles(relativeCache)
            }
            Toast.makeText(requireContext(), R.string.clear_cache_success, Toast.LENGTH_SHORT).show()
            Handler(Looper.getMainLooper()).postDelayed({
                needReload()
            }, 500)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), getString(R.string.clear_cache_failed, e.message), Toast.LENGTH_SHORT).show()
            e.printStackTrace()
        }
    }

    private fun clearDirFiles(dir: File): Boolean {
        if (dir.isDirectory) {
            val children = dir.list() ?: return true
            for (child in children) {
                val childFile = File(dir, child)
                if (childFile.isDirectory) {
                    clearDirFiles(childFile)
                } else {
                    childFile.delete()
                }
            }
            return true
        }
        return false
    }

    private fun updateAllCategoryStyles(styleValue: String?, group: PreferenceGroup) {
        val newLayout = when (styleValue) {
            "style1" -> R.layout.uwu_preference_category_1
            "style2" -> R.layout.uwu_preference_category_2
            "style3" -> R.layout.uwu_preference_category_3
            "style4" -> R.layout.uwu_preference_category_4
            "style5" -> R.layout.uwu_preference_category_5
            "style6" -> R.layout.uwu_preference_category_6
            "style7" -> R.layout.uwu_preference_category_7
            "style8" -> R.layout.uwu_preference_category_8
            "style9" -> R.layout.uwu_preference_category_9
            "style10" -> R.layout.uwu_preference_category_10
            else -> R.layout.uwu_preference_category_1
        }

        for (i in 0 until group.preferenceCount) {
            val preference = group.getPreference(i)
            if (preference is PreferenceCategory) {
                preference.layoutResource = newLayout
            }
            if (preference is PreferenceGroup) {
                updateAllCategoryStyles(styleValue, preference)
            }
        }
    }
    
}
