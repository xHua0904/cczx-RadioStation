package com.cczxcsc.common

class ApiError(val status: Int, override val message: String) : RuntimeException(message)
