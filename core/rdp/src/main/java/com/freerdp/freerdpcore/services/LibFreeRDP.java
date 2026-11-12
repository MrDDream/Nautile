/*
   Android FreeRDP JNI Wrapper

   Copyright 2013 Thincast Technologies GmbH, Author: Martin Fleisz

   This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
   If a copy of the MPL was not distributed with this file, You can obtain one at
   http://mozilla.org/MPL/2.0/.

   Modified for Nautile (2026): the class keeps its package and the native / callback signatures
   expected by libfreerdp-android.so (FreeRDP 3.33.1), but callbacks are dispatched to a
   per-instance com.nautile.rdp.RdpCallbacks instead of aFreeRDP's GlobalApp / SessionState,
   and the bookmark-based argument builders were removed (see com.nautile.rdp.RdpArgs).
*/

package com.freerdp.freerdpcore.services;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import com.nautile.rdp.RdpCallbacks;

import java.util.HashMap;
import java.util.Map;

public final class LibFreeRDP
{
	private static final String TAG = "LibFreeRDP";
	private static final Map<Long, RdpCallbacks> callbacks = new HashMap<>();
	/**
	 * Instances whose session thread runs: from connect() until the final OnDisconnected /
	 * OnConnectionFailure callback has returned. Nothing in the native code joins that thread, so
	 * freeing the instance before would pull the context from under it.
	 */
	private static final Map<Long, Boolean> running = new HashMap<>();
	private static boolean hasH264 = false;
	private static Throwable loadError = null;

	public static final long VERIFY_CERT_FLAG_CHANGED = 0x40;
	public static final long VERIFY_CERT_FLAG_MISMATCH = 0x80;
	public static final long VERIFY_CERT_FLAG_FP_IS_PEM = 0x200;

	static
	{
		try
		{
			System.loadLibrary("freerdp-android");
			String version = freerdp_get_jni_version();
			String major = version.split("[.-]")[0];
			/* Load the dependent libraries too so that their JNI_OnLoad run. */
			System.loadLibrary("freerdp-client" + major);
			System.loadLibrary("freerdp" + major);
			System.loadLibrary("winpr" + major);
			hasH264 = freerdp_has_h264();
			Log.i(TAG, "FreeRDP " + version + ", H.264 " + (hasH264 ? "available" : "unavailable"));
		}
		catch (Throwable e)
		{
			Log.e(TAG, "Failed to load FreeRDP: " + e);
			loadError = e;
		}
	}

	private LibFreeRDP()
	{
	}

	/** Null when the native libraries are loaded, else why they could not be. */
	public static Throwable loadError()
	{
		return loadError;
	}

	public static boolean hasH264Support()
	{
		return hasH264;
	}

	private static native boolean freerdp_has_h264();

	private static native String freerdp_get_jni_version();

	private static native String freerdp_get_version();

	private static native long freerdp_new(Context context);

	private static native void freerdp_free(long inst);

	private static native boolean freerdp_parse_arguments(long inst, String[] args);

	private static native boolean freerdp_connect(long inst);

	private static native boolean freerdp_disconnect(long inst);

	private static native void freerdp_set_foreground(long inst, boolean foreground);

	private static native boolean freerdp_update_graphics(long inst, Bitmap bitmap, int x, int y,
	                                                      int width, int height);

	private static native boolean freerdp_send_cursor_event(long inst, int x, int y, int flags);

	private static native boolean freerdp_send_key_event(long inst, int keycode, boolean down);

	private static native boolean freerdp_send_unicodekey_event(long inst, int keycode,
	                                                            boolean down);

	private static native boolean freerdp_send_clipboard_data(long inst, String data);

	private static native boolean freerdp_send_monitor_layout(long inst, int width, int height);

	private static native String freerdp_get_last_error_string(long inst);

	public static String getVersion()
	{
		return freerdp_get_version();
	}

	public static long newInstance(Context context, RdpCallbacks cb)
	{
		long inst = freerdp_new(context);
		if (inst != 0)
			synchronized (callbacks)
			{
				callbacks.put(inst, cb);
			}
		return inst;
	}

	/**
	 * Disconnects if needed, waits for the session thread to end, then frees [inst]. Must not be
	 * called from the session thread (a callback).
	 */
	public static void freeInstance(long inst)
	{
		boolean waited = false;
		synchronized (running)
		{
			if (running.containsKey(inst))
				freerdp_disconnect(inst);
			long deadline = System.currentTimeMillis() + 15000;
			while (running.containsKey(inst) && System.currentTimeMillis() < deadline)
			{
				waited = true;
				try
				{
					running.wait(1000);
				}
				catch (InterruptedException e)
				{
					Thread.currentThread().interrupt();
					break;
				}
			}
			if (running.containsKey(inst))
			{
				// The thread is stuck: leak the instance rather than free it under its feet.
				Log.w(TAG, "session thread still running, instance not freed");
				return;
			}
		}
		if (waited)
		{
			// After its last callback the thread only logs and exits.
			try
			{
				Thread.sleep(100);
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
			}
		}
		freerdp_free(inst);
		synchronized (callbacks)
		{
			callbacks.remove(inst);
		}
	}

	public static boolean parseArguments(long inst, String[] args)
	{
		return freerdp_parse_arguments(inst, args);
	}

	/** Starts the session thread; the outcome arrives through the callbacks. */
	public static boolean connect(long inst)
	{
		setRunning(inst, true);
		boolean ok = freerdp_connect(inst);
		if (!ok)
			setRunning(inst, false);
		return ok;
	}

	public static boolean disconnect(long inst)
	{
		return freerdp_disconnect(inst);
	}

	public static void setForeground(long inst, boolean foreground)
	{
		freerdp_set_foreground(inst, foreground);
	}

	public static boolean updateGraphics(long inst, Bitmap bitmap, int x, int y, int width,
	                                     int height)
	{
		return freerdp_update_graphics(inst, bitmap, x, y, width, height);
	}

	public static boolean sendCursorEvent(long inst, int x, int y, int flags)
	{
		return freerdp_send_cursor_event(inst, x, y, flags);
	}

	/** [vk] is a Windows virtual-key code. */
	public static boolean sendKeyEvent(long inst, int vk, boolean down)
	{
		return freerdp_send_key_event(inst, vk, down);
	}

	public static boolean sendUnicodeKeyEvent(long inst, int codeUnit, boolean down)
	{
		return freerdp_send_unicodekey_event(inst, codeUnit, down);
	}

	public static boolean sendClipboardData(long inst, String data)
	{
		return freerdp_send_clipboard_data(inst, data);
	}

	public static boolean sendMonitorLayout(long inst, int width, int height)
	{
		return freerdp_send_monitor_layout(inst, width, height);
	}

	public static String getLastErrorString(long inst)
	{
		return freerdp_get_last_error_string(inst);
	}

	private static RdpCallbacks cb(long inst)
	{
		synchronized (callbacks)
		{
			return callbacks.get(inst);
		}
	}

	private static void setRunning(long inst, boolean on)
	{
		synchronized (running)
		{
			if (on)
				running.put(inst, true);
			else
				running.remove(inst);
			running.notifyAll();
		}
	}

	/* Called from native code (android_freerdp.c, android_cliprdr.c). */

	private static void OnPreConnect(long inst)
	{
	}

	private static void OnConnectionSuccess(long inst)
	{
		RdpCallbacks c = cb(inst);
		if (c != null)
			c.onConnected();
	}

	private static void OnConnectionFailure(long inst)
	{
		try
		{
			RdpCallbacks c = cb(inst);
			if (c != null)
				c.onConnectionFailed(freerdp_get_last_error_string(inst));
		}
		finally
		{
			setRunning(inst, false);
		}
	}

	private static void OnDisconnecting(long inst)
	{
	}

	private static void OnDisconnected(long inst)
	{
		try
		{
			RdpCallbacks c = cb(inst);
			if (c != null)
				c.onDisconnected(freerdp_get_last_error_string(inst));
		}
		finally
		{
			setRunning(inst, false);
		}
	}

	private static void OnReconnecting(long inst)
	{
		RdpCallbacks c = cb(inst);
		if (c != null)
			c.onReconnecting();
	}

	private static void OnSettingsChanged(long inst, int width, int height, int bpp)
	{
		RdpCallbacks c = cb(inst);
		if (c != null)
			c.onSettingsChanged(width, height, bpp);
	}

	private static boolean OnAuthenticate(long inst, StringBuilder username, StringBuilder domain,
	                                      StringBuilder password)
	{
		RdpCallbacks c = cb(inst);
		return c != null && c.onAuthenticate(username, domain, password, false);
	}

	private static boolean OnGatewayAuthenticate(long inst, StringBuilder username,
	                                             StringBuilder domain, StringBuilder password)
	{
		RdpCallbacks c = cb(inst);
		return c != null && c.onAuthenticate(username, domain, password, true);
	}

	private static int OnVerifyCertificateEx(long inst, String host, long port, String commonName,
	                                         String subject, String issuer, String fingerprint,
	                                         long flags)
	{
		RdpCallbacks c = cb(inst);
		return c == null ? 0
		                 : c.onVerifyCertificate(host, (int)port, commonName, subject, issuer,
		                                         fingerprint, flags);
	}

	private static int OnVerifyChangedCertificateEx(long inst, String host, long port,
	                                                String commonName, String subject,
	                                                String issuer, String fingerprint,
	                                                String oldSubject, String oldIssuer,
	                                                String oldFingerprint, long flags)
	{
		// Certificates are trusted for the session only (return 2), so FreeRDP's own store stays
		// empty and the app's store decides; treat a change report like a first sight.
		RdpCallbacks c = cb(inst);
		return c == null ? 0
		                 : c.onVerifyCertificate(host, (int)port, commonName, subject, issuer,
		                                         fingerprint, flags);
	}

	private static boolean OnExperimentalFeature(long inst, int feature)
	{
		return false;
	}

	private static void OnGraphicsUpdate(long inst, int x, int y, int width, int height)
	{
		RdpCallbacks c = cb(inst);
		if (c != null)
			c.onGraphicsUpdate(x, y, width, height);
	}

	private static void OnGraphicsResize(long inst, int width, int height, int bpp)
	{
		RdpCallbacks c = cb(inst);
		if (c != null)
			c.onGraphicsResize(width, height, bpp);
	}

	private static void OnRemoteClipboardChanged(long inst, String data)
	{
		RdpCallbacks c = cb(inst);
		if (c != null)
			c.onRemoteClipboard(data);
	}

	private static void OnRemoteClipboardImageChanged(long inst, byte[] data)
	{
	}

	private static void OnPointerSet(long inst, int[] pixels, int width, int height, int hotX,
	                                 int hotY)
	{
		RdpCallbacks c = cb(inst);
		if (c != null)
			c.onPointer(pixels, width, height, hotX, hotY);
	}

	private static void OnPointerSetNull(long inst)
	{
		RdpCallbacks c = cb(inst);
		if (c != null)
			c.onPointer(null, 0, 0, 0, 0);
	}

	private static void OnPointerSetDefault(long inst)
	{
		RdpCallbacks c = cb(inst);
		if (c != null)
			c.onPointerDefault();
	}

	/* RemoteApp (RAIL) is not offered by Nautile; the callbacks must still exist. */

	private static void OnRailWindowUpdate(long inst, long windowId, int width, int height,
	                                       int[] pixels)
	{
	}

	private static void OnRailWindowMove(long inst, long windowId, int x, int y, int w, int h)
	{
	}

	private static void OnRailWindowHide(long inst, long windowId)
	{
	}

	private static void OnRailWindowDestroy(long inst, long windowId)
	{
	}

	private static void OnRailSessionEnd(long inst)
	{
	}

	private static void OnRailMonitoredDesktop(long inst, long[] windowIds, long activeWindowId)
	{
	}
}
