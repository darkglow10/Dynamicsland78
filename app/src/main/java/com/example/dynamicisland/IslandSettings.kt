// IslandSettings data class içerisine eklenecek alan:
val incomingCallEnabled: Boolean = true,

// SettingsRepository içerisindeki K nesnesine eklenecek anahtar:
val CALL_ON = booleanPreferencesKey("call_on")

// flow map içerisine:
incomingCallEnabled = p[K.CALL_ON] ?: d.incomingCallEnabled,

// save fonksiyonu içerisine:
it[K.CALL_ON] = s.incomingCallEnabled
