package com.kubedash.ui.screens.viewmodel

import androidx.lifecycle.ViewModel
import com.kubedash.ThemeManager

class SettingsScreenViewModel : ViewModel() {
    val isDarkTheme: Boolean
        get() = ThemeManager.isDarkTheme

    fun setDarkTheme(dark: Boolean) {
        ThemeManager.isDarkTheme = dark
    }
}
