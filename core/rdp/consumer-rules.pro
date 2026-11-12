# Native code calls LibFreeRDP's static callbacks by name.
-keep class com.freerdp.freerdpcore.services.LibFreeRDP { *; }
-keep class com.nautile.rdp.RdpCallbacks { *; }
