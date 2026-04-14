package io.github.kdroidfilter.nucleus.servicemanagement

/**
 * Describes an app service to manage with [AppServiceManager].
 *
 * Maps to the `SMAppService` factory methods and properties:
 * - [MainApp] — `SMAppService.mainApp` (registers the app itself as a login item)
 * - [LoginItem] — `SMAppService.loginItem(identifier:)`
 * - [Agent] — `SMAppService.agent(plistName:)` (plist in `Contents/Library/LaunchAgents/`)
 * - [Daemon] — `SMAppService.daemon(plistName:)` (plist in `Contents/Library/LaunchDaemons/`)
 */
public sealed class AppService(
    internal val type: Int,
    internal val identifier: String,
) {
    /**
     * Registers the main application itself as a login item.
     *
     * This is the simplest way to make the app launch at user login —
     * no helper app or plist required.
     */
    public object MainApp : AppService(TYPE_MAIN_APP, "")

    /**
     * A login item helper that launches at user login.
     *
     * @param bundleIdentifier the bundle identifier of the login item helper
     *        (located in `Contents/Library/LoginItems/`)
     */
    public class LoginItem(bundleIdentifier: String) : AppService(TYPE_LOGIN_ITEM, bundleIdentifier)

    /**
     * A launch agent that runs in the user session.
     *
     * @param plistName the filename of the plist in `Contents/Library/LaunchAgents/`
     *        (e.g. `"com.myapp.agent.plist"`)
     */
    public class Agent(plistName: String) : AppService(TYPE_AGENT, plistName)

    /**
     * A launch daemon that runs as root.
     *
     * @param plistName the filename of the plist in `Contents/Library/LaunchDaemons/`
     *        (e.g. `"com.myapp.daemon.plist"`)
     */
    public class Daemon(plistName: String) : AppService(TYPE_DAEMON, plistName)

    internal companion object {
        const val TYPE_MAIN_APP = 3
        const val TYPE_LOGIN_ITEM = 0
        const val TYPE_AGENT = 1
        const val TYPE_DAEMON = 2
    }
}
