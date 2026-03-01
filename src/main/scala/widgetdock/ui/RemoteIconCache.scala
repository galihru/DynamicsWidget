package widgetdock.ui

import java.awt.Image
import java.awt.image.BufferedImage
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import javax.swing.ImageIcon
import scala.util.Try

object RemoteIconCache:

  private val cache = ConcurrentHashMap[String, ImageIcon]()

  def get(url: String, width: Int, height: Int): Option[ImageIcon] =
    val key = s"$url|$width|$height"
    Option(cache.get(key)).orElse {
      load(url, width, height).map { icon =>
        cache.put(key, icon)
        icon
      }
    }

  private def load(url: String, width: Int, height: Int): Option[ImageIcon] =
    Try {
      val conn = URL(url).openConnection()
      conn.setConnectTimeout(4000)
      conn.setReadTimeout(4000)
      conn.setRequestProperty("User-Agent", "WidgetDockPro/1.0")
      val stream = conn.getInputStream
      try
        val img = ImageIO.read(stream)
        if img == null then null
        else
          val scaled = img.getScaledInstance(width, height, Image.SCALE_SMOOTH)
          ImageIcon(scaled)
      finally stream.close()
    }.toOption.filter(_ != null)
