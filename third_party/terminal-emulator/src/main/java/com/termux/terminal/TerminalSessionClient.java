package com.termux.terminal;

/**
 * The interface for communication between {@link TerminalSession} and its client. It is used to
 * send callbacks to the client when {@link TerminalSession} changes or for sending other
 * back data to the client like logs.
 */
public interface TerminalSessionClient {

    void onTextChanged(TerminalSession changedSession);

    void onTitleChanged(TerminalSession changedSession);

    void onSessionFinished(TerminalSession finishedSession);

    void onCopyTextToClipboard(TerminalSession session, String text);

    /** Nautile: a program (not the user) asked to set the clipboard through OSC 52. Ignored by default. */
    default void onRemoteCopyTextToClipboard(TerminalSession session, String text) {
    }

    void onPasteTextFromClipboard(TerminalSession session);

    /**
     * Nautile: {@code count} bytes typed or pasted were not sent (main thread): {@code tooLarge} when they
     * exceed what the session queues at all, otherwise the peer has not read what was sent before.
     */
    default void onInputRefused(TerminalSession session, int count, boolean tooLarge) {
    }

    void onBell(TerminalSession session);

    void onColorsChanged(TerminalSession session);

    void onTerminalCursorStateChange(boolean state);



    Integer getTerminalCursorStyle();



    void logError(String tag, String message);

    void logWarn(String tag, String message);

    void logInfo(String tag, String message);

    void logDebug(String tag, String message);

    void logVerbose(String tag, String message);

    void logStackTraceWithMessage(String tag, String message, Exception e);

    void logStackTrace(String tag, Exception e);

}
