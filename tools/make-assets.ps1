# Regenerates the hand-made binary assets for the bridge addon:
#
#   assets/bridge/textures/gui/game/contract_bridge.png  the game-select icon (source PNG, unscaled)
#   assets/bridge/textures/font/suit.png                 8x8 bitmap glyphs for spades/hearts/diamonds/clubs
#   assets/bridge/textures/font/nt.png                   16x8 bitmap glyph for "NT"
#
# Bitmap-font PNGs live under textures/font/, not font/: a provider's "file" is resolved relative to
# assets/<namespace>/textures/, so "bridge:font/suit.png" means assets/bridge/textures/font/suit.png,
# while the font json itself sits in assets/bridge/font/.
#
# The suit glyphs are white so the game can tint them with the deck's own suit colour, exactly like
# vanilla bitmap glyphs. Shapes are defined in a 64x64 space and rasterised at 8x supersampling.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\make-assets.ps1

param(
    [string]$IconSource = "C:\Users\BigMer\Desktop\桥牌\bridge_icon.png",
    [string]$Resources = (Join-Path $PSScriptRoot '..\src\main\resources'),
    [int]$IconSize = 0
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$assets = Join-Path $Resources 'assets\bridge'
$fontTextures = Join-Path $assets 'textures\font'
New-Item -ItemType Directory -Force -Path (Join-Path $assets 'textures\gui\game') | Out-Null
New-Item -ItemType Directory -Force -Path $fontTextures | Out-Null

function New-Bitmap([int]$w, [int]$h) {
    return New-Object System.Drawing.Bitmap -ArgumentList $w, $h, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
}

function New-Graphics($bitmap) {
    $g = [System.Drawing.Graphics]::FromImage($bitmap)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
    $g.Clear([System.Drawing.Color]::Transparent)
    return $g
}

# ---------------------------------------------------------------- game icon ---
# Charta blits a game icon with full 0..1 UVs over a 70x70 quad, so the source can be any resolution:
# copy the original untouched and let the GPU filter it down. Pass -IconSize to force a downscale.
if ($IconSize -gt 0) {
    $src = [System.Drawing.Image]::FromFile($IconSource)
    $icon = New-Bitmap $IconSize $IconSize
    $ig = New-Graphics $icon
    $ig.DrawImage($src, (New-Object System.Drawing.Rectangle(0, 0, $IconSize, $IconSize)))
    $ig.Dispose(); $src.Dispose()
    $iconPath = Join-Path $assets 'textures\gui\game\contract_bridge.png'
    $icon.Save($iconPath, [System.Drawing.Imaging.ImageFormat]::Png)
    $icon.Dispose()
    Write-Host "icon      -> $iconPath (resized to $IconSize)"
} else {
    $iconPath = Join-Path $assets 'textures\gui\game\contract_bridge.png'
    Copy-Item $IconSource $iconPath -Force
    Write-Host "icon      -> $iconPath (original size, copied)"
}

# -------------------------------------------------------------- suit glyphs ---
$SS = 8      # supersampling factor
$CELL = 8    # output glyph cell, in bitmap-font pixels

function Add-Circle($g, [double]$cx, [double]$cy, [double]$r) {
    $b = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::White)
    $g.FillEllipse($b, [float](($cx - $r) * $SS), [float](($cy - $r) * $SS), [float](2 * $r * $SS), [float](2 * $r * $SS))
    $b.Dispose()
}

function Add-Poly($g, $points) {
    $pts = @()
    for ($i = 0; $i -lt $points.Count; $i += 2) {
        $pts += New-Object System.Drawing.PointF([float]($points[$i] * $SS), [float]($points[$i + 1] * $SS))
    }
    $b = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::White)
    $g.FillPolygon($b, $pts)
    $b.Dispose()
}

function Draw-Spade($g) {
    Add-Poly $g @(7, 39, 57, 39, 32, 6)
    Add-Circle $g 22 39 15
    Add-Circle $g 42 39 15
    Add-Poly $g @(23, 58, 41, 58, 32, 33)
}

function Draw-Heart($g) {
    Add-Circle $g 22 25 15
    Add-Circle $g 42 25 15
    Add-Poly $g @(7, 25, 57, 25, 32, 58)
}

function Draw-Diamond($g) {
    Add-Poly $g @(32, 3, 59, 32, 32, 61, 5, 32)
}

function Draw-Club($g) {
    Add-Circle $g 32 17 12
    Add-Circle $g 17 44 12
    Add-Circle $g 47 44 12
    Add-Poly $g @(25, 58, 39, 58, 32, 30)
}

# Rasterises one shape at 8x and squashes it into a CELL x CELL glyph.
function Render-SuitGlyph($drawer) {
    $canvas = New-Bitmap (64 * $SS) (64 * $SS)
    $cg = New-Graphics $canvas
    & $drawer $cg
    $cg.Dispose()

    $out = New-Bitmap $CELL $CELL
    $og = New-Graphics $out
    $og.DrawImage($canvas, (New-Object System.Drawing.Rectangle(0, 0, $CELL, $CELL)))
    $og.Dispose()
    $canvas.Dispose()
    return $out
}

# One row of four cells, in the order spades, hearts, diamonds, clubs.
$sheet = New-Bitmap ($CELL * 4) $CELL
$sg = New-Graphics $sheet
$order = @(${function:Draw-Spade}, ${function:Draw-Heart}, ${function:Draw-Diamond}, ${function:Draw-Club})
for ($i = 0; $i -lt 4; $i++) {
    $glyph = Render-SuitGlyph $order[$i]
    $sg.DrawImageUnscaled($glyph, $i * $CELL, 0)
    $glyph.Dispose()
}
$sg.Dispose()
$suitPath = Join-Path $fontTextures 'suit.png'
$sheet.Save($suitPath, [System.Drawing.Imaging.ImageFormat]::Png)
$sheet.Dispose()
Write-Host "suit font -> $suitPath"

# ------------------------------------------------------------------- "NT" ---
# 5x7 pixel letters, stamped straight onto the sheet so they stay crisp.
$glyphN = @('#...#', '#...#', '##..#', '#.#.#', '#..##', '#...#', '#...#')
$glyphT = @('#####', '..#..', '..#..', '..#..', '..#..', '..#..', '..#..')
$nt = New-Bitmap 16 8
$white = [System.Drawing.Color]::White
for ($row = 0; $row -lt 7; $row++) {
    for ($col = 0; $col -lt 5; $col++) {
        if ($glyphN[$row][$col] -eq '#') { $nt.SetPixel(1 + $col, $row, $white) }
        if ($glyphT[$row][$col] -eq '#') { $nt.SetPixel(9 + $col, $row, $white) }
    }
}
$ntPath = Join-Path $fontTextures 'nt.png'
$nt.Save($ntPath, [System.Drawing.Imaging.ImageFormat]::Png)
$nt.Dispose()
Write-Host "nt font   -> $ntPath"
