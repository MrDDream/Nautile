package com.nautile.data.db

import androidx.room.TypeConverter
import com.nautile.data.model.AuthMethod
import com.nautile.data.model.Protocol
import com.nautile.data.model.ProxyKind
import com.nautile.data.model.ForwardKind

class Converters {
    @TypeConverter fun protocolToString(p: Protocol): String = p.name
    @TypeConverter fun stringToProtocol(s: String): Protocol = Protocol.valueOf(s)
    @TypeConverter fun authToString(a: AuthMethod): String = a.name
    @TypeConverter fun stringToAuth(s: String): AuthMethod = AuthMethod.valueOf(s)
    @TypeConverter fun forwardToString(k: ForwardKind): String = k.name
    @TypeConverter fun stringToForward(s: String): ForwardKind = ForwardKind.valueOf(s)
    @TypeConverter fun proxyToString(p: ProxyKind?): String? = p?.name
    @TypeConverter fun stringToProxy(s: String?): ProxyKind? = s?.let { ProxyKind.valueOf(it) }
}
