package widgetdock.modules

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.util.Try
import ujson.*

final class UrlDraftStore(baseDir: Path):

  private val file = baseDir.resolve("url-drafts.json")
  private var drafts: Map[String, String] = loadFromDisk()

  def get(tabId: String): Option[String] =
    drafts.get(tabId).map(_.trim).filter(_.nonEmpty)

  def put(tabId: String, value: String): Unit =
    val clean = value.trim
    if clean.nonEmpty then drafts = drafts.updated(tabId, clean)
    else drafts = drafts - tabId
    saveToDisk()

  def remove(tabId: String): Unit =
    drafts = drafts - tabId
    saveToDisk()

  private def loadFromDisk(): Map[String, String] =
    try
      if !Files.exists(baseDir) then Files.createDirectories(baseDir)
      if !Files.exists(file) then return Map.empty
      val json = ujson.read(Files.readString(file, StandardCharsets.UTF_8))
      json.obj.iterator.map { case (k, v) => k -> v.str }.toMap
    catch
      case _: Throwable => Map.empty

  private def saveToDisk(): Unit =
    try
      if !Files.exists(baseDir) then Files.createDirectories(baseDir)
      val obj = Obj.from(drafts.iterator.map { case (k, v) => (k, Str(v)) })
      Files.writeString(file, obj.render(2), StandardCharsets.UTF_8)
    catch
      case _: Throwable => ()

object UrlDraftStore:
  def defaultStore(): UrlDraftStore =
    val localAppData = Option(System.getenv("LOCALAPPDATA"))
      .map(Paths.get(_))
      .getOrElse(Paths.get(System.getProperty("java.io.tmpdir")))
    UrlDraftStore(localAppData.resolve("WidgetDockPro"))
