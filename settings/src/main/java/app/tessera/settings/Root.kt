package app.tessera.settings

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.util.concurrent.Executors

/**
 * Client for the root helper daemon on the abstract socket `tesserad`: one command line out,
 * its stdout back. Everything that an ordinary app may not change goes through here.
 */
object Root {
    /** One thread, so commands land in the order the user caused them. */
    private val queue = Executors.newSingleThreadExecutor()

    /**
     * Blocking; never call on the main thread. Null means the daemon is absent or the call failed.
     * LocalSocket has no connect timeout, but an abstract socket either accepts or refuses at once;
     * the read timeout is what bounds a hung command.
     */
    fun sh(cmd: String, timeoutMs: Int = 4000): String? = try {
        val s = LocalSocket()
        try {
            s.connect(LocalSocketAddress("tesserad", LocalSocketAddress.Namespace.ABSTRACT))
            s.soTimeout = timeoutMs
            s.outputStream.run { write((cmd + "\n").toByteArray(Charsets.UTF_8)); flush() }
            s.shutdownOutput()
            s.inputStream.readBytes().toString(Charsets.UTF_8)
        } finally {
            runCatching { s.close() }
        }
    } catch (_: Throwable) {
        null
    }

    /** Runs [cmd] off the main thread and hands its stdout (or null) back on it. */
    fun async(cmd: String, timeoutMs: Int = 4000, cb: (String?) -> Unit = {}) {
        queue.execute {
            val out = sh(cmd, timeoutMs)
            Ui.main.post { cb(out) }
        }
    }

    /** For commands whose only interesting result is whether they worked: the exit status. */
    fun run(cmd: String, timeoutMs: Int = 4000, cb: (Boolean) -> Unit = {}) =
        async("( $cmd ) >/dev/null 2>&1; echo $?", timeoutMs) { cb(it?.trim() == "0") }

    /** Single-quotes [s] for sh, so SSIDs and passphrases cannot break out of their argument. */
    fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"
}
