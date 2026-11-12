package com.example.crossreel.desktop

import com.example.crossreel.ColorPreset
import com.example.crossreel.OutputOrientation
import com.example.crossreel.VfxPreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.Properties

data class DesktopAudioSelection(
    val trackId: String,
    val file: File,
    val startOffsetMs: Long,
    val bpm: Int,
    val isRemote: Boolean,
    val reason: String
)

object DesktopAudioManager {

    private const val CATALOG_URL = "https://raw.githubusercontent.com/NewtonH725/orangebeat-audio-db/main/catalog.json"
    private const val MIN_VALID_MP3_BYTES = 100 * 1024L

    private data class TrackCandidate(
        val id: String,
        val genre: String,
        val bpm: Int,
        val durationMs: Long,
        val subStyle: String,
        val energy: String,
        val dropOffsetsMs: List<Long>,
        val resourcePath: String? = null,
        val remoteUrl: String? = null
    )

    private val localCatalog: List<TrackCandidate> = listOf(
        TrackCandidate("rock_01", "ROCK", 80, 60_000L, "CLASSIC_GROOVY", "MEDIUM", listOf(0L), "raw/rock_01.mp3"),
        TrackCandidate("rock_02", "ROCK", 150, 60_000L, "FAST_DRIVING", "HIGH", listOf(0L), "raw/rock_02.mp3"),
        TrackCandidate("rock_03", "ROCK", 92, 60_000L, "CLASSIC_GROOVY", "MEDIUM", listOf(0L), "raw/rock_03.mp3"),
        TrackCandidate("rock_04", "ROCK", 133, 60_000L, "EXTREME_PEAK", "HIGH", listOf(0L), "raw/rock_04.mp3"),

        TrackCandidate("metal_01", "METAL", 86, 60_000L, "DARK_INDUSTRIAL", "HIGH", listOf(0L), "raw/metal_01.mp3"),
        TrackCandidate("metal_02", "METAL", 86, 60_000L, "DARK_INDUSTRIAL", "HIGH", listOf(0L), "raw/metal_02.mp3"),
        TrackCandidate("metal_03", "METAL", 150, 60_000L, "EXTREME_PEAK", "EXTREME", listOf(0L), "raw/metal_03.mp3"),
        TrackCandidate("metal_04", "METAL", 86, 60_000L, "FAST_DRIVING", "HIGH", listOf(0L), "raw/metal_04.mp3"),

        TrackCandidate("techno_01", "TECHNO", 133, 60_000L, "FAST_DRIVING", "HIGH", listOf(0L), "raw/techno_01.mp3"),
        TrackCandidate("techno_02", "TECHNO", 100, 60_000L, "CLASSIC_GROOVY", "MEDIUM", listOf(0L), "raw/techno_02.mp3"),
        TrackCandidate("techno_03", "TECHNO", 133, 60_000L, "EXTREME_PEAK", "EXTREME", listOf(0L), "raw/techno_03.mp3"),
        TrackCandidate("techno_04", "TECHNO", 120, 60_000L, "DARK_INDUSTRIAL", "HIGH", listOf(0L), "raw/techno_04.mp3"),

        TrackCandidate("dubstep_01", "DUBSTEP", 133, 60_000L, "FAST_DRIVING", "HIGH", listOf(0L), "raw/dubstep_01.mp3"),
        TrackCandidate("dubstep_02", "DUBSTEP", 109, 60_000L, "CLASSIC_GROOVY", "MEDIUM", listOf(0L), "raw/dubstep_02.mp3"),
        TrackCandidate("dubstep_03", "DUBSTEP", 133, 60_000L, "EXTREME_PEAK", "EXTREME", listOf(0L), "raw/dubstep_03.mp3"),
        TrackCandidate("dubstep_04", "DUBSTEP", 171, 60_000L, "EXTREME_PEAK", "EXTREME", listOf(0L), "raw/dubstep_04.mp3")
    )

    suspend fun selectAndPrepareTrack(
        genre: String,
        durationSeconds: Int,
        colorPreset: ColorPreset,
        vfxPreset: VfxPreset,
        orientation: OutputOrientation,
        avgMotionScore: Float
    ): DesktopAudioSelection = withContext(Dispatchers.IO) {
        val cleanGenre = genre.trim().uppercase(Locale.ROOT)
        val props = loadHistory()

        val remoteTracks = fetchRemoteTracks(cleanGenre)
        val localTracks = localCatalog.filter { it.genre == cleanGenre }.ifEmpty {
            localCatalog.filter { it.genre == "TECHNO" }
        }

        val combined = (localTracks + remoteTracks).distinctBy { it.id }
        val lastTrack = props.getProperty("last_track_$cleanGenre", "")

        val nonConsecutive = if (combined.size > 1 && lastTrack.isNotBlank()) {
            combined.filter { it.id != lastTrack }
        } else {
            combined
        }

        val desiredEnergy = when {
            avgMotionScore >= 0.25f || vfxPreset == VfxPreset.FLASH || vfxPreset == VfxPreset.TV_GLITCH -> "EXTREME"
            durationSeconds <= 20 -> "HIGH"
            colorPreset == ColorPreset.ILFORD_PAN_F || colorPreset == ColorPreset.DESATURATED_COLD -> "MEDIUM"
            else -> "HIGH"
        }

        val scored = nonConsecutive.sortedByDescending { candidate ->
            val usage = props.getProperty("usage_${candidate.id}", "0").toIntOrNull() ?: 0
            var score = 100.0 - (usage * 18.0)
            if (candidate.energy.equals(desiredEnergy, ignoreCase = true)) score += 25.0
            if (orientation == OutputOrientation.PORTRAIT && candidate.bpm >= 120) score += 10.0
            if (candidate.remoteUrl != null) score += 5.0
            score
        }

        for (candidate in scored) {
            val file = resolveTrackFile(candidate)
            if (file != null && file.exists() && file.length() > 1024L) {
                val offset = candidate.dropOffsetsMs.firstOrNull() ?: 0L
                val prevUsage = props.getProperty("usage_${candidate.id}", "0").toIntOrNull() ?: 0
                props.setProperty("usage_${candidate.id}", (prevUsage + 1).toString())
                props.setProperty("last_track_$cleanGenre", candidate.id)
                saveHistory(props)

                return@withContext DesktopAudioSelection(
                    trackId = candidate.id,
                    file = file,
                    startOffsetMs = offset,
                    bpm = candidate.bpm,
                    isRemote = candidate.remoteUrl != null,
                    reason = "Energy=$desiredEnergy, BPM=${candidate.bpm}"
                )
            }
        }

        // Fallback garantito su prima traccia locale
        val fallback = localCatalog.first()
        val fallbackFile = extractBundledTrack(fallback)!!
        DesktopAudioSelection(
            trackId = fallback.id,
            file = fallbackFile,
            startOffsetMs = 0L,
            bpm = fallback.bpm,
            isRemote = false,
            reason = "Fallback locale"
        )
    }

    private fun resolveTrackFile(candidate: TrackCandidate): File? {
        if (candidate.remoteUrl != null) {
            val cached = File(DesktopPaths.getAudioCacheDir(), "${candidate.id}.mp3")
            if (cached.exists() && cached.length() >= MIN_VALID_MP3_BYTES) {
                return cached
            }
            if (downloadMp3(candidate.remoteUrl, cached)) {
                return cached
            }
        }
        if (candidate.resourcePath != null) {
            return extractBundledTrack(candidate)
        }
        return null
    }

    private fun extractBundledTrack(candidate: TrackCandidate): File? {
        val resPath = candidate.resourcePath ?: return null
        val target = File(DesktopPaths.getBundledAudioDir(), "${candidate.id}.mp3")
        if (target.exists() && target.length() > 10_000L) return target

        val stream = DesktopAudioManager::class.java.classLoader.getResourceAsStream(resPath) ?: return null
        stream.use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output)
            }
        }
        return target
    }

    private fun fetchRemoteTracks(genre: String): List<TrackCandidate> {
        return try {
            val catalogFile = File(DesktopPaths.getAudioCacheDir(), "catalog.json")
            val jsonText = fetchText(CATALOG_URL)?.also {
                catalogFile.writeText(it)
            } ?: if (catalogFile.exists()) catalogFile.readText() else return emptyList()

            val root = JSONObject(jsonText)
            val tracksArray = root.optJSONArray("tracks") ?: return emptyList()
            val list = mutableListOf<TrackCandidate>()
            for (i in 0 until tracksArray.length()) {
                val obj = tracksArray.optJSONObject(i) ?: continue
                if (!obj.optString("genre", "").equals(genre, ignoreCase = true)) continue
                val id = obj.optString("id", "").ifBlank { continue }
                val url = obj.optString("url", "").ifBlank { continue }
                val offsets = mutableListOf<Long>()
                val arr = obj.optJSONArray("dropOffsetsMs")
                if (arr != null) {
                    for (j in 0 until arr.length()) offsets.add(arr.optLong(j, 0L))
                }
                if (offsets.isEmpty()) offsets.add(0L)

                list.add(
                    TrackCandidate(
                        id = id,
                        genre = genre,
                        bpm = obj.optInt("bpm", 128),
                        durationMs = obj.optLong("durationMs", 60_000L),
                        subStyle = obj.optString("subStyle", "FAST_DRIVING"),
                        energy = obj.optString("energy", "HIGH"),
                        dropOffsetsMs = offsets,
                        remoteUrl = url
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun fetchText(urlStr: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 6000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "OrangeBeat-Desktop")
            }
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else null
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun downloadMp3(urlStr: String, target: File): Boolean {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 30000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "OrangeBeat-Desktop")
            }
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                conn.inputStream.use { input ->
                    FileOutputStream(tmp).use { output -> input.copyTo(output) }
                }
                if (tmp.length() >= MIN_VALID_MP3_BYTES) {
                    if (target.exists()) target.delete()
                    return tmp.renameTo(target)
                }
            }
            false
        } catch (_: Exception) {
            false
        } finally {
            conn?.disconnect()
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun loadHistory(): Properties {
        val props = Properties()
        val file = File(DesktopPaths.getAppDataDir(), "audio_history.properties")
        if (file.exists()) {
            runCatching { FileInputStream(file).use { props.load(it) } }
        }
        return props
    }

    private fun saveHistory(props: Properties) {
        val file = File(DesktopPaths.getAppDataDir(), "audio_history.properties")
        runCatching { FileOutputStream(file).use { props.store(it, "OrangeBeat Desktop Audio History") } }
    }
}
