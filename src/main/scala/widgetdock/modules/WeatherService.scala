package widgetdock.modules

import java.net.URI
import java.net.URLEncoder
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import scala.util.Try
import ujson.Value

final case class DailyForecast(
    dayLabel: String,
    dateIso: String,
    minC: Double,
    maxC: Double,
    condition: String,
    iconCode: String
):
  def iconUrl: String = WeatherService.iconUrl(iconCode)

final case class WeatherSnapshot(
    locationLabel: String,
    temperatureC: Double,
    feelsLikeC: Double,
    humidityPct: Double,
    windKmh: Double,
    weatherCode: Int,
    isDay: Boolean,
    condition: String,
    iconCode: String,
    daily: Vector[DailyForecast],
    updatedAtMs: Long
):
  def iconUrl: String = WeatherService.iconUrl(iconCode)

object WeatherService:

  private val client = HttpClient.newBuilder().build()

  def fetchByCity(city: String): Either[String, WeatherSnapshot] =
    val query = city.trim
    if query.isEmpty then return Left("City is empty")

    val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8)
    val geoUrl =
      s"https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=1&language=en&format=json"

    for
      geoJson <- fetchJson(geoUrl)
      lat <- readPath(geoJson, Seq("results", 0, "latitude"))
      lon <- readPath(geoJson, Seq("results", 0, "longitude"))
      cityName <- readPath(geoJson, Seq("results", 0, "name"))
      country <- readPath(geoJson, Seq("results", 0, "country"))
      weather <- fetchWeather(lat.num, lon.num)
    yield weather.copy(locationLabel = s"${cityName.str}, ${country.str}")

  def iconUrl(iconCode: String): String =
    s"https://openweathermap.org/img/wn/${iconCode}@2x.png"

  private def fetchWeather(lat: Double, lon: Double): Either[String, WeatherSnapshot] =
    val weatherUrl =
      s"https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m,is_day&daily=weather_code,temperature_2m_max,temperature_2m_min&timezone=auto"

    for
      json <- fetchJson(weatherUrl)
      cur <- readPath(json, Seq("current"))
      temp <- readPath(cur, Seq("temperature_2m"))
      feels <- readPath(cur, Seq("apparent_temperature"))
      humidity <- readPath(cur, Seq("relative_humidity_2m"))
      code <- readPath(cur, Seq("weather_code"))
      wind <- readPath(cur, Seq("wind_speed_10m"))
      isDayV <- readPath(cur, Seq("is_day"))
      daily <- parseDaily(json)
    yield
      val codeInt = code.num.toInt
      val isDay = isDayV.num.toInt == 1
      WeatherSnapshot(
        locationLabel = "",
        temperatureC = temp.num,
        feelsLikeC = feels.num,
        humidityPct = humidity.num,
        windKmh = wind.num,
        weatherCode = codeInt,
        isDay = isDay,
        condition = weatherCodeToLabel(codeInt),
        iconCode = iconCodeFromWeatherCode(codeInt, isDay),
        daily = daily,
        updatedAtMs = System.currentTimeMillis()
      )

  private def parseDaily(json: Value): Either[String, Vector[DailyForecast]] =
    Try {
      val daily = json("daily")
      val dates = daily("time").arr.toVector.map(_.str)
      val codes = daily("weather_code").arr.toVector.map(_.num.toInt)
      val maxs = daily("temperature_2m_max").arr.toVector.map(_.num)
      val mins = daily("temperature_2m_min").arr.toVector.map(_.num)
      val size = List(dates.size, codes.size, maxs.size, mins.size).min
      (0 until size).toVector.map { i =>
        val label = dayLabel(i, dates(i))
        val code = codes(i)
        DailyForecast(
          dayLabel = label,
          dateIso = dates(i),
          minC = mins(i),
          maxC = maxs(i),
          condition = weatherCodeToLabel(code),
          iconCode = iconCodeFromWeatherCode(code, isDay = true)
        )
      }
    }.toEither.left.map(err => s"Daily weather parse error: ${err.getMessage}")

  private def dayLabel(index: Int, dateIso: String): String =
    if index == 0 then "Today"
    else if index == 1 then "Tomorrow"
    else
      Try {
        val d = LocalDate.parse(dateIso, DateTimeFormatter.ISO_LOCAL_DATE)
        d.getDayOfWeek.toString.take(3)
      }.getOrElse(s"Day ${index + 1}")

  private def fetchJson(url: String): Either[String, Value] =
    Try {
      val req = HttpRequest
        .newBuilder()
        .uri(URI.create(url))
        .timeout(java.time.Duration.ofSeconds(10))
        .GET()
        .build()
      val res = client.send(req, HttpResponse.BodyHandlers.ofString())
      if res.statusCode() >= 200 && res.statusCode() < 300 then
        ujson.read(res.body())
      else throw RuntimeException(s"HTTP ${res.statusCode()}")
    }.toEither.left.map(err => s"Weather request failed: ${err.getMessage}")

  private def readPath(root: Value, path: Seq[Any]): Either[String, Value] =
    Try {
      path.foldLeft(root) {
        case (v, key: String) => v(key)
        case (v, idx: Int)    => v(idx)
        case (_, other)       => throw RuntimeException(s"Unsupported path token: $other")
      }
    }.toEither.left.map(_ => s"Missing data: ${path.mkString(".")}")

  private def iconCodeFromWeatherCode(code: Int, isDay: Boolean): String =
    val suffix = if isDay then "d" else "n"
    code match
      case 0                             => s"01$suffix"
      case 1 | 2                         => s"02$suffix"
      case 3                             => s"03$suffix"
      case 45 | 48                       => s"50$suffix"
      case 51 | 53 | 55 | 56 | 57        => s"09$suffix"
      case 61 | 63 | 65 | 66 | 67        => s"10$suffix"
      case 71 | 73 | 75 | 77 | 85 | 86   => s"13$suffix"
      case 80 | 81 | 82                  => s"10$suffix"
      case 95 | 96 | 99                  => s"11$suffix"
      case _                             => s"03$suffix"

  private def weatherCodeToLabel(code: Int): String =
    code match
      case 0                    => "Clear"
      case 1 | 2                => "Partly Cloudy"
      case 3                    => "Cloudy"
      case 45 | 48              => "Fog"
      case 51 | 53 | 55         => "Drizzle"
      case 56 | 57              => "Freezing Drizzle"
      case 61 | 63 | 65         => "Rain"
      case 66 | 67              => "Freezing Rain"
      case 71 | 73 | 75 | 77    => "Snow"
      case 80 | 81 | 82         => "Rain Showers"
      case 85 | 86              => "Snow Showers"
      case 95 | 96 | 99         => "Thunderstorm"
      case _                    => "Unknown"

