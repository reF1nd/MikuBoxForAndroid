package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import libcore.Libcore
import moe.matsuri.nb4a.utils.listByLineOrComma
import java.io.File

object RuleSetAssets {

    data class References(
        val geoip: Set<String>,
        val geosite: Set<String>,
    )

    private val validCode = Regex("[a-z0-9_-]+")
    val localDirectory: File
        get() = SagerNet.application.filesDir.resolve("rule-set/local")
    val remoteDirectory: File
        get() = SagerNet.application.filesDir.resolve("rule-set/remote")

    fun code(reference: String): String {
        val code = reference.substringAfter(':', "").lowercase()
        require(validCode.matches(code)) {
            SagerNet.application.getString(R.string.rules_reference_invalid, reference)
        }
        return code
    }

    fun tag(reference: String): String = "${reference.substringBefore(':').lowercase()}-${code(reference)}"

    fun localFile(reference: String): File = localDirectory.resolve("${tag(reference)}.srs")

    fun remoteFile(reference: String): File = remoteDirectory.resolve("${tag(reference)}.srs")

    fun references(): References {
        val geoip = linkedSetOf<String>()
        val geosite = linkedSetOf<String>()
        for (rule in SagerDatabase.rulesDao.enabledRules()) {
            rule.ip.listByLineOrComma().forEach {
                if (it.startsWith("geoip:", ignoreCase = true) && !it.equals("geoip:private", true)) {
                    geoip += code(it)
                }
            }
            rule.domains.listByLineOrComma().forEach {
                if (it.startsWith("geosite:", ignoreCase = true)) {
                    geosite += code(it)
                }
            }
        }
        return References(geoip, geosite)
    }

    suspend fun prepare() = withContext(Dispatchers.IO) {
        if (DataStore.rulesResourceMode != 0) return@withContext
        val references = references()
        convertIfNeeded("geoip.db", references.geoip)
        convertIfNeeded("geosite.db", references.geosite)
    }

    fun convertDatabase(fileName: String, source: File) {
        val references = references()
        when (fileName) {
            "geoip.db" -> convert("geoip", source, references.geoip)
            "geosite.db" -> convert("geosite", source, references.geosite)
        }
    }

    fun generatedCount(fileName: String): Int {
        val prefix = fileName.substringBefore('.') + "-"
        return localDirectory.listFiles()?.count { it.isFile && it.name.startsWith(prefix) && it.extension == "srs" } ?: 0
    }

    private fun convertIfNeeded(fileName: String, codes: Set<String>) {
        if (codes.isEmpty()) return
        val database = SagerNet.application.externalAssets.resolve(fileName)
        if (!database.isFile) {
            throw IllegalStateException(
                SagerNet.application.getString(R.string.rules_local_asset_missing, fileName),
            )
        }
        val prefix = fileName.substringBefore('.')
        val upToDate = codes.all { code ->
            val output = localDirectory.resolve("$prefix-$code.srs")
            output.isFile && output.lastModified() >= database.lastModified()
        }
        if (!upToDate) convert(prefix, database, codes)
    }

    private fun convert(prefix: String, source: File, codes: Set<String>) {
        if (codes.isEmpty()) return
        localDirectory.mkdirs()
        val codeList = codes.sorted().joinToString("\n")
        when (prefix) {
            "geoip" -> Libcore.convertGeoIPRuleSets(source.absolutePath, localDirectory.absolutePath, codeList)
            "geosite" -> Libcore.convertGeositeRuleSets(source.absolutePath, localDirectory.absolutePath, codeList)
        }
    }
}
