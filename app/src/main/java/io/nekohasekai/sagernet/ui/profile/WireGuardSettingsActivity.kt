package io.nekohasekai.sagernet.ui.profile

import android.os.Bundle
import android.text.InputType
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.fmt.wireguard.formatWireGuardPeers
import io.nekohasekai.sagernet.fmt.wireguard.parseWireGuardPeers
import moe.matsuri.nb4a.proxy.PreferenceBinding
import moe.matsuri.nb4a.proxy.PreferenceBindingManager
import moe.matsuri.nb4a.proxy.Type
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import io.nekohasekai.sagernet.database.DataStore

class WireGuardSettingsActivity : ProfileSettingsActivity<WireGuardBean>() {

    companion object {
        private const val ADDITIONAL_PEERS = "wireGuardAdditionalPeers"
    }

    override fun createEntity() = WireGuardBean()

    private val pbm = PreferenceBindingManager()
    private val name = pbm.add(PreferenceBinding(Type.Text, "name"))
    private val serverAddress = pbm.add(PreferenceBinding(Type.Text, "serverAddress"))
    private val serverPort = pbm.add(PreferenceBinding(Type.TextToInt, "serverPort"))
    private val localAddress = pbm.add(PreferenceBinding(Type.Text, "localAddress"))
    private val privateKey = pbm.add(PreferenceBinding(Type.Text, "privateKey"))
    private val peerPublicKey = pbm.add(PreferenceBinding(Type.Text, "peerPublicKey"))
    private val peerPreSharedKey = pbm.add(PreferenceBinding(Type.Text, "peerPreSharedKey"))
    private val peerAllowedIPs = pbm.add(PreferenceBinding(Type.Text, "peerAllowedIPs"))
    private val peerPersistentKeepaliveInterval =
        pbm.add(PreferenceBinding(Type.TextToInt, "peerPersistentKeepaliveInterval"))
    private val mtu = pbm.add(PreferenceBinding(Type.TextToInt, "mtu"))
    private val reserved = pbm.add(PreferenceBinding(Type.Text, "reserved"))
    private val listenPort = pbm.add(PreferenceBinding(Type.TextToInt, "listenPort"))
    private val dnsServer = pbm.add(PreferenceBinding(Type.Text, "dnsServer"))

    override fun WireGuardBean.init() {
        pbm.writeToCacheAll(this)
        DataStore.profileCacheStore.putString(
            ADDITIONAL_PEERS,
            formatWireGuardPeers(peers.drop(1)),
        )
    }

    override fun WireGuardBean.serialize() {
        pbm.fromCacheAll(this)
        val additionalPeers = DataStore.profileCacheStore.getString(ADDITIONAL_PEERS).orEmpty()
        peers = buildList {
            add(primaryPeer())
            if (additionalPeers.isNotBlank()) addAll(parseWireGuardPeers(additionalPeers))
        }
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.wireguard_preferences)

        val styleValue = DataStore.categoryStyle
        preferenceScreen?.let { screen ->
            updateAllCategoryStyles(styleValue, screen)
        }
        
        pbm.setPreferenceFragment(this)

        (serverPort.preference as EditTextPreference)
            .setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        (privateKey.preference as EditTextPreference).summaryProvider = PasswordSummaryProvider
        (mtu.preference as EditTextPreference).setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        (listenPort.preference as EditTextPreference)
            .setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        (peerPersistentKeepaliveInterval.preference as EditTextPreference)
            .setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        findPreference<EditTextPreference>(ADDITIONAL_PEERS)!!.setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            editText.minLines = 8
            editText.typeface = android.graphics.Typeface.MONOSPACE
            editText.setSelection(editText.text.length)
        }
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
