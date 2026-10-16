package org.sinytra

import dev.kord.core.Kord
import dev.kord.core.entity.application.GlobalChatInputCommand
import dev.kord.core.event.interaction.GuildChatInputCommandInteractionCreateEvent
import dev.kord.rest.builder.interaction.string

class CheckCompatChatCommand(private val gameVersions: List<String>) : CheckCompatCommandBase() {

    suspend fun register(kord: Kord): GlobalChatInputCommand {
        return kord.createGlobalChatInputCommand(
            "test",
            "Test mod compatibility"
        ) {
            string("slug", "Project slug") {
                required = true
            }
            string("platform", "Project platform (default: Modrinth)") {
                choice("Modrinth", "modrinth")

                required = false
            }
            string("game_version", "Game version (default: ${gameVersions.first()})") {
                gameVersions.forEach { choice(it, it) }
                required = false
            }
        }
    }

    suspend fun GuildChatInputCommandInteractionCreateEvent.handle() {
        val response = interaction.deferPublicResponse()

        val platform = interaction.command.strings["platform"] ?: "modrinth"
        val slug = interaction.command.strings["slug"]!!
        val gameVersion = interaction.command.strings["game_version"] ?: gameVersions.first()

        checkCompat(response, platform, slug, gameVersion)
    }
}