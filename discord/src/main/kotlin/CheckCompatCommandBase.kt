package org.sinytra

import dev.kord.common.Color
import dev.kord.core.behavior.interaction.response.DeferredMessageInteractionResponseBehavior
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.rest.builder.message.embed
import kotlinx.datetime.format.FormatStringsInDatetimeFormats
import kotlinx.datetime.toJavaLocalDateTime
import org.slf4j.LoggerFactory
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(FormatStringsInDatetimeFormats::class)
abstract class CheckCompatCommandBase {
    companion object {
        private val LOGGER = LoggerFactory.getLogger(CheckCompatCommandBase::class.java)
        
        private val REPLACED_PROJECTS = setOf("fabric-api", "P7dR8mSH")
        private val CONNECTOR_PROJECTS = setOf("connector", "u58R1TMW")
    }

    suspend fun checkCompat(response: DeferredMessageInteractionResponseBehavior, platform: String, slug: String) {
        if (slug in REPLACED_PROJECTS) {
            response.respond { 
                content = "When using Connector, please install the [Forgified Fabric API](https://modrinth.com/mod/forgified-fabric-api) instead."
            }
            return
        }

        if (slug in CONNECTOR_PROJECTS) {
            response.respond { 
                content = "Learn more about Connector on our [website](https://github.com/Sinytra/Connector)."
            }
            return
        }

        val result = try {
            TransformRunner.runTransformation(platform, slug)
        } catch (e: ProjectNotFoundException) {
            response.respond {
                content = ":warning: Project `${slug}` not found!"
            }
            return
        } catch (e: Exception) {
            LOGGER.error("Error transforming {} project {}: {}", platform, slug, e)

            response.respond {
                content = ":x: Internal server error"
            }
            return
        }

        if (result.type == ResultType.TESTED) {
            response.respondTestResult(result as TestResponseBody)
        } else {
            response.respondSkippedTest(result as SkippedResponseBody)
        }
    }

    private suspend fun DeferredMessageInteractionResponseBehavior.respondTestResult(result: TestResponseBody) {
        val green = Color(0, 255, 0)
        val red = Color(255, 0, 0)
        val link = result.projectUrl

        respond {
            embed {
                title = "Compatibility check results"
                color = if (result.passing) green else red
                url = link
                thumbnail {
                    url = result.iconUrl
                }

                field {
                    name = "Mod ID"
                    value = "`${result.modid}`"
                }
                field {
                    name = "Compatibility"
                    value = if (result.passing) "\u2705 Compatible" else "\u274C Incompatible"
                }
                field {
                    name = "Project page"
                    value = link
                }
                field {
                    name = "Mod version"
                    value = result.version
                    inline = true
                }
                field {
                    name = "Game version"
                    value = result.gameVersion
                    inline = true
                }
                field {
                    name = "Connector version"
                    value = result.toolchainVersion
                }
                field {
                    name = "Last tested at"
                    value = result.createdAt.toJavaLocalDateTime().format(
                        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.LONG, FormatStyle.SHORT)
                    )
                }
            }
        }
    }

    suspend fun DeferredMessageInteractionResponseBehavior.respondSkippedTest(result: SkippedResponseBody) {
        val neoOrange = Color(215, 116, 47)
        val link = result.projectUrl

        respond {
            embed {
                title = "Tests skipped"
                color = neoOrange
                url = link

                thumbnail {
                    url = result.iconUrl
                }

                field {
                    name = "Project slug"
                    value = "`${result.slug}`"
                }
                field {
                    name = "Compatibility"
                    value = ":question: Not tested - NeoForge build available"
                }
                field {
                    name = "Project page"
                    value = link
                }
                field {
                    name = "Game version"
                    value = result.gameVersion
                }
            }
        }
    }
}