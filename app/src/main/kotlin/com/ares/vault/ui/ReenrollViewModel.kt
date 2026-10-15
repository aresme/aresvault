package com.ares.vault.ui

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ReenrollViewModel @Inject constructor(private val enroller: BiometricEnroller) : ViewModel() {
    suspend fun enable(activity: FragmentActivity) {
        enroller.enable(activity)
    }
}
