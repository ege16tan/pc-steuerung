# build-icon.ps1
#
# Erzeugt "PC Steuerung.ico" aus denselben Werten wie das Android-App-Icon
# (Android App/app/src/main/res/drawable/ic_launcher_background.xml und
#  ic_launcher_foreground.xml):
#   - Hintergrund: LinearGradient #0F766E -> #1E293B
#   - Vordergrund: weisser Power-Ring (offen oben) + Stiel
#
# Das Ergebnis ist eine Multi-Size-ICO (16/24/32/48/64/128/256 px) fuer
# Desktop-Verknuepfungen, Startmenu und Installer.
#
# Ausfuehren:  powershell -ExecutionPolicy Bypass -File scripts\build-icon.ps1

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = Split-Path -Parent $here
$target = Join-Path $projectRoot 'PC Steuerung.ico'

# Android-Vektoren (viewport 108x108) exakt nachbauen.
$viewport = 108.0
$centerX = 54.0
$centerY = 54.0
$ringRadius = 20.0
$ringStroke = 10.0
$stemStroke = 9.0
$stemTopY = 30.0
$stemBottomY = 57.0
# Bogen: Start rechts oben (321,76 deg), 256,48 deg im Uhrzeigersinn -> Luecke oben.
$arcStart = 321.76
$arcSweep = 256.48

function New-RoundedRectPath([float]$size, [float]$radius) {
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $d = $radius * 2
    $path.AddArc(0, 0, $d, $d, 180, 90)
    $path.AddArc($size - $d, 0, $d, $d, 270, 90)
    $path.AddArc($size - $d, $size - $d, $d, $d, 0, 90)
    $path.AddArc(0, $size - $d, $d, $d, 90, 90)
    $path.CloseFigure()
    return $path
}

function New-IconBitmap([int]$size) {
    $scale = $size / $viewport
    $bmp = New-Object System.Drawing.Bitmap $size, $size, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.Clear([System.Drawing.Color]::Transparent)

    # Abgerundete Ecken wie ein modernes App-Icon (Radius ~22% der Kantenlaenge).
    $radius = [float]($size * 0.22)
    $clip = New-RoundedRectPath $size $radius
    $g.SetClip($clip)

    $rect = New-Object System.Drawing.RectangleF 0, 0, $size, $size
    $teal = [System.Drawing.ColorTranslator]::FromHtml('#0F766E')
    $navy = [System.Drawing.ColorTranslator]::FromHtml('#1E293B')
    $brush = New-Object System.Drawing.Drawing2D.LinearGradientBrush($rect, $teal, $navy, [float]45)
    $g.FillRectangle($brush, $rect)

    # Power-Ring (oben offen) + Stiel, beide mit runden Enden.
    $pen = New-Object System.Drawing.Pen ([System.Drawing.Color]::White), ([float]($ringStroke * $scale))
    $pen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
    $pen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
    $arcRect = New-Object System.Drawing.RectangleF `
        ([float](($centerX - $ringRadius) * $scale)), `
        ([float](($centerY - $ringRadius) * $scale)), `
        ([float](($ringRadius * 2) * $scale)), `
        ([float](($ringRadius * 2) * $scale))
    $g.DrawArc($pen, $arcRect, [float]$arcStart, [float]$arcSweep)

    $stemPen = New-Object System.Drawing.Pen ([System.Drawing.Color]::White), ([float]($stemStroke * $scale))
    $stemPen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
    $stemPen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
    $g.DrawLine(
        $stemPen,
        [float]($centerX * $scale), [float]($stemBottomY * $scale),
        [float]($centerX * $scale), [float]($stemTopY * $scale)
    )

    $pen.Dispose()
    $stemPen.Dispose()
    $brush.Dispose()
    $g.Dispose()
    $clip.Dispose()
    return $bmp
}

$sizes = @(16, 24, 32, 48, 64, 128, 256)
$pngData = @()

foreach ($size in $sizes) {
    $bmp = New-IconBitmap $size
    $ms = New-Object System.IO.MemoryStream
    $bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
    $pngData += , ($ms.ToArray())
    $ms.Dispose()
    $bmp.Dispose()
}

# ICO-Container mit PNG-Eintraegen (von Windows seit Vista unterstuetzt).
$stream = New-Object System.IO.MemoryStream
$writer = New-Object System.IO.BinaryWriter($stream)

$writer.Write([UInt16]0)             # reserved
$writer.Write([UInt16]1)             # type = icon
$writer.Write([UInt16]$sizes.Count)  # image count

$offset = 6 + (16 * $sizes.Count)
for ($i = 0; $i -lt $sizes.Count; $i++) {
    $size = $sizes[$i]
    $bytes = $pngData[$i]
    $dim = if ($size -ge 256) { 0 } else { $size }
    $writer.Write([Byte]$dim)        # width  (0 = 256)
    $writer.Write([Byte]$dim)        # height
    $writer.Write([Byte]0)           # palette
    $writer.Write([Byte]0)           # reserved
    $writer.Write([UInt16]1)         # color planes
    $writer.Write([UInt16]32)        # bits per pixel
    $writer.Write([UInt32]$bytes.Length)
    $writer.Write([UInt32]$offset)
    $offset += $bytes.Length
}
foreach ($bytes in $pngData) { $writer.Write($bytes) }

$writer.Flush()
[System.IO.File]::WriteAllBytes($target, $stream.ToArray())
$writer.Dispose()
$stream.Dispose()

$info = Get-Item -LiteralPath $target
Write-Host ("Icon erstellt: {0} ({1:N1} KB, {2} Groessen: {3})" -f $info.FullName, ($info.Length / 1KB), $sizes.Count, ($sizes -join ', '))
