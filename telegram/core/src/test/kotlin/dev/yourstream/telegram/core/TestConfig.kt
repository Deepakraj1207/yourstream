package dev.yourstream.telegram.core

import dev.yourstream.telegram.api.model.TelegramConfig

val VALID_CONFIG = TelegramConfig(
    apiId = 12345,
    apiHash = "0123456789abcdef0123456789abcdef",
    databaseDirectory = "/tmp/td-db",
    filesDirectory = "/tmp/td-files",
)
