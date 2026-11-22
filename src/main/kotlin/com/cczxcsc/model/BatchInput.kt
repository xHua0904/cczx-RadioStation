package com.cczxcsc.model

data class BatchInput(val ids: List<Long> = emptyList(), val action: String = "", val note: String = "", val broadcastDate: String = "", val broadcastTime: String = "", val confirmed: Boolean = false)
