package com.grimorio.hogwarts.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object Rede {

    fun disponivel(context: Context): Boolean {
        val conectividade = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val rede = conectividade.activeNetwork ?: return false
        val capacidades = conectividade.getNetworkCapabilities(rede) ?: return false
        return capacidades.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
