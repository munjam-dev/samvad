package com.example.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.ui.graphics.vector.ImageVector

object CategoryIconHelper {
    fun getIconForCategory(iconKey: String): ImageVector {
        return when (iconKey.lowercase()) {
            "badge", "identity" -> Icons.Filled.Badge
            "school", "education" -> Icons.Filled.School
            "account_balance", "banking", "finance" -> Icons.Filled.AccountBalance
            "verified_user", "insurance" -> Icons.Filled.VerifiedUser
            "medical_services", "medical", "health" -> Icons.Filled.LocalHospital
            "flight", "travel" -> Icons.Filled.Flight
            else -> Icons.Filled.Folder
        }
    }
}
