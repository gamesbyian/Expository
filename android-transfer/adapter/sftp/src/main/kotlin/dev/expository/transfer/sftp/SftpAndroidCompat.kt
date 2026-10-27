package dev.expository.transfer.sftp

import org.apache.sshd.common.util.OsUtils
import org.apache.sshd.common.util.io.PathUtils
import java.nio.file.Files
import java.nio.file.Path

/**
 * Apache MINA expects Java user.home and user.dir, which may not be present
 * on Android. Install its supported resolver hooks before opening an SFTP
 * connection. Pass only the application's own private files directory.
 *
 * No system properties are changed, no broad storage grant is requested and
 * no SSH keys, user names or credentials are read by this setup. Repeated
 * initialization must not redirect MINA to a different path mid-session.
 */
object SftpAndroidCompat {
    private var configuredHome: Path? = null

    @Synchronized
    fun configurePrivateHome(directory: Path) {
        val path = directory.toAbsolutePath().normalize()
        require(Files.isDirectory(path)) { "APP_PRIVATE_HOME_REQUIRED" }
        val previous = configuredHome
        if (previous != null) {
            require(previous == path) { "SFTP_HOME_CHANGED_IN_ACTIVE_PROCESS" }
            return
        }
        PathUtils.setUserHomeFolderResolver { path }
        OsUtils.setCurrentWorkingDirectoryResolver { path }
        configuredHome = path
    }
}
