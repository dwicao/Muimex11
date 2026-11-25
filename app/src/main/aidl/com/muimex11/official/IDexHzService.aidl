package com.muimex11.official;

interface IDexHzService {
    int getServiceUid() = 0;
    /** JSON snapshot of the session, the monitor, and the selectable rates. */
    String getState() = 1;
    String startBoost(float targetHz, boolean autoReconnect, boolean coolDown) = 2;
    String stopBoost() = 3;
    /** Applies new settings to a running session, or stores them for the next start. */
    void configure(float targetHz, boolean autoReconnect, boolean coolDown) = 4;
    /** Renegotiates the DisplayPort link so the monitor can come up in its fastest timing. */
    String reconnectMonitor() = 5;
    /**
     * JSON with the session's event log, the last saved service crash, and (when asked) the
     * display-related part of the system log.
     */
    String getDiagnostics(boolean includeSystemLog) = 6;
    /** Deletes the saved service crash once it has been reported or dismissed. */
    void clearCrash() = 7;
    /**
     * Restarts the monitor in a mode the user chose from the state's modeChoices: with root through
     * the composer's start-mode property, otherwise by setting it as the monitor connects (Snapdragon).
     */
    String switchMonitorMode(long displayId, int width, int height, int hz) = 8;
    /** Keeps (true) or undoes (false) a monitor mode switch that is waiting for the user. */
    String confirmMonitorMode(boolean keep) = 9;
    /** Stops setting the kept start mode; the monitor starts in its own default mode from its next connection. */
    String forgetMonitorStartMode() = 10;
    oneway void destroy() = 16777114;
}
