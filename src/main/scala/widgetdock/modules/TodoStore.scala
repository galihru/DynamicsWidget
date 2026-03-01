package widgetdock.modules

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.util.Try
import ujson.*

final case class TodoItem(
    id: String,
    text: String,
    done: Boolean,
    createdAtMs: Long
)

final class TodoStore(baseDir: Path):

  private val file = baseDir.resolve("todo.json")

  private var items: Vector[TodoItem] = loadFromDisk()

  def all(): Vector[TodoItem] = items

  def add(text: String): TodoItem =
    val clean = text.trim
    val item = TodoItem(
      id = java.util.UUID.randomUUID().toString.take(8),
      text = clean,
      done = false,
      createdAtMs = System.currentTimeMillis()
    )
    items = items :+ item
    saveToDisk()
    item

  def toggle(id: String): Unit =
    items = items.map { t =>
      if t.id == id then t.copy(done = !t.done) else t
    }
    saveToDisk()

  def delete(id: String): Unit =
    items = items.filterNot(_.id == id)
    saveToDisk()

  private def loadFromDisk(): Vector[TodoItem] =
    try
      if !Files.exists(baseDir) then Files.createDirectories(baseDir)
      if !Files.exists(file) then return Vector.empty

      val text = Files.readString(file, StandardCharsets.UTF_8)
      val json = ujson.read(text)
      json.arr.toVector.map { v =>
        TodoItem(
          id = v("id").str,
          text = v("text").str,
          done = v("done").bool,
          createdAtMs = v.obj.get("createdAtMs").map(_.num.toLong).getOrElse(0L)
        )
      }
    catch
      case _: Throwable => Vector.empty

  private def saveToDisk(): Unit =
    try
      if !Files.exists(baseDir) then Files.createDirectories(baseDir)
      val arr = Arr.from(items.map(toJson))
      Files.writeString(file, arr.render(2), StandardCharsets.UTF_8)
    catch
      case _: Throwable => ()

  private def toJson(item: TodoItem): Value =
    Obj(
      "id" -> item.id,
      "text" -> item.text,
      "done" -> item.done,
      "createdAtMs" -> item.createdAtMs
    )

object TodoStore:
  def defaultStore(): TodoStore =
    val localAppData = Option(System.getenv("LOCALAPPDATA"))
      .map(Paths.get(_))
      .getOrElse(Paths.get(System.getProperty("java.io.tmpdir")))
    new TodoStore(localAppData.resolve("WidgetDockPro"))
