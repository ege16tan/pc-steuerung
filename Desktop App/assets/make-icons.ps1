# make-icons.ps1
#
# Erzeugt die Icon-Dateien der WinForms-Desktop-App:
#   app.ico            - Kopie von "PC Steuerung.ico" (mit Pruefung/Fallback)
#   tray-running.ico   - gefuellter Kreis #2ECC71
#   tray-starting.ico  - gefuellter Kreis #F59E0B
#   tray-stopped.ico   - gefuellter Kreis #9199A6
#   tray-error.ico     - gefuellter Kreis #E74C3C
#
# Die Tray-Icons enthalten 16/24/32 px, jeweils 32bpp mit Alpha als klassische
# BMP-Eintraege (BITMAPINFOHEADER + XOR-Bitmap + AND-Maske), kein PNG.
#
# Ausfuehren:
#   powershell.exe -NoProfile -ExecutionPolicy Bypass -File "Desktop App\assets\make-icons.ps1"
#
# Wird nicht mit ausgeliefert, dient nur der Reproduzierbarkeit.

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$desktopApp = Split-Path -Parent $here
$projectRoot = Split-Path -Parent $desktopApp
$sourceIcon = Join-Path $projectRoot 'assets\PC Steuerung.ico'
$buildIconScript = Join-Path $projectRoot 'scripts\build-icon.ps1'
$minimumSizes = @(16, 32)

# ---------------------------------------------------------------- ICO-Header lesen
function Get-IcoEntries([string]$Path) {
    $bytes = [System.IO.File]::ReadAllBytes($Path)
    if ($bytes.Length -lt 6) { throw "Datei zu kurz ($($bytes.Length) Bytes): $Path" }
    if ([BitConverter]::ToUInt16($bytes, 0) -ne 0) { throw "ICONDIR reserved != 0: $Path" }
    if ([BitConverter]::ToUInt16($bytes, 2) -ne 1) { throw "Kein Icon-Typ (type != 1): $Path" }
    $count = [BitConverter]::ToUInt16($bytes, 4)
    if ($count -lt 1) { throw "Keine Bildeintraege: $Path" }
    $entries = @()
    for ($i = 0; $i -lt $count; $i++) {
        $o = 6 + 16 * $i
        $w = [int]$bytes[$o]; if ($w -eq 0) { $w = 256 }
        $h = [int]$bytes[$o + 1]; if ($h -eq 0) { $h = 256 }
        $entries += [pscustomobject]@{
            Width        = $w
            Height       = $h
            BitsPerPixel = [BitConverter]::ToUInt16($bytes, $o + 6)
            Length       = [BitConverter]::ToUInt32($bytes, $o + 8)
            Offset       = [BitConverter]::ToUInt32($bytes, $o + 12)
        }
    }
    return $entries
}

# --------------------------------------------------------------- Tray-Bitmap malen
function New-TrayBitmap([int]$Size, [System.Drawing.Color]$Color) {
    $bmp = New-Object System.Drawing.Bitmap $Size, $Size, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.Clear([System.Drawing.Color]::Transparent)

    # Kreis fuellt ~80 % der Flaeche, Rand bleibt transparent.
    $diameter = [float]($Size * 0.80)
    $inset = [float](($Size - $diameter) / 2.0)
    $rect = New-Object System.Drawing.RectangleF $inset, $inset, $diameter, $diameter

    # 1. dunkler Aussenring (35 % Deckkraft) auf der Kreislinie
    $ringColor = [System.Drawing.Color]::FromArgb(89, 15, 23, 42)
    $ringPen = New-Object System.Drawing.Pen $ringColor, ([float]1.0)
    $g.DrawEllipse($ringPen, $rect)
    $ringPen.Dispose()

    # 2. gefuellter Kreis in der Statusfarbe
    $brush = New-Object System.Drawing.SolidBrush $Color
    $g.FillEllipse($brush, $rect)
    $brush.Dispose()

    $g.Dispose()
    return $bmp
}

# ------------------------------------------------------- ICO schreiben (32bpp BMP)
function Write-Ico([string]$Path, $Bitmaps) {
    $payloads = @()

    foreach ($bmp in $Bitmaps) {
        $w = $bmp.Width
        $h = $bmp.Height
        $rect = New-Object System.Drawing.Rectangle 0, 0, $w, $h
        $locked = $bmp.LockBits($rect,
            [System.Drawing.Imaging.ImageLockMode]::ReadOnly,
            [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
        $stride = $locked.Stride
        $raw = New-Object byte[] ($stride * $h)
        [System.Runtime.InteropServices.Marshal]::Copy($locked.Scan0, $raw, 0, $raw.Length)
        $bmp.UnlockBits($locked)

        $rowBytes = $w * 4                                     # BGRA, 4 Bytes je Pixel
        $xorSize = $rowBytes * $h
        $andStride = [int]([math]::Floor(($w + 31) / 32) * 4)  # 1 Bit je Pixel, 4-Byte-ausgerichtet
        $andSize = $andStride * $h

        $ms = New-Object System.IO.MemoryStream
        $bw = New-Object System.IO.BinaryWriter($ms)

        # BITMAPINFOHEADER (40 Bytes); Hoehe = 2*h wegen XOR-Bitmap + AND-Maske
        $bw.Write([UInt32]40)                      # biSize
        $bw.Write([Int32]$w)                       # biWidth
        $bw.Write([Int32]($h * 2))                 # biHeight
        $bw.Write([UInt16]1)                       # biPlanes
        $bw.Write([UInt16]32)                      # biBitCount
        $bw.Write([UInt32]0)                       # biCompression = BI_RGB
        $bw.Write([UInt32]($xorSize + $andSize))   # biSizeImage
        $bw.Write([Int32]0)                        # biXPelsPerMeter
        $bw.Write([Int32]0)                        # biYPelsPerMeter
        $bw.Write([UInt32]0)                       # biClrUsed
        $bw.Write([UInt32]0)                       # biClrImportant

        # XOR-Bitmap: zeilenweise von unten nach oben
        for ($y = $h - 1; $y -ge 0; $y--) {
            $bw.Write($raw, ($y * $stride), $rowBytes)
        }

        # AND-Maske: komplett 0 (voll deckend), Transparenz steckt im Alpha-Kanal
        $mask = New-Object byte[] $andSize
        $bw.Write($mask, 0, $andSize)

        $bw.Flush()
        $payloads += , $ms.ToArray()
        $bw.Dispose()
        $ms.Dispose()
    }

    $count = $Bitmaps.Count
    $stream = New-Object System.IO.MemoryStream
    $writer = New-Object System.IO.BinaryWriter($stream)
    $writer.Write([UInt16]0)       # reserved
    $writer.Write([UInt16]1)       # type = icon
    $writer.Write([UInt16]$count)  # Anzahl Bilder

    $offset = 6 + (16 * $count)
    for ($i = 0; $i -lt $count; $i++) {
        $size = $Bitmaps[$i].Width
        $dim = if ($size -ge 256) { 0 } else { $size }  # 0 bedeutet 256
        $bytes = $payloads[$i]
        $writer.Write([Byte]$dim)    # Breite
        $writer.Write([Byte]$dim)    # Hoehe
        $writer.Write([Byte]0)       # Farbpalette
        $writer.Write([Byte]0)       # reserved
        $writer.Write([UInt16]1)     # Ebenen
        $writer.Write([UInt16]32)    # Bit pro Pixel
        $writer.Write([UInt32]$bytes.Length)
        $writer.Write([UInt32]$offset)
        $offset += $bytes.Length
    }
    foreach ($bytes in $payloads) { $writer.Write($bytes) }
    $writer.Flush()
    [System.IO.File]::WriteAllBytes($Path, $stream.ToArray())
    $writer.Dispose()
    $stream.Dispose()
}

# ------------------------------------------------------------------ 1) app.ico
$appIcon = Join-Path $here 'app.ico'
$needRebuild = $true

if (Test-Path -LiteralPath $sourceIcon) {
    try {
        $entries = @(Get-IcoEntries $sourceIcon)
        $sizes = @($entries | ForEach-Object { [int]$_.Width })
        $missing = @($minimumSizes | Where-Object { $sizes -notcontains $_ })
        if ($missing.Count -eq 0) {
            Copy-Item -LiteralPath $sourceIcon -Destination $appIcon -Force
            Write-Host ("app.ico          : Kopie von '{0}' (Groessen: {1})" -f `
                (Split-Path -Leaf $sourceIcon), ($sizes -join ', '))
            $needRebuild = $false
        }
        else {
            Write-Warning ("Quelle ohne {0}px - Icon wird neu gebaut." -f ($missing -join '/'))
        }
    }
    catch {
        Write-Warning ("Quelle nicht lesbar ({0}) - Icon wird neu gebaut." -f $_.Exception.Message)
    }
}
else {
    Write-Warning ("Quelle fehlt: {0} - Icon wird neu gebaut." -f $sourceIcon)
}

if ($needRebuild) {
    if (-not (Test-Path -LiteralPath $buildIconScript)) {
        throw "Fallback-Skript fehlt: $buildIconScript"
    }
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $buildIconScript | Out-Host
    if (-not (Test-Path -LiteralPath $sourceIcon)) { throw "build-icon.ps1 hat kein Icon erzeugt." }
    Copy-Item -LiteralPath $sourceIcon -Destination $appIcon -Force
    Write-Host 'app.ico          : neu gebaut und kopiert'
}

$appSizes = @((@(Get-IcoEntries $appIcon) | ForEach-Object { [int]$_.Width }))
foreach ($need in $minimumSizes) {
    if ($appSizes -notcontains $need) { throw "app.ico enthaelt kein ${need}x${need}." }
}

# -------------------------------------------------------------- 2) Tray-Icons
$traySizes = @(16, 24, 32)
$trayIcons = [ordered]@{
    'tray-running.ico'  = '#2ECC71'
    'tray-starting.ico' = '#F59E0B'
    'tray-stopped.ico'  = '#9199A6'
    'tray-error.ico'    = '#E74C3C'
}

foreach ($name in $trayIcons.Keys) {
    $color = [System.Drawing.ColorTranslator]::FromHtml($trayIcons[$name])
    $bitmaps = @()
    foreach ($size in $traySizes) { $bitmaps += (New-TrayBitmap $size $color) }
    Write-Ico (Join-Path $here $name) $bitmaps
    foreach ($bmp in $bitmaps) { $bmp.Dispose() }
}
