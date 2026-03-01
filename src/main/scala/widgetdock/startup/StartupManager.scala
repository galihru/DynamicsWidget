package widgetdock.startup

import scala.util.Try

object StartupManager:

  def registerWindowsStartup(
      shortcutName: String,
      targetPath: String,
      arguments: String,
      workingDirectory: String
  ): Either[String, Unit] =
    val script =
      s"""
         |$${startupDir} = [Environment]::GetFolderPath("Startup")
         |$${lnkPath} = Join-Path $${startupDir} "$shortcutName.lnk"
         |$${shell} = New-Object -ComObject WScript.Shell
         |$${shortcut} = $${shell}.CreateShortcut($${lnkPath})
         |$${shortcut}.TargetPath = "$targetPath"
         |$${shortcut}.Arguments = "$arguments"
         |$${shortcut}.WorkingDirectory = "$workingDirectory"
         |$${shortcut}.WindowStyle = 7
         |$${shortcut}.Save()
         |""".stripMargin

    val result = Try {
      val pb = new ProcessBuilder(
        "powershell",
        "-NoProfile",
        "-ExecutionPolicy",
        "Bypass",
        "-Command",
        script
      )
      val process = pb.start()
      val exitCode = process.waitFor()
      exitCode
    }.toEither

    result match
      case Right(0) => Right(())
      case Right(code) =>
        Left(s"Gagal register startup shortcut, exit code: $code")
      case Left(err) =>
        Left(s"Gagal menjalankan PowerShell startup registration: ${err.getMessage}")

