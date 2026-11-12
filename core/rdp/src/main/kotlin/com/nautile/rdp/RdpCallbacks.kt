package com.nautile.rdp

/** Events of one FreeRDP instance, called on FreeRDP's session thread. */
interface RdpCallbacks {
    fun onConnected()
    fun onConnectionFailed(error: String?)
    fun onDisconnected(error: String?)
    fun onReconnecting()
    fun onSettingsChanged(width: Int, height: Int, bpp: Int)

    /** Fill the builders and return true to retry the logon, false to give up. */
    fun onAuthenticate(username: StringBuilder, domain: StringBuilder, password: StringBuilder, gateway: Boolean): Boolean

    /** 0 reject, 1 trust and remember (FreeRDP store), 2 trust for this session only. */
    fun onVerifyCertificate(host: String, port: Int, commonName: String, subject: String, issuer: String, fingerprint: String, flags: Long): Int

    fun onGraphicsResize(width: Int, height: Int, bpp: Int)
    fun onGraphicsUpdate(x: Int, y: Int, width: Int, height: Int)
    fun onRemoteClipboard(text: String)

    /** [pixels] ARGB, null hides the pointer. */
    fun onPointer(pixels: IntArray?, width: Int, height: Int, hotX: Int, hotY: Int)
    fun onPointerDefault()
}
