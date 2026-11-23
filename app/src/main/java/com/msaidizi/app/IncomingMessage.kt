package com.msaidizi.app

/** Ujumbe mpya uliotambuliwa kutoka kwenye arifa ya SMS au WhatsApp. [key] huzuia kusema ujumbe huo huo mara mbili. */
data class IncomingMessage(val app: String, val sender: String, val text: String, val key: String)
