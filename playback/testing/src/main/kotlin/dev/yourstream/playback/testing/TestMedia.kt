package dev.yourstream.playback.testing

import java.io.File
import java.util.concurrent.TimeUnit

/** Synthetic test videos (made by scripts/make-test-media.sh: ffmpeg test patterns, no third-party content). */
object TestMedia {
    const val SMALL_360P = "small_360p_faststart.mp4"   // 640x360, 6 s, moov first
    const val RES_720P_MOOV_END = "res_720p_moov_end.mp4" // 1280x720, 3 s, moov at the end
    const val RES_1080P = "res_1080p.mp4"                 // 1920x1080, 2 s
    const val RES_480P_MKV = "res_480p.mkv"               // 854x480, 3 s, Matroska
    const val LONG_DURATION_MS = 5_100_000L               // long_85min.mp4

    /** A committed clip, copied to [dir]. */
    fun file(name: String, dir: File): File {
        dir.mkdirs()
        val out = File(dir, name)
        if (!out.exists()) {
            val stream = TestMedia::class.java.getResourceAsStream("/media/$name") ?: error("missing test media $name")
            stream.use { input -> out.outputStream().use { input.copyTo(it) } }
        }
        return out
    }

    /**
     * The 85-minute, ~150 MB file for seek tests. Too big to commit: generated once into the
     * build directory when ffmpeg is installed. Null when it cannot be made (tests then skip).
     */
    fun longVideo(): File? {
        val root = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "scripts/make-test-media.sh").exists() } ?: return null
        val dir = File(root, "build/test-media")
        val out = File(dir, "long_85min.mp4")
        if (out.length() > 100_000_000) return out
        synchronized(this) {
            if (out.length() > 100_000_000) return out
            val ffmpeg = System.getenv("PATH").orEmpty().split(File.pathSeparator).any { File(it, "ffmpeg").canExecute() }
            if (!ffmpeg) return null
            val p = ProcessBuilder("bash", File(root, "scripts/make-test-media.sh").path, dir.path, "long")
                .redirectErrorStream(true).start()
            if (!p.waitFor(10, TimeUnit.MINUTES) || p.exitValue() != 0) return null
        }
        return out.takeIf { it.length() > 100_000_000 }
    }
}
