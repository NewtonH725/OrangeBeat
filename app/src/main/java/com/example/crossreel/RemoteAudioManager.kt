package com.example.crossreel

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Gestore Cloud-First per il recupero, download asincrono, caching su disco
 * e rotazione anti-ripetizione delle tracce audio da GitHub Raw, con fallback
 * automatico e trasparente sul pool offline locale res/raw/.
 */
object RemoteAudioManager {

    private const val TAG = "RemoteAudioManager"

    // URL del catalogo JSON servito via GitHub Raw
    const val CATALOG_URL = "https://raw.githubusercontent.com/NewtonH725/orangebeat-audio-db/main/catalog.json"

    private const val PREFS_NAME = "orangebeat_audio_prefs"
    private const val PREF_CATALOG_VERSION = "catalog_version"
    private const val PREF_USAGE_PREFIX = "usage_count_"
    private const val PREF_LAST_USED_PREFIX = "last_used_"
    private const val MAX_CACHED_TRACKS = 30
    private const val MIN_VALID_MP3_BYTES = 100 * 1024L // 100 KB

    data class RemoteTrack(
        val id: String,
        val genre: String,
        val bpm: Int,
        val durationMs: Long,
        val url: String,
        val subStyle: String = "FAST_DRIVING",
        val energy: String = "HIGH",
        val dropOffsetsMs: List<Long> = listOf(0L)
    )

    /**
     * Scarica eventuali tracce extra dal catalogo remoto che non sono già incluse nel pacchetto res/raw/.
     */
    suspend fun fetchExtraTracks(context: Context, genre: String): List<RemoteTrack> = withContext(Dispatchers.IO) {
        val cleanGenre = genre.trim().uppercase(java.util.Locale.ROOT)
        try {
            val catalogJson = fetchOrLoadCatalog(context) ?: return@withContext emptyList()
            checkAndInvalidateCacheOnNewVersion(context, catalogJson)
            return@withContext parseGenreTracks(catalogJson, cleanGenre)
        } catch (e: Exception) {
            Log.w(TAG, "Impossibile recuperare tracce extra per $cleanGenre: ${e.message}")
            return@withContext emptyList()
        }
    }

    /**
     * Risolve la traccia musicale per il genere specificato:
     * 1. Tenta di scaricare o leggere da cache il catalogo remoto.
     * 2. Invalida e svuota la cache se la versione del catalogo è incrementata.
     * 3. Applica l'algoritmo anti-ripetizione persistente (SharedPreferences).
     * 4. Verifica la presenza del file MP3 in cache (o lo scarica atomicamente).
     * 5. Se la rete non è disponibile o si verifica qualsiasi anomalia, esegue il fallback
     *    immediato sul pool locale res/raw/ senza bloccare l'esperienza utente.
     */
    suspend fun resolveTrack(context: Context, genre: String): AudioSource = withContext(Dispatchers.IO) {
        val cleanGenre = genre.trim().uppercase(java.util.Locale.ROOT)
        val localFallbackDetector = AudioBeatDetector()

        try {
            val catalogJson = fetchOrLoadCatalog(context)
            if (catalogJson != null) {
                checkAndInvalidateCacheOnNewVersion(context, catalogJson)
                val tracks = parseGenreTracks(catalogJson, cleanGenre)
                if (tracks.isNotEmpty()) {
                    val selectedTrack = selectTrackWithAntiRepetition(context, cleanGenre, tracks)
                    val cachedFile = ensureTrackCached(context, selectedTrack)
                    if (cachedFile != null && cachedFile.exists() && cachedFile.length() >= MIN_VALID_MP3_BYTES) {
                        Log.i(TAG, "Traccia remota pronta: ${selectedTrack.id} (${selectedTrack.genre}, ${selectedTrack.bpm} BPM)")
                        return@withContext AudioSource.LocalFile(cachedFile, selectedTrack.id)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Errore nella risoluzione traccia remota, attivo fallback su res/raw/", e)
            ErrorLogger.log(
                context = context,
                error = e,
                settings = "RemoteAudioManager fallback to raw for genre=$cleanGenre",
                editPlanJson = null
            )
        }

        // Fallback robusto su risorsa locale offline con offset anti-intro
        val fallbackSource = localFallbackDetector.getLocalAudioSource(cleanGenre)
        Log.i(TAG, "Utilizzo risorsa audio locale di fallback per $cleanGenre: resId=${fallbackSource.resId}, offset=${fallbackSource.startOffsetMs}ms")
        return@withContext fallbackSource
    }

    /**
     * Verifica la versione del catalogo e, se cambiata rispetto a quella salvata,
     * svuota la directory cache dei vecchi MP3 con l'intro lenta e resetta last_used_*.
     */
    private fun checkAndInvalidateCacheOnNewVersion(context: Context, catalogJson: JSONObject) {
        val serverVersion = catalogJson.optInt("version", 1)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val localVersion = prefs.getInt(PREF_CATALOG_VERSION, 0)

        if (serverVersion > localVersion) {
            Log.i(TAG, "Nuova versione catalogo rilevata ($serverVersion > $localVersion). Svuotamento cache audio...")
            val tracksDir = File(context.cacheDir, "remote_tracks")
            if (tracksDir.exists()) {
                tracksDir.listFiles()?.forEach { it.delete() }
            }

            val editor = prefs.edit()
            editor.putInt(PREF_CATALOG_VERSION, serverVersion)
            // Rimuovi vecchi last_used e usage_count
            for (key in prefs.all.keys) {
                if (key.startsWith(PREF_LAST_USED_PREFIX) || key.startsWith(PREF_USAGE_PREFIX)) {
                    editor.remove(key)
                }
            }
            editor.apply()
        }
    }

    /**
     * Scarica catalog.json da GitHub Raw con timeout brevi (4s connessione, 6s lettura).
     * Se la connessione fallisce, carica l'ultima copia salvata in filesDir.
     */
    private fun fetchOrLoadCatalog(context: Context): JSONObject? {
        val backupFile = File(context.filesDir, "catalog_cache.json")
        try {
            val url = URL("$CATALOG_URL?t=${System.currentTimeMillis() / 60000L}")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                useCaches = false
                connectTimeout = 4000
                readTimeout = 6000
                requestMethod = "GET"
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "OrangeBeat-Android-Client")
                setRequestProperty("Cache-Control", "no-cache")
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val jsonString = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()
                // Salva copia locale per consultazione offline
                backupFile.writeText(jsonString)
                return JSONObject(jsonString)
            }
            connection.disconnect()
        } catch (e: Exception) {
            Log.d(TAG, "Download catalog.json non riuscito: ${e.message}. Tento lettura da backup locale.")
        }

        // Tentativo di lettura da file di backup locale
        return if (backupFile.exists()) {
            try {
                JSONObject(backupFile.readText())
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
    }

    private fun parseGenreTracks(catalogJson: JSONObject, genre: String): List<RemoteTrack> {
        val result = mutableListOf<RemoteTrack>()
        val genreArray = catalogJson.optJSONArray(genre) ?: catalogJson.optJSONArray(genre.lowercase()) ?: return emptyList()

        for (i in 0 until genreArray.length()) {
            val obj = genreArray.optJSONObject(i) ?: continue
            val id = obj.optString("id", "")
            val url = obj.optString("url", "")
            if (id.isNotEmpty() && url.isNotEmpty()) {
                val offsetsArray = obj.optJSONArray("dropOffsetsMs")
                val offsets = if (offsetsArray != null && offsetsArray.length() > 0) {
                    (0 until offsetsArray.length()).map { idx -> offsetsArray.optLong(idx, 0L) }
                } else {
                    listOf(0L)
                }
                result.add(
                    RemoteTrack(
                        id = id,
                        genre = obj.optString("genre", genre),
                        bpm = obj.optInt("bpm", 128),
                        durationMs = obj.optLong("durationMs", 60000L),
                        url = url,
                        subStyle = obj.optString("subStyle", "FAST_DRIVING"),
                        energy = obj.optString("energy", "HIGH"),
                        dropOffsetsMs = offsets
                    )
                )
            }
        }
        return result
    }

    /**
     * Algoritmo deterministico anti-ripetizione persistente basato su SharedPreferences:
     * - Esclude l'ultima traccia usata per il genere.
     * - Filtra i brani con il conteggio minimo di utilizzi (min-usage count).
     * - Estrae casualmente tra quelli a minimo utilizzo e incrementa il contatore.
     */
    private fun selectTrackWithAntiRepetition(
        context: Context,
        genre: String,
        tracks: List<RemoteTrack>
    ): RemoteTrack {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastUsedId = prefs.getString("$PREF_LAST_USED_PREFIX$genre", null)

        val nonConsecutiveCandidates = if (tracks.size > 1 && lastUsedId != null) {
            tracks.filter { it.id != lastUsedId }
        } else {
            tracks
        }
        val pool = nonConsecutiveCandidates.ifEmpty { tracks }

        val minUsage = pool.minOf { track ->
            prefs.getInt("$PREF_USAGE_PREFIX${track.id}", 0)
        }

        val leastUsedCandidates = pool.filter { track ->
            prefs.getInt("$PREF_USAGE_PREFIX${track.id}", 0) == minUsage
        }

        val chosenTrack = leastUsedCandidates.random()

        // Aggiorna contatori persistenti
        val currentCount = prefs.getInt("$PREF_USAGE_PREFIX${chosenTrack.id}", 0)
        prefs.edit()
            .putInt("$PREF_USAGE_PREFIX${chosenTrack.id}", currentCount + 1)
            .putString("$PREF_LAST_USED_PREFIX$genre", chosenTrack.id)
            .apply()

        return chosenTrack
    }

    /**
     * Verifica la presenza del file MP3 nella cartella cacheDir/remote_tracks/.
     * Se mancante o corrotto (< 100 KB), lo scarica tramite .tmp atomico.
     */
    fun ensureTrackCached(context: Context, track: RemoteTrack): File? {
        val tracksDir = File(context.cacheDir, "remote_tracks").apply { mkdirs() }
        val targetFile = File(tracksDir, "${track.id}.mp3")

        if (targetFile.exists() && targetFile.length() >= MIN_VALID_MP3_BYTES) {
            return targetFile
        }

        val tempFile = File(tracksDir, "${track.id}.tmp")
        try {
            val url = URL(track.url)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                useCaches = false
                connectTimeout = 5000
                readTimeout = 12000
                requestMethod = "GET"
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "OrangeBeat-Android-Client")
                setRequestProperty("Cache-Control", "no-cache")
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.use { input: InputStream ->
                    FileOutputStream(tempFile).use { output: FileOutputStream ->
                        input.copyTo(output)
                    }
                }
                connection.disconnect()

                if (tempFile.length() >= MIN_VALID_MP3_BYTES) {
                    if (targetFile.exists()) targetFile.delete()
                    if (tempFile.renameTo(targetFile)) {
                        pruneCacheIfNeeded(tracksDir)
                        return targetFile
                    }
                }
            }
            connection.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "Download fallito per ${track.id} da ${track.url}: ${e.message}")
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }

        return if (targetFile.exists() && targetFile.length() >= MIN_VALID_MP3_BYTES) targetFile else null
    }

    /**
     * Mantiene un tetto massimo di MAX_CACHED_TRACKS file MP3 in cache eliminando
     * i file meno recentemente modificati.
     */
    private fun pruneCacheIfNeeded(tracksDir: File) {
        val files = tracksDir.listFiles { file -> file.isFile && file.extension == "mp3" } ?: return
        if (files.size > MAX_CACHED_TRACKS) {
            files.sortedBy { it.lastModified() }
                .take(files.size - MAX_CACHED_TRACKS)
                .forEach { it.delete() }
        }
    }
}
