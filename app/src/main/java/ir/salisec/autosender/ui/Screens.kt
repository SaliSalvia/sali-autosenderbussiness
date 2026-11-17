package ir.salisec.autosender.ui

sealed class Screen {
    object Home : Screen()
    object Campaign : Screen()
    object Send : Screen()
    object PipelineInbox : Screen()
    object Settings : Screen()
}
