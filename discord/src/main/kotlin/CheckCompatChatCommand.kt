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
            string("platform", "Project platform") {
                choice("Modrinth", "modrinth")

                required = true
            }
            string("slug", "Project slug") {
                required = true
            }
            string("game_version", "Game version (default: ${gameVersions.first()})") {
                gameVersions.forEach { choice(it, it) }
                required = false
            }
        }
    }

    suspend fun GuildChatInputCommandInteractionCreateEvent.handle() {
        val response = interaction.deferPublicResponse()

        val platform = interaction.command.strings["platform"]!!
        val slug = interaction.command.strings["slug"]!!
        val gameVersion = interaction.command.strings["game_version"] ?: gameVersions.first()

        checkCompat(response, platform, slug, gameVersion)
    }
}