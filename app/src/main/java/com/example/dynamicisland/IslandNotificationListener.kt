// Sınıf içerisine eklenecek alanlar ve metodlar:
private val telephonyManager by lazy { getSystemService(TelephonyManager::class.java) }
private var lastCallPublished: IslandContent.Call? = null
private var callStartTime = 0L
private var currentIncomingNumber = ""
private var currentCallerName = ""

@Suppress("DEPRECATION")
private val phoneStateListener = object : PhoneStateListener() {
    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
        super.onCallStateChanged(state, phoneNumber)
        runCatching { handleCallState(state, phoneNumber) }
    }
}

// onListenerConnected içerisine ekleyin:
runCatching {
    @Suppress("DEPRECATION")
    telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
}

// Komut dinleyicisine eklenecek:
sc.launch { IslandBus.callCommands.collect(::handleCallCommand) }

private fun handleCallCommand(cmd: CallCommand) {
    when (cmd) {
        CallCommand.Answer -> {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val telecomManager = getSystemService(Context.TELECOM_SERVICE) as? android.telecom.TelecomManager
                    if (checkSelfPermission(android.Manifest.permission.ANSWER_PHONE_CALLS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        telecomManager?.acceptRingingCall()
                    }
                }
            }
        }
        CallCommand.Reject -> {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val telecomManager = getSystemService(Context.TELECOM_SERVICE) as? android.telecom.TelecomManager
                    if (checkSelfPermission(android.Manifest.permission.ANSWER_PHONE_CALLS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        telecomManager?.endCall()
                    }
                }
            }
        }
    }
}

private fun handleCallState(state: Int, phoneNumber: String?) {
    when (state) {
        TelephonyManager.CALL_STATE_RINGING -> {
            currentIncomingNumber = phoneNumber.orEmpty()
            currentCallerName = resolveCallerName(currentIncomingNumber)
            callStartTime = SystemClock.elapsedRealtime()
            val call = IslandContent.Call(
                callerName = currentCallerName.ifBlank { currentIncomingNumber.ifBlank { "Bilinmeyen" } },
                phoneNumber = currentIncomingNumber,
                isRinging = true,
                callDurationSeconds = 0L
            )
            lastCallPublished = call
            IslandBus.post(IslandEvent.CallChanged(call))
        }
        TelephonyManager.CALL_STATE_OFFHOOK -> {
            val duration = if (callStartTime > 0) (SystemClock.elapsedRealtime() - callStartTime) / 1000 else 0L
            val call = IslandContent.Call(
                callerName = currentCallerName.ifBlank { currentIncomingNumber.ifBlank { "Arama" } },
                phoneNumber = currentIncomingNumber,
                isRinging = false,
                callDurationSeconds = duration
            )
            lastCallPublished = call
            IslandBus.post(IslandEvent.CallChanged(call))
        }
        TelephonyManager.CALL_STATE_IDLE -> {
            callStartTime = 0L
            lastCallPublished = null
            IslandBus.post(IslandEvent.CallChanged(null))
        }
    }
}

private fun resolveCallerName(number: String): String {
    if (number.isBlank()) return ""
    if (checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
        return number
    }
    return runCatching {
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        val cursor = contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
        cursor?.use { if (it.moveToFirst()) it.getString(0) else null } ?: number
    }.getOrDefault(number)
}
