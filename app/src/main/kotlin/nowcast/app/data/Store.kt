package nowcast.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import nowcast.engine.forecast.*
import nowcast.engine.radar.*
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val flow = MutableStateFlow(Settings.fromMap(sp.all))
    val settings: StateFlow<Settings> = flow
    val value: Settings get() = flow.value

    /** Synchronized: the screens and the cycle's coroutines both update, and a lost update is a lost setting. */
    @Synchronized fun update(f: (Settings) -> Settings) {
        val s = f(flow.value)
        sp.edit().clear().apply { s.toMap().forEach { (k, v) -> putString(k, v) } }.apply()
        flow.value = s
    }
}

/**
 * API keys per radar source, AES-GCM with a key that never leaves the Android Keystore.
 */
class Secrets(ctx: Context) {
    private val sp = ctx.getSharedPreferences("secrets", Context.MODE_PRIVATE)
    private val flow = MutableStateFlow(sp.all.keys.toSet())
    /** Sources that have a key; the keys themselves are only read on demand. */
    val sources: StateFlow<Set<String>> = flow

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    fun get(source: String): String? = sp.getString(source, null)?.let {
        runCatching {
            val raw = Base64.decode(it, Base64.NO_WRAP)
            val c = Cipher.getInstance(AES)
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
            String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
        }.getOrNull()
    }

    fun set(source: String, value: String?) {
        if (value.isNullOrBlank()) sp.edit().remove(source).apply()
        else {
            val c = Cipher.getInstance(AES).apply { init(Cipher.ENCRYPT_MODE, key()) }
            val ct = c.doFinal(value.trim().toByteArray(Charsets.UTF_8))
            sp.edit().putString(source, Base64.encodeToString(c.iv + ct, Base64.NO_WRAP)).apply()
        }
        flow.value = sp.all.keys.toSet()
    }

    private companion object { const val ALIAS = "api-keys"; const val AES = "AES/GCM/NoPadding" }
}

/** Bundled regions until the user first saves; then files/regions.json is the whole list. */
class RegionStore(private val ctx: Context) {
    private val file = File(ctx.filesDir, "regions.json")
    private val json = Json { prettyPrint = true; encodeDefaults = false }
    private val flow = MutableStateFlow(load())
    val regions: StateFlow<List<Region>> = flow

    fun bundled(): List<Region> = Regions.parse(ctx.assets.open("regions.json").bufferedReader().readText())
    private fun load(): List<Region> =
        runCatching { if (file.exists()) Regions.parse(file.readText()) else null }.getOrNull() ?: bundled()

    fun save(list: List<Region>) { file.writeText(json.encodeToString(list)); flow.value = list }
    fun reset() { file.delete(); flow.value = bundled() }
}

/** User models in files/models; "smaat.onnx" is the bundled asset. */
class ModelStore(private val ctx: Context) {
    private val dir = File(ctx.filesDir, "models").apply { mkdirs() }
    private val flow = MutableStateFlow(list())
    val models: StateFlow<List<String>> = flow

    private fun list() = listOf(BUNDLED) + (dir.listFiles()?.map { it.name }?.filter { it.endsWith(".onnx") }?.sorted() ?: emptyList())

    fun bytes(name: String): ByteArray {
        val f = File(dir, name)
        return if (name != BUNDLED && f.exists()) f.readBytes() else ctx.assets.open(BUNDLED).readBytes()
    }

    fun isKnown(name: String) = name == BUNDLED || File(dir, name).exists()

    /** Changes when a model of that name is imported again. */
    fun stamp(name: String) = if (name == BUNDLED) 0L else File(dir, name).lastModified()

    /** Copy, validate with a forward pass, keep only if it passes. Returns the stored name. */
    fun import(uri: Uri): String {
        val display = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "model.onnx"
        var name = display.replace(Regex("[^A-Za-z0-9._-]"), "_").let { if (it.endsWith(".onnx")) it else "$it.onnx" }
        if (name == BUNDLED) name = "user-$name"
        val tmp = File(dir, "$name.part")
        ctx.contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { i.copyTo(it) } }
        val err = Validate.model(tmp.readBytes())
        if (err != null) { tmp.delete(); throw IllegalArgumentException(err) }
        tmp.renameTo(File(dir, name))
        flow.value = list()
        return name
    }

    fun delete(name: String) { if (name != BUNDLED) File(dir, name).delete(); flow.value = list() }

    companion object { const val BUNDLED = "smaat.onnx" }
}
