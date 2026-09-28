package de.jvg.mercurius;

// Runs inside a Shizuku user service with shell (adb) privileges.
interface IShellService {
    // Reserved by Shizuku for tearing the service down.
    void destroy() = 16777114;

    // Runs `sh -c cmd` and returns "exit=<code>" followed by combined stdout and stderr.
    String exec(String cmd) = 1;

    // Start/stop the Wi-Fi hotspot through TetheringManager. Returns a short result text.
    String startHotspot() = 2;
    String stopHotspot() = 3;
}
