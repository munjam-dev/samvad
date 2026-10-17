package com.samvad.messenger.ui.vault

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.samvad.messenger.AppContainer

const val VAULT_ROUTE = "vault"

/**
 * The Personal Vault destinations, added to the signed-in navigation graph in one call so that
 * SamvadRoot needs no vault knowledge beyond this function and [VAULT_ROUTE].
 */
fun NavGraphBuilder.vaultRoutes(nav: NavHostController, container: AppContainer) {
  composable(VAULT_ROUTE) {
    VaultScreen(
      container = container,
      onBack = { nav.popBackStack() },
      onViewImage = { id -> nav.navigate("vault/image/$id") },
      onEditNote = { id -> nav.navigate(if (id == null) "vault/note" else "vault/note/$id") },
    )
  }
  composable("vault/image/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
    VaultImageScreen(container, entry.arguments!!.getString("id")!!, onBack = { nav.popBackStack() })
  }
  composable("vault/note") {
    VaultNoteScreen(container, null, onBack = { nav.popBackStack() })
  }
  composable("vault/note/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
    VaultNoteScreen(container, entry.arguments!!.getString("id")!!, onBack = { nav.popBackStack() })
  }
}
