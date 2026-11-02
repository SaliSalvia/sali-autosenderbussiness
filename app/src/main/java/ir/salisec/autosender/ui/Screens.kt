package ir.salisec.autosender.ui

sealed class Screen {
    object Home : Screen()
    object Settings : Screen()
    object Domains : Screen()
    object Contacts : Screen()
    object Reports : Screen()
    object Generate : Screen()
    object Send : Screen()
    object Inbox : Screen()
    object Pipeline : Screen()
}
