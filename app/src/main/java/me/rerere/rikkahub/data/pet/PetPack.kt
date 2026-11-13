package me.rerere.rikkahub.data.pet

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream

/**
 * DSH-TUI 兼容的像素宠物包。
 *
 * 格式：ZIP 根目录（或单层子目录）下 pack.json + 若干横向序列帧 PNG。
 * pack.json 的 animations 每项给出 sheet / frameW / frameH / cols / frames。
 *
 * 这里只实现**格式兼容**；内置「鲸娘」来自 DSH Go 的默认宠物包
 * （`app/assets/pets/whale-girl/`，作者/来源标注见包内 pack.json 与 SOURCE.md）。
 */
@Serializable
data class PetPackDto(
    val schema: Int = 1,
    val id: String,
    val name: String,
    val author: String = "",
    val license: String = "",
    val source: String = "",
    val pixelArt: Boolean = true,
    val scale: Float = 1f,
    val canvas: PetCanvas = PetCanvas(),
    val anchor: PetAnchor = PetAnchor(),
    val contentBox: PetBox? = null,
    val animations: Map<String, PetAnimation> = emptyMap(),
)

@Serializable data class PetCanvas(val w: Int = 256, val h: Int = 256)
@Serializable data class PetAnchor(val x: Float = 128f, val baseline: Float = 240f)
@Serializable data class PetBox(val x: Int = 0, val y: Int = 0, val w: Int = 256, val h: Int = 256)

@Serializable
data class PetAnimation(
    val sheet: String,
    val frameW: Int,
    val frameH: Int,
    val cols: Int = 1,
    val loop: Boolean = true,
    val ms: Long = 1000,
    val frames: List<PetFrame> = emptyList(),
)

@Serializable data class PetFrame(val i: Int, val ms: Long? = null)

/** 运行期宠物包；[assetDir] 与 [dir] 二选一。 */
data class PetPack(
    val id: String,
    val name: String,
    val author: String,
    val license: String,
    val canvasW: Int,
    val canvasH: Int,
    val animations: Map<String, PetAnimation>,
    val assetDir: String? = null,
    val dir: File? = null,
    val builtin: Boolean = false,
) {
    fun animation(name: String): PetAnimation? =
        animations[name] ?: animations["idle"] ?: animations.values.firstOrNull()

    fun hasAnimation(name: String): Boolean = animations.containsKey(name)
}

object PetSelection {
    /** 设置页换了宠物后，悬浮窗无需重建 Activity 也能重读。 */
    val revision = MutableStateFlow(0)
    fun bump() { revision.value += 1 }
}

object PetPrefs {
    private const val FILE = "pet_prefs"
    private const val KEY = "selected_pet_id"
    const val DEFAULT_ID = "whale-girl"

    fun selected(context: Context): String =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, DEFAULT_ID)
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_ID

    fun select(context: Context, id: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, id).apply()
        PetSelection.bump()
    }
}

class PetRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val bitmaps = ConcurrentHashMap<String, ImageBitmap>()

    fun selected(): PetPack? {
        val id = PetPrefs.selected(context)
        return loadPacks().firstOrNull { it.id == id } ?: builtinPack()
    }

    fun loadPacks(): List<PetPack> = buildList {
        builtinPack()?.let(::add)
        val root = File(context.filesDir, "pets")
        root.listFiles()?.sortedBy { it.name }?.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            if (dir.name.endsWith(".bak")) return@forEach
            parseDir(dir)?.takeIf { it.id != "whale-girl" || !it.builtin }?.let(::add)
        }
    }.distinctBy { it.id }

    fun bitmap(pack: PetPack, sheet: String): ImageBitmap? {
        val key = "${pack.id}:$sheet"
        bitmaps[key]?.let { return it }
        val image = runCatching {
            val stream = if (pack.assetDir != null) {
                context.assets.open("${pack.assetDir}/$sheet")
            } else {
                File(pack.dir, sheet).inputStream()
            }
            stream.use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
        }.getOrNull()
        if (image != null) bitmaps[key] = image
        return image
    }

    suspend fun importZip(uri: Uri): PetPack = withContext(Dispatchers.IO) {
        val temp = File(context.cacheDir, "pet-import-${System.currentTimeMillis()}")
        temp.deleteRecursively()
        temp.mkdirs()
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val name = entry.name.replace('\\', '/')
                        if (name.startsWith("/") || name.split('/').any { it == ".." }) {
                            throw IllegalArgumentException("宠物包包含非法路径：$name")
                        }
                        if (!entry.isDirectory) {
                            val out = File(temp, name)
                            val canonical = out.canonicalFile
                            if (!canonical.path.startsWith(temp.canonicalPath + File.separator)) {
                                throw IllegalArgumentException("宠物包路径越界：$name")
                            }
                            out.parentFile?.mkdirs()
                            out.outputStream().use { zip.copyTo(it) }
                        }
                        zip.closeEntry()
                    }
                }
            } ?: throw IllegalArgumentException("无法读取 ZIP")

            val packFile = temp.walkTopDown().firstOrNull { it.isFile && it.name == "pack.json" }
                ?: throw IllegalArgumentException("ZIP 里没有 pack.json")
            val base = packFile.parentFile ?: temp
            val dto = json.decodeFromString(PetPackDto.serializer(), packFile.readText())
            if (dto.animations.isEmpty()) throw IllegalArgumentException("pack.json 没有 animations")
            val safeId = dto.id.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,48}")) }
                ?: ("pet-" + Integer.toHexString(dto.name.hashCode()))
            val finalId = if (safeId == PetPrefs.DEFAULT_ID) "$safeId-custom" else safeId
            val target = File(context.filesDir, "pets/$finalId")
            val backup = File(context.filesDir, "pets/$finalId.bak")
            if (backup.exists()) backup.deleteRecursively()
            if (target.exists() && !target.renameTo(backup)) {
                throw IllegalStateException("无法备份旧宠物包")
            }
            try {
                base.copyRecursively(target, overwrite = true)
                val parsed = parseDir(target) ?: throw IllegalArgumentException("宠物包解析失败")
                PetPrefs.select(context, parsed.id)
                parsed
            } catch (t: Throwable) {
                target.deleteRecursively()
                if (backup.exists()) backup.renameTo(target)
                throw t
            } finally {
                backup.deleteRecursively()
            }
        } finally {
            temp.deleteRecursively()
        }
    }

    fun deleteImported(pack: PetPack) {
        if (pack.builtin) return
        val root = File(context.filesDir, "pets").canonicalFile
        val dir = pack.dir?.canonicalFile ?: return
        if (!dir.path.startsWith(root.path + File.separator)) return
        dir.deleteRecursively()
        if (PetPrefs.selected(context) == pack.id) PetPrefs.select(context, PetPrefs.DEFAULT_ID)
    }

    private fun builtinPack(): PetPack? = runCatching {
        val text = context.assets.open("pets/whale-girl/pack.json").bufferedReader().use { it.readText() }
        val dto = json.decodeFromString(PetPackDto.serializer(), text)
        PetPack(
            id = dto.id,
            name = dto.name,
            author = dto.author,
            license = dto.license,
            canvasW = dto.canvas.w,
            canvasH = dto.canvas.h,
            animations = normalizedAnimations(dto.animations),
            assetDir = "pets/whale-girl",
            builtin = true,
        )
    }.getOrNull()

    private fun parseDir(dir: File): PetPack? = runCatching {
        val packFile = File(dir, "pack.json")
        if (!packFile.isFile) return null
        val dto = json.decodeFromString(PetPackDto.serializer(), packFile.readText())
        PetPack(
            // 目录名才是本机唯一 ID：导入的 DSH 包可能仍写着 "whale-girl"，
            // 若沿用包内 id 会与内置鲸娘撞车，distinctBy 直接把导入包丢掉。
            id = dir.name,
            name = dto.name,
            author = dto.author,
            license = dto.license,
            canvasW = dto.canvas.w,
            canvasH = dto.canvas.h,
            animations = normalizedAnimations(dto.animations),
            dir = dir,
            builtin = false,
        )
    }.getOrNull()

    private fun normalizedAnimations(source: Map<String, PetAnimation>): Map<String, PetAnimation> =
        source.mapValues { (_, anim) ->
            if (anim.frames.isNotEmpty()) anim
            else anim.copy(frames = (0 until anim.cols.coerceAtLeast(1)).map { PetFrame(it) })
        }
}
