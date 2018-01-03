package com.twitter.finagle.toggle

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.util.{DefaultIndenter, DefaultPrettyPrinter}
import com.fasterxml.jackson.databind.{MappingJsonFactory, ObjectMapper}
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.twitter.util.Try
import java.net.URL
import scala.collection.mutable
import scala.collection.{breakOut, immutable}

/**
 * [[ToggleMap ToggleMaps]] in JSON format.
 *
 * @define jsonschema The [[http://json-schema.org/ JSON Schema]] used is:
 * {{{
 * {
 *   "\$schema": "http://json-schema.org/draft-04/schema#",
 *   "type": "object",
 *   "required": [
 *     "toggles"
 *   ],
 *   "properties": {
 *     "toggles": {
 *       "type": "array",
 *       "items": {
 *         "type": "object",
 *         "properties": {
 *           "id": { "type": "string" },
 *           "description": { "type": "string" },
 *           "fraction": {
 *             "type": "number",
 *             "minimum": 0.0,
 *             "maximum": 1.0,
 *             "exclusiveMinimum": false,
 *             "exclusiveMaxmimum": false
 *           },
 *           "comment": { "type": "string" }
 *         },
 *         "required": [
 *           "id",
 *           "fraction"
 *         ]
 *       }
 *     }
 *   }
 * }
 * }}}
 *
 * @define example Here is an example of a JSON [[ToggleMap]] input:
 * {{{
 * {
 *   "toggles": [
 *     {
 *       "id": "com.game.of.thrones.WargingEnabled",
 *       "description": "Use warging for computing the response.",
 *       "fraction": 0.1
 *     },
 *     {
 *       "id": "com.game.of.thrones.IsWinterComing",
 *       "description": "Controls whether or not winter is coming.",
 *       "fraction": 1.0,
 *       "comment": "We've seen the white walkers, we know that winter is coming."
 *     }
 *   ]
 * }
 * }}}
 *
 * With the exception of "comment", the properties correspond to the various
 * fields on [[Toggle.Metadata]].
 *  - '''name''': Corresponds to `Toggle.Metadata.id`.
 *  - '''fraction''': Corresponds to `Toggle.Metadata.fraction` and values must be
 *    between `0.0` and `1.0`, inclusive.
 *  - '''description''': Corresponds to `Toggle.Metadata.description`.
 *  - '''comment''': For documentation purposes only and is not used in the
 *    creation of the [[ToggleMap]].
 */
object JsonToggleMap {

  private[this] val mapper: ObjectMapper =
    new ObjectMapper().registerModule(DefaultScalaModule)

  /**
   * How to treat the "description" field on a toggle.
   *
   * @see [[DescriptionIgnored]] and [[DescriptionRequired]].
   */
  sealed abstract class DescriptionMode

  /**
   * Requires toggles to have a "description" field.
   *
   * This is useful for the library owner's base definitions of [[Toggle]].
   */
  object DescriptionRequired extends DescriptionMode

  /**
   * Transforms the Toggle's "description" field into being empty.
   *
   * This is useful for service owner overrides of a toggle where
   * the developer making modifications is not the one who has defined
   * the toggle itself.
   */
  object DescriptionIgnored extends DescriptionMode

  private[this] case class JsonToggle(
    @JsonProperty(required = true) id: String,
    @JsonProperty(required = true) fraction: Double,
    description: Option[String],
    comment: Option[String]
  )

  private[this] case class JsonToggles(@JsonProperty(required = true) toggles: Seq[JsonToggle]) {

    def toToggleMap(
      source: String,
      descriptionMode: DescriptionMode
    ): ToggleMap = {
      val invalid = toggles.find { md =>
        descriptionMode match {
          case DescriptionRequired => md.description.isEmpty
          case DescriptionIgnored => false
        }
      }
      invalid match {
        case None => ()
        case Some(md) =>
          throw new IllegalArgumentException(s"Mandatory description is missing for: $md")
      }

      val metadata: immutable.Seq[Toggle.Metadata] =
        toggles.map { jsonToggle =>
          val description = descriptionMode match {
            case DescriptionRequired => jsonToggle.description
            case DescriptionIgnored => None
          }
          Toggle.Metadata(jsonToggle.id, jsonToggle.fraction, description, source)
        }(breakOut)

      val ids = metadata.map(_.id)
      val uniqueIds = ids.distinct
      if (ids.size != uniqueIds.size) {
        throw new IllegalArgumentException(s"Duplicate Toggle ids found: ${ids.mkString(",")}")
      }
      new ToggleMap.Immutable(metadata)
    }
  }

  /**
   * Attempts to parse the given JSON `String` into a [[ToggleMap]].
   *
   * $jsonschema
   *
   * $example
   *
   * @param descriptionMode how to treat the "description" field for a toggle.
   */
  def parse(json: String, descriptionMode: DescriptionMode): Try[ToggleMap] = Try {
    val jsonToggles = mapper.readValue(json, classOf[JsonToggles])
    jsonToggles.toToggleMap("JSON String", descriptionMode)
  }

  /**
   * Attempts to parse the given JSON `URL` into a [[ToggleMap]].
   *
   * Useful for loading resource files via [[StandardToggleMap]].
   *
   * $jsonschema
   *
   * $example
   *
   * @param descriptionMode how to treat the "description" field for a toggle.
   */
  def parse(url: URL, descriptionMode: DescriptionMode): Try[ToggleMap] = Try {
    val jsonToggles = mapper.readValue(url, classOf[JsonToggles])
    jsonToggles.toToggleMap(url.toString, descriptionMode)
  }

  private case class Component(source: String, fraction: Double)
  private case class LibraryToggle(current: Current, components: Seq[Component])
  private case class Library(libraryName: String, toggles: Seq[LibraryToggle])
  private case class Libraries(libraries: Seq[Library])
  private case class Current(
    id: String,
    fraction: Double,
    lastValue: Option[Boolean],
    description: Option[String]
  )

  private val factory = new MappingJsonFactory()
  factory.disable(JsonFactory.Feature.USE_THREAD_LOCAL_FOR_BUFFER_RECYCLING)
  private val printer = new DefaultPrettyPrinter
  printer.indentArraysWith(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE)
  mapper.writer(printer)

  private[this] def toLibraryToggles(toggleMap: ToggleMap): Seq[LibraryToggle] = {
    // create a map of id to metadata for faster lookups
    val idToMetadata = toggleMap.iterator.map { md =>
      md.id -> md
    }.toMap

    // create a mapping of id to a seq of its components.
    val idToComponents = mutable.Map.empty[String, mutable.ArrayBuffer[Component]]
    ToggleMap.components(toggleMap).foreach { tm =>
      tm.iterator.foreach { md =>
        val components: mutable.ArrayBuffer[Component] =
          idToComponents.getOrElse(md.id, mutable.ArrayBuffer.empty[Component])
        idToComponents.put(md.id, components += Component(md.source, md.fraction))
      }
    }

    idToComponents.map {
      case (id, details) =>
        val md = idToMetadata(id)
        val lastApply = toggleMap(id) match {
          case captured: Toggle.Captured => captured.lastApply
          case _ => None
        }
        LibraryToggle(Current(id, md.fraction, lastApply, md.description), details)
    }.toSeq
  }

  /**
   * Serialize a [[ToggleMap]] to JSON format
   */
  def toJson(registry: Map[String, ToggleMap]): String = {
    val libs = registry.map { case (name, toggleMap) => Library(name, toLibraryToggles(toggleMap)) }
    mapper.writeValueAsString(Libraries(libs.toSeq))
  }
}
