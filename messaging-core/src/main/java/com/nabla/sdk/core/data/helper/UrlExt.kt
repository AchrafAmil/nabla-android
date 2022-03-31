package com.nabla.sdk.core.data.helper

import com.nabla.sdk.core.domain.entity.Uri
import java.net.MalformedURLException
import java.net.URI

@Throws(MalformedURLException::class)
fun Uri.toJvmUri(): URI {
    return URI(uri)
}

fun URI.toKtUri(): Uri {
    return Uri(toString())
}