# Regenerates assets/bridge/textures/font/{suit,nt}.png.
#
# The four card suits are pasted from the hand-made pixel art in tools/suits/, one per
# glyph, in the order the font declares them: spade, heart, diamond, club.
#
# ## Why nothing is resampled any more
#
# Earlier revisions squashed every sprite so its ink was exactly 9 or 10 px tall, so that
# the four suits came out the same size. That sounded tidier than it looked: the spade is
# 11x12 and the club is 11x12, so aiming at 10 threw away two rows and two columns *and*
# invented shapes doing it. The spade's apex is a single pixel and the two notches that
# make a club a club are one pixel wide, so anything that resamples has to be very careful
# with them; a run-length resample was written to try, and it was still "a bit deformed"
# next to the source art.
#
# So now the atlas cell is simply the size of the largest source: 13x12. That is the
# spade/club ink (11x12) plus one pixel of bearing on each side. Every sprite is copied
# pixel for pixel, vertically and horizontally centred in its cell, and the four suits
# keep the proportions they were drawn with -- a spade really is taller than a heart.
#
# ## Why the cell is 13 wide
#
# net.minecraft.client.gui.font.providers.BitmapProvider$Definition.load computes each
# glyph's advance as `(int) (0.5 + inkWidth * scale) + 1` where inkWidth is measured from
# the *cell's* left edge (getActualGlyphWidth scans in from the right). So a glyph has no
# control over its advance beyond where it sits in the cell -- paste it at x = BEARING and
# the advance becomes inkWidth + 2, i.e. a 1 px bearing on either side, for every glyph.
# The cell just has to be wide enough for the widest ink (11) plus both bearings, hence 13.
#
# ## For that to reach the screen 1:1
#
# font/suit.json declares "height": 12, the same as the cell height, so BitmapProvider's
# `scale = height / cellHeight` is exactly 1 and the atlas is never resampled on the way
# out. "ascent": 9 is what centres the ink on the rank digit: the glyph box is
# [ascent - height, ascent] = [-3, 9], and an 11x12 spade pasted at cell row 0 therefore
# puts its ink on screen rows -3..8, concentric with the digit's ink at -1..6 (both centre
# on 2.5). The heart, whose own ink is 9 tall, is centred in the same cell and so lands
# symmetrically inside the same band.
#
# ## Colour
#
# The sources are quite dark (#252525 spade, #7F0000 heart, #00007F club) because they
# were lifted off a card face. On the auction's near-black cells a #252525 spade is
# invisible, so every sprite is scaled up -- each channel by the same factor, so the hue
# does not move -- until its brightest channel reaches PEAK. The suit glyph is then drawn
# untinted, so what ends up on screen is these exact colours.
#
# nt.png is the notrump glyph, drawn here rather than sourced, in 1 px strokes.

Add-Type -AssemblyName System.Drawing

$cellW = 13                    # widest ink (11) + a bearing on each side
$cellH = 12                    # tallest ink, i.e. the spade / club
$bearing = 1                   # left inset; gives every glyph a 1 px advance either side
$peak = 230                    # 0xE6: brightest channel every sprite is lifted to
$alphaFloor = 8                # below this a pixel counts as transparent

$tools = $PSScriptRoot
$assets = Join-Path $tools '..\src\main\resources\assets\bridge\textures\font'

# Order must match the `chars` array in font/suit.json.
$glyphs = @('spade', 'heart', 'diamond', 'club')

function New-CellCanvas([int]$cells) {
    return New-Object System.Drawing.Bitmap ($cells * $cellW), $cellH,
        ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
}

# Tight box around the non-transparent pixels, as x, y, width, height.
function Get-InkBox($bitmap) {
    $minX = $bitmap.Width
    $minY = $bitmap.Height
    $maxX = -1
    $maxY = -1
    for ($y = 0; $y -lt $bitmap.Height; $y++) {
        for ($x = 0; $x -lt $bitmap.Width; $x++) {
            if ($bitmap.GetPixel($x, $y).A -lt $alphaFloor) { continue }
            if ($x -lt $minX) { $minX = $x }
            if ($y -lt $minY) { $minY = $y }
            if ($x -gt $maxX) { $maxX = $x }
            if ($y -gt $maxY) { $maxY = $y }
        }
    }
    if ($maxX -lt 0) { throw 'sprite is fully transparent' }
    return @($minX, $minY, ($maxX - $minX + 1), ($maxY - $minY + 1))
}

# Copies one source sprite into its cell untouched: trim to the ink box, lift every channel
# by the same factor so the brightest one lands on PEAK, and centre it in the cell.
function Add-Sprite($target, [int]$column, $source) {
    $box = Get-InkBox $source
    $w = $box[2]
    $h = $box[3]
    if ($w + 2 * $bearing -gt $cellW -or $h -gt $cellH) {
        throw "$($w)x$($h) sprite does not fit the ${cellW}x${cellH} cell"
    }

    $brightest = 1
    for ($y = 0; $y -lt $h; $y++) {
        for ($x = 0; $x -lt $w; $x++) {
            $c = $source.GetPixel($box[0] + $x, $box[1] + $y)
            if ($c.A -lt $alphaFloor) { continue }
            $brightest = [math]::Max($brightest, [math]::Max($c.R, [math]::Max($c.G, $c.B)))
        }
    }
    $lift = $peak / [double]$brightest

    # Floor, not [int]: PowerShell's cast rounds half to even, so [int]((13 - 9) / 2) is the 2
    # you would not expect and the glyph ends up a pixel right.
    $offsetX = $column * $cellW + [int][math]::Floor(($cellW - $w) / 2)
    $offsetY = [int][math]::Floor(($cellH - $h) / 2)
    for ($y = 0; $y -lt $h; $y++) {
        for ($x = 0; $x -lt $w; $x++) {
            $c = $source.GetPixel($box[0] + $x, $box[1] + $y)
            if ($c.A -lt $alphaFloor) { continue }
            $target.SetPixel($offsetX + $x, $offsetY + $y, [System.Drawing.Color]::FromArgb(
                $c.A,
                [math]::Min(255, [int][math]::Round($c.R * $lift)),
                [math]::Min(255, [int][math]::Round($c.G * $lift)),
                [math]::Min(255, [int][math]::Round($c.B * $lift))))
        }
    }
}

# --- suits -------------------------------------------------------------------------

$suit = New-CellCanvas $glyphs.Count
for ($i = 0; $i -lt $glyphs.Count; $i++) {
    $path = Join-Path $tools "suits\$($glyphs[$i]).png"
    $source = [System.Drawing.Bitmap]::FromFile($path)
    Add-Sprite $suit $i $source
    $source.Dispose()
}
$suitPath = Join-Path $assets 'suit.png'
$suit.Save($suitPath, [System.Drawing.Imaging.ImageFormat]::Png)
$suit.Dispose()

# --- notrump -----------------------------------------------------------------------

# "NT" in the same ink band as the suits: rows 1..10, columns 1..9, so it lands on screen
# rows -2..7 exactly like a suit's ink band and reads the same height.
#
# Every stroke is one pixel -- the N's uprights and diagonal, and the T's arm and stem. An
# earlier cut had a 2 px T stem and a 2 px N diagonal, which read as bold next to the suits.
$ntArt = @(
    '.............',
    '.N...N.TTT...',
    '.NN..N..T....',
    '.N.N.N..T....',
    '.N.N.N..T....',
    '.N..NN..T....',
    '.N...N..T....',
    '.N...N..T....',
    '.N...N..T....',
    '.N...N..T....',
    '.N...N..T....',
    '.............'
)
if ($ntArt.Count -ne $cellH) { throw "nt art has $($ntArt.Count) rows, expected $cellH" }
for ($i = 0; $i -lt $cellH; $i++) {
    if ($ntArt[$i].Length -ne $cellW) { throw "nt row $i is $($ntArt[$i].Length) wide, expected $cellW" }
}

$nt = New-CellCanvas 1
$ink = [System.Drawing.Color]::FromArgb(255, $peak, $peak, $peak)
for ($y = 0; $y -lt $cellH; $y++) {
    for ($x = 0; $x -lt $cellW; $x++) {
        if ($ntArt[$y][$x] -ne '.') { $nt.SetPixel($x, $y, $ink) }
    }
}
$ntPath = Join-Path $assets 'nt.png'
$nt.Save($ntPath, [System.Drawing.Imaging.ImageFormat]::Png)
$nt.Dispose()

"saved {0} ({1}x{2}) and {3} ({4}x{5})" -f $suitPath, ($cellW * $glyphs.Count), $cellH, $ntPath, $cellW, $cellH
